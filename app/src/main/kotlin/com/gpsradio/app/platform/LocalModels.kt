package com.gpsradio.app.platform

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.gpsradio.core.ai.LocalWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** An open model the listener can download for free, on-device stories (spec A §69), run by Google's LiteRT-LM. */
data class LocalModelSpec(val id: String, val label: String, val url: String, val fileName: String, val sizeMb: Int)

object LocalModelCatalog {
    private const val HF = "https://huggingface.co/litert-community"
    val all = listOf(
        LocalModelSpec("gemma4-e2b", "Gemma 4 E2B", "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", "gemma-4-E2B-it.litertlm", 2588),
        LocalModelSpec("qwen3-1.7b", "Qwen3 1.7B", "$HF/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", 977),
        LocalModelSpec("gemma4-e4b", "Gemma 4 E4B", "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", "gemma-4-E4B-it.litertlm", 3660),
    )
    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

/** Gemini Nano's state on this phone (Android AICore). */
enum class NanoState { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** A model download: [fraction] null while the size is unknown; [error] set when it failed. */
data class ModelDownload(val fraction: Float? = null, val error: String? = null)

/**
 * The on-device story writers (spec A §69): Gemini Nano where the phone has it (Pixel, Galaxy, recent Xiaomi
 * flagships), or an open model downloaded once. The choice is a setting: "auto" (Nano if ready, else a
 * downloaded model), "off", "nano" or a model id.
 */
class LocalModels(private val context: Context, http: OkHttpClient, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()
    private val _downloads = MutableStateFlow<Map<String, ModelDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownload>> = _downloads.asStateFlow()
    private val _nano = MutableStateFlow(NanoState.UNKNOWN)
    val nano: StateFlow<NanoState> = _nano.asStateFlow()
    private val jobs = HashMap<String, Job>()

    private val nanoWriter = NanoWriter()
    private val liteRt = HashMap<String, LiteRtWriter>()

    private fun scanInstalled() = LocalModelCatalog.all.filter { File(dir, it.fileName).isFile }.map { it.id }.toSet()

    fun fileOf(spec: LocalModelSpec) = File(dir, spec.fileName)

    suspend fun refreshNano(): NanoState {
        val state = nanoWriter.state()
        if (_nano.value != NanoState.DOWNLOADING || state == NanoState.AVAILABLE) _nano.value = state
        return _nano.value
    }

    /** The writer for [choice]; it answers "not available" until its model is ready. */
    fun writer(choice: () -> String): LocalWriter = object : LocalWriter {
        override val name: String get() = current()?.name ?: "none"

        private suspend fun resolve(): LocalWriter? = when (val c = choice()) {
            "off" -> null
            "nano" -> nanoWriter
            "auto" -> if (refreshNano() == NanoState.AVAILABLE) nanoWriter
                else LocalModelCatalog.all.firstOrNull { it.id in _installed.value }?.let { liteRtFor(it) }
            else -> LocalModelCatalog.byId(c)?.takeIf { it.id in _installed.value }?.let { liteRtFor(it) }
        }

        private fun current(): LocalWriter? = when (val c = choice()) {
            "off" -> null
            "nano" -> nanoWriter
            else -> LocalModelCatalog.byId(c)?.let { liteRtFor(it) } ?: nanoWriter
        }

        override suspend fun available(): Boolean = resolve()?.available() == true

        override suspend fun write(prompt: String): String? = resolve()?.write(prompt)
    }

    private fun liteRtFor(spec: LocalModelSpec) = synchronized(liteRt) {
        liteRt.getOrPut(spec.id) { LiteRtWriter(spec.label, fileOf(spec), context.cacheDir) }
    }

    /** Starts the download of [id] ("nano" asks AICore to fetch Gemini Nano). */
    fun download(id: String) {
        if (id == NANO) {
            _nano.value = NanoState.DOWNLOADING
            scope.launch {
                runCatching { nanoWriter.download() }.onFailure { Log.w(TAG, "Nano download failed", it) }
                _nano.value = nanoWriter.state()
            }
            return
        }
        val spec = LocalModelCatalog.byId(id) ?: return
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            _downloads.update { it + (id to ModelDownload()) }
            jobs[id] = scope.launch(Dispatchers.IO) {
                val error = runCatching { fetch(spec) }.exceptionOrNull()
                if (error == null) {
                    _installed.value = scanInstalled()
                    _downloads.update { it - id }
                } else if (isActive) {
                    Log.w(TAG, "Download of ${spec.id} failed", error)
                    _downloads.update { it + (id to ModelDownload(error = error.message ?: error.javaClass.simpleName)) }
                }
            }
        }
    }

    fun cancel(id: String) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        _downloads.update { it - id }
    }

    fun delete(id: String) {
        val spec = LocalModelCatalog.byId(id) ?: return
        cancel(id)
        synchronized(liteRt) { liteRt.remove(id)?.close() }
        fileOf(spec).delete()
        File(dir, spec.fileName + ".part").delete()
        _installed.value = scanInstalled()
    }

    /** Downloads with resume: a broken connection continues where it stopped. */
    private suspend fun fetch(spec: LocalModelSpec) {
        val part = File(dir, spec.fileName + ".part")
        val needed = spec.sizeMb * 1_000_000L - part.length() + 200_000_000L
        if (dir.usableSpace < needed) throw IllegalStateException("Not enough free space: ${needed / 1_000_000} MB needed")
        var attempt = 0
        while (true) {
            try {
                download(spec, part)
                break
            } catch (e: java.io.IOException) {
                if (++attempt >= 5 || !kotlin.coroutines.coroutineContext.isActive) throw e
                kotlinx.coroutines.delay(3_000L * attempt)
            }
        }
        if (!part.renameTo(fileOf(spec))) throw IllegalStateException("Could not save the model")
    }

    private suspend fun download(spec: LocalModelSpec, part: File) {
        val have = part.length()
        val request = Request.Builder().url(spec.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416) return // already complete
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val append = response.code == 206
            val body = response.body ?: throw java.io.IOException("Empty response")
            val total = body.contentLength().takeIf { it > 0 }?.let { it + if (append) have else 0 }
            var done = if (append) have else 0L
            var lastReport = 0L
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (!kotlin.coroutines.coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReport > 4_000_000) {
                            lastReport = done
                            val fraction = total?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
                            _downloads.update { it + (spec.id to ModelDownload(fraction)) }
                        }
                    }
                }
            }
            if (total != null && done < total) throw java.io.IOException("Download interrupted")
        }
    }

    companion object {
        const val NANO = "nano"
        private const val TAG = "LocalModels"
    }
}

/** Gemini Nano through ML Kit GenAI (Android AICore): only on phones that ship it. */
private class NanoWriter : LocalWriter {
    override val name = "Gemini Nano"
    private var client: GenerativeModel? = null

    @Synchronized
    private fun model(): GenerativeModel? = client ?: runCatching { Generation.getClient() }.getOrNull()?.also { client = it }

    suspend fun state(): NanoState {
        val status = runCatching { model()?.checkStatus() }.getOrNull() ?: return NanoState.UNAVAILABLE
        return when (status) {
            FeatureStatus.AVAILABLE -> NanoState.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoState.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoState.DOWNLOADING
            else -> NanoState.UNAVAILABLE
        }
    }

    suspend fun download() {
        model()?.download()?.collect { }
    }

    override suspend fun available() = state() == NanoState.AVAILABLE

    override suspend fun write(prompt: String): String? =
        model()?.generateContent(prompt)?.candidates?.firstOrNull()?.text
}

/** An open model file run by LiteRT-LM on the phone's CPU; loaded on first use and kept. */
private class LiteRtWriter(override val name: String, private val file: File, private val cacheDir: File) : LocalWriter {
    private val lock = Mutex()
    private var engine: Engine? = null

    override suspend fun available() = file.isFile

    override suspend fun write(prompt: String): String? = lock.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = cacheDir.path))
                .also { it.initialize(); engine = it }
            e.createConversation(ConversationConfig()).use { conversation ->
                conversation.sendMessage(Contents.of(prompt)).contents.contents
                    .filterIsInstance<Content.Text>().joinToString("") { it.text }
            }
        }
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}
