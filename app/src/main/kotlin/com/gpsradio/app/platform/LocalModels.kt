package com.gpsradio.app.platform

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import java.io.File

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

/** The phone's chip as Android reports it (Android 12+), with the Snapdragon name for common Qualcomm codes. */
fun chipName(): String? {
    if (android.os.Build.VERSION.SDK_INT < 31) return null
    val model = android.os.Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != android.os.Build.UNKNOWN } ?: return null
    val maker = android.os.Build.SOC_MANUFACTURER?.takeIf { it.isNotBlank() && it != android.os.Build.UNKNOWN }
    val snapdragon = SNAPDRAGON[model.uppercase()]
    return listOfNotNull(maker, model).joinToString(" ") + (snapdragon?.let { " ($it)" } ?: "")
}

private val SNAPDRAGON = mapOf(
    "SM8850" to "Snapdragon 8 Elite Gen 5",
    "SM8750" to "Snapdragon 8 Elite",
    "SM8735" to "Snapdragon 8s Gen 4",
    "SM8650" to "Snapdragon 8 Gen 3",
    "SM8635" to "Snapdragon 8s Gen 3",
    "SM8550" to "Snapdragon 8 Gen 2",
    "SM8475" to "Snapdragon 8+ Gen 1",
    "SM8450" to "Snapdragon 8 Gen 1",
    "SM8350" to "Snapdragon 888",
    "SM7750" to "Snapdragon 7 Gen 4",
    "SM7675" to "Snapdragon 7+ Gen 3",
    "SM7635" to "Snapdragon 7s Gen 3",
    "SM7550" to "Snapdragon 7 Gen 3",
    "SM7475" to "Snapdragon 7+ Gen 2",
    "SM7435" to "Snapdragon 7s Gen 2",
    "SM6450" to "Snapdragon 6 Gen 1",
    "SM6375" to "Snapdragon 695",
    "SM4450" to "Snapdragon 4 Gen 2",
    // MediaTek (Xiaomi "T" models, Redmi, POCO).
    "MT6993" to "Dimensity 9500",
    "MT6991" to "Dimensity 9400",
    "MT6989" to "Dimensity 9300",
    "MT6985" to "Dimensity 9200",
    "MT6899" to "Dimensity 8400",
    "MT6897" to "Dimensity 8300",
    "MT6896" to "Dimensity 8200",
    "MT6895" to "Dimensity 8100",
    "MT6886" to "Dimensity 7200",
    "MT6878" to "Dimensity 7300",
    "MT6877" to "Dimensity 1080 / 7050",
    "MT6835" to "Dimensity 6300",
    "MT6833" to "Dimensity 700 / 6020",
)

/** Flagship chips (Snapdragon 8, Dimensity 9000/8000) can carry the bigger model at a listenable speed. */
fun isFlagshipChip(soc: String?): Boolean {
    val code = soc?.uppercase() ?: return false
    return Regex("SM8\\d{3}").containsMatchIn(code) || Regex("MT69(8|9)\\d").containsMatchIn(code) ||
        code.contains("MT6897") || code.contains("MT6899") || code.contains("TENSOR")
}

/** The biggest model that runs comfortably: the model takes about its file size in memory besides Android and apps. */
fun recommendedFor(ramGb: Double, soc: String? = chipName()): LocalModelSpec = when {
    // The 4B model needs both memory and a fast chip: on a mid-range chip (e.g. Dimensity 7300) it's too slow to talk.
    ramGb >= 11 && isFlagshipChip(soc) -> LocalModelCatalog.byId("gemma4-e4b")!!
    ramGb >= 6 -> LocalModelCatalog.byId("gemma4-e2b")!!
    else -> LocalModelCatalog.byId("qwen3-1.7b")!!
}

/** Gemini Nano's state on this phone (Android AICore). */
enum class NanoState { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** What this phone offers for on-device AI, shown in Settings → Free & offline (spec A §70). */
data class DeviceInfo(
    val android: String,
    val ramGb: Double,
    val freeGb: Double,
    val aiCore: Boolean,
    val recommended: LocalModelSpec,
    /** The chip, e.g. "Qualcomm SM8750 (Snapdragon 8 Elite)"; null before Android 12. */
    val chip: String? = null,
)

/** Why a queued download isn't moving. */
enum class DownloadWait { WIFI, NETWORK, RETRY }

/**
 * A model download: [fraction] null while the size is unknown; [waiting] while Android holds it (no Wi-Fi,
 * no network, retrying); [error] set when it failed.
 */
data class ModelDownload(val fraction: Float? = null, val error: String? = null, val waiting: DownloadWait? = null)

/**
 * The on-device story writers (spec A §69): Gemini Nano where the phone has it (Pixel, Galaxy, recent Xiaomi
 * flagships), or an open model downloaded once. The choice is a setting: "auto" (Nano if ready, else a
 * downloaded model), "off", "nano" or a model id.
 *
 * Open models are fetched by Android's DownloadManager (spec A §71): it keeps going while the app is closed
 * or the phone sleeps, resumes by itself after a dropped connection or a restart, can wait for Wi-Fi, and
 * shows its progress in the notification shade.
 */
class LocalModels(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") http: OkHttpClient,
    private val scope: CoroutineScope,
    private val wifiOnly: () -> Boolean = { true },
) {
    /**
     * Downloaded once and kept: the app's own external storage (Android/data/<app>/files/models) survives app
     * updates (the self-update installs over the same app), stays out of cloud backup, and is where
     * DownloadManager can write. Only uninstalling, "clear data" or Delete in Settings removes a model.
     */
    private val dir = (context.getExternalFilesDir(MODELS) ?: File(context.noBackupFilesDir, MODELS)).apply { mkdirs() }
    /** Where 0.5.91–0.5.92 kept models: still used if a model is there. */
    private val legacyDir = File(context.noBackupFilesDir, MODELS)
    /** LiteRT-LM's prepared-model cache, kept next to the models so it isn't rebuilt after an update. */
    private val engineCache = File(dir, "cache").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("model_downloads", Context.MODE_PRIVATE)
    private val downloadManager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()
    private val _downloads = MutableStateFlow<Map<String, ModelDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownload>> = _downloads.asStateFlow()
    private val _nano = MutableStateFlow(NanoState.UNKNOWN)
    val nano: StateFlow<NanoState> = _nano.asStateFlow()
    private var poller: Job? = null

    private val nanoWriter = NanoWriter()
    private val liteRt = HashMap<String, LiteRtWriter>()

    init {
        // Unfinished downloads from the old in-app downloader can't be continued by Android: drop them.
        LocalModelCatalog.all.forEach { File(legacyDir, it.fileName + ".part").delete() }
        // Android kept downloading while the app was closed or being updated: pick up where it is.
        reconcile()
        // Know early whether Gemini Nano is there, so "ready" is right before the first story.
        scope.launch { runCatching { refreshNano() } }
    }

    private fun scanInstalled() = LocalModelCatalog.all.filter { fileOf(it).isFile }.map { it.id }.toSet()

    /** The model's file: in the models folder, or where an earlier version saved it. */
    fun fileOf(spec: LocalModelSpec): File =
        File(legacyDir, spec.fileName).takeIf { it.isFile } ?: File(dir, spec.fileName)

    /** Android version, memory, free space, whether AICore (Gemini Nano) supports this phone, and the model that fits. */
    fun deviceInfo(): DeviceInfo {
        val mem = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(mem)
        val ramGb = mem.totalMem / 1e9
        val aiCore = runCatching { com.google.mlkit.genai.common.internal.GenAiUtils.isAiCoreCompatible(context) }.getOrDefault(false)
        return DeviceInfo(
            android = android.os.Build.VERSION.RELEASE ?: "?",
            ramGb = ramGb,
            freeGb = dir.usableSpace / 1e9,
            aiCore = aiCore,
            recommended = recommendedFor(ramGb),
            chip = chipName(),
        )
    }

    /** Whether [choice] has a model ready right now (no waiting): Nano available or a model downloaded. */
    fun readyNow(choice: String): Boolean = when (choice) {
        "off" -> false
        NANO -> _nano.value == NanoState.AVAILABLE
        "auto" -> _nano.value == NanoState.AVAILABLE || _installed.value.isNotEmpty()
        else -> choice in _installed.value
    }

    suspend fun refreshNano(): NanoState {
        val state = nanoWriter.state()
        if (_nano.value != NanoState.DOWNLOADING || state == NanoState.AVAILABLE) _nano.value = state
        return _nano.value
    }

    /**
     * The models to try for [choice], best first (spec A §73): the chosen one, then (as fallbacks) Gemini Nano
     * if ready, then the downloaded open models, the one that fits this phone first. "off" means none.
     */
    private suspend fun candidates(choice: String): List<LocalWriter> {
        if (choice == "off") return emptyList()
        val nanoReady = refreshNano() == NanoState.AVAILABLE
        val installed = LocalModelCatalog.all.filter { it.id in _installed.value }
        val fit = runCatching { recommendedFor(deviceInfo().ramGb) }.getOrNull()
        val open = installed.sortedBy { if (it == fit) 0 else 1 }.map { liteRtFor(it) }
        val chosen: List<LocalWriter> = when (choice) {
            NANO -> listOfNotNull(nanoWriter.takeIf { nanoReady })
            "auto" -> emptyList()
            else -> LocalModelCatalog.byId(choice)?.takeIf { it.id in _installed.value }?.let { listOf(liteRtFor(it)) }.orEmpty()
        }
        return (chosen + listOfNotNull(nanoWriter.takeIf { nanoReady }) + open).distinct()
    }

    /**
     * The writer for [choice]: each call goes down [candidates] until one model loads and gives an acceptable
     * reply; a model that fails to load is skipped for the rest of the session.
     */
    fun writer(choice: () -> String): LocalWriter = object : LocalWriter {
        override val name: String get() = lastUsed?.name ?: "none"
        @Volatile private var lastUsed: LocalWriter? = null

        override suspend fun available(): Boolean = candidates(choice()).any { it !in broken && it.available() }

        override suspend fun prepare(): Boolean {
            for (w in candidates(choice())) {
                if (w in broken || !w.available()) continue
                if (runCatching { w.prepare() }.getOrDefault(false)) { lastUsed = w; return true }
                broken += w
            }
            return false
        }

        override suspend fun write(prompt: String): String? = writeChecked(prompt) { true }

        /** Gemini Nano is always ready; an open model once its engine is loaded. */
        override val isLoaded: Boolean
            get() = _nano.value == NanoState.AVAILABLE || synchronized(liteRt) { liteRt.values.any { it.loaded } }

        override fun warmUp() = warmUp(choice())

        override suspend fun writeChecked(prompt: String, accept: (String) -> Boolean): String? {
            for (w in candidates(choice())) {
                if (w in broken || !w.available()) continue
                if (!runCatching { w.prepare() }.getOrDefault(false)) { broken += w; continue }
                val reply = runCatching { w.write(prompt) }.onFailure { Log.w(TAG, "${w.name} failed", it) }.getOrNull()
                if (reply != null && accept(reply)) { lastUsed = w; return reply }
            }
            return null
        }
    }

    /** Models that failed to load this session (e.g. not enough memory): not tried again until restart. */
    private val broken: MutableSet<LocalWriter> = java.util.Collections.synchronizedSet(HashSet())

    /** What "Test model" reports: which model and engine answered, how long loading and writing took. */
    data class TestResult(
        val model: String?,
        val backend: String?,
        val loadMs: Long,
        val writeMs: Long,
        val text: String?,
        val inLanguage: Boolean,
        val error: String?,
    )

    /**
     * Settings → "Test model": loads the model for [choice] and has it retell a few facts about the Eiffel Tower
     * in [language], the way stories are told on air.
     */
    suspend fun test(choice: String, language: String): TestResult {
        val facts = "The Eiffel Tower is a wrought-iron lattice tower in Paris, built by Gustave Eiffel's company for " +
            "the 1889 World's Fair. It is 330 metres tall and was the tallest structure in the world until 1930. " +
            "Critics first called it an eyesore; today it is the most visited paid monument in the world."
        val prompt = com.gpsradio.core.ai.LocalNarrator.prompt("Eiffel Tower", facts, language)
        for (w in candidates(choice)) {
            if (!w.available()) continue
            val t0 = System.currentTimeMillis()
            val loaded = runCatching { w.prepare() }
            val loadMs = System.currentTimeMillis() - t0
            if (loaded.getOrDefault(false) != true) {
                return TestResult(w.name, backendOf(w), loadMs, 0, null, false, loaded.exceptionOrNull()?.message ?: "The model didn't load")
            }
            broken -= w
            val t1 = System.currentTimeMillis()
            val reply = runCatching { w.write(prompt) }
            val writeMs = System.currentTimeMillis() - t1
            val text = reply.getOrNull()?.let { com.gpsradio.core.ai.LocalNarrator.clean(it, minChars = 1) }
            return TestResult(
                w.name, backendOf(w), loadMs, writeMs, text,
                text != null && com.gpsradio.core.ai.LocalNarrator.fitsLanguage(text, language),
                reply.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: if (text == null) "Empty reply" else null,
            )
        }
        return TestResult(null, null, 0, 0, null, false, "No model is ready: download one first")
    }

    private fun backendOf(w: LocalWriter): String? = (w as? LiteRtWriter)?.backend ?: if (w === nanoWriter) "AICore" else null

    /** Loads the model in the background so the first free story doesn't wait for it. */
    fun warmUp(choice: String) {
        scope.launch { runCatching { candidates(choice).firstOrNull { it.available() }?.prepare() } }
    }

    private fun liteRtFor(spec: LocalModelSpec) = synchronized(liteRt) {
        liteRt.getOrPut(spec.id) { LiteRtWriter(spec.label, fileOf(spec), engineCache, gpuGuard) }
    }

    /**
     * Remembers a GPU load that never finished (the app died in the driver): after that, this phone uses the CPU.
     */
    private val gpuGuard = object : GpuGuard {
        private val p = context.getSharedPreferences("litert_gpu", Context.MODE_PRIVATE)
        override fun gpuAllowed() = !p.getBoolean("broken", false) && !p.getBoolean("pending", false)
        override fun starting() { p.edit().putBoolean("pending", true).commit() }
        override fun finished(ok: Boolean) { p.edit().putBoolean("pending", false).putBoolean("broken", !ok).commit() }
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
        if (id in _installed.value || prefs.contains(id)) { reconcile(); return }
        val needed = spec.sizeMb * 1_000_000L + 200_000_000L
        if (dir.usableSpace < needed) {
            _downloads.update { it + (id to ModelDownload(error = "Not enough free space: ${needed / 1_000_000} MB needed")) }
            return
        }
        val dm = downloadManager ?: run {
            _downloads.update { it + (id to ModelDownload(error = "No download service on this phone")) }
            return
        }
        tempOf(spec).delete()
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("GPS Radio: ${spec.label}")
            .setDescription(context.getString(com.gpsradio.app.R.string.local_model_notification))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, MODELS, tempOf(spec).name)
            .setAllowedOverMetered(!wifiOnly())
            .setAllowedOverRoaming(false)
        val downloadId = runCatching { dm.enqueue(request) }.getOrElse { e ->
            _downloads.update { it + (id to ModelDownload(error = e.message ?: e.javaClass.simpleName)) }
            return
        }
        prefs.edit().putLong(id, downloadId).apply()
        _downloads.update { it + (id to ModelDownload()) }
        watch()
    }

    fun cancel(id: String) {
        val downloadId = prefs.getLong(id, -1)
        if (downloadId >= 0) runCatching { downloadManager?.remove(downloadId) }
        prefs.edit().remove(id).apply()
        LocalModelCatalog.byId(id)?.let { tempOf(it).delete() }
        _downloads.update { it - id }
    }

    fun delete(id: String) {
        val spec = LocalModelCatalog.byId(id) ?: return
        cancel(id)
        synchronized(liteRt) { liteRt.remove(id)?.close() }
        fileOf(spec).delete()
        File(dir, spec.fileName).delete()
        _installed.value = scanInstalled()
    }

    private fun tempOf(spec: LocalModelSpec) = File(dir, spec.fileName + ".download")

    /**
     * Brings the state up to date with Android's download service: progress, "waiting for Wi-Fi", failures,
     * and finished downloads (moved into place). Safe to call any time; the download receiver calls it too.
     */
    @Synchronized
    fun reconcile() {
        val dm = downloadManager
        val next = HashMap<String, ModelDownload>()
        for (spec in LocalModelCatalog.all) {
            val downloadId = prefs.getLong(spec.id, -1)
            if (downloadId < 0) {
                // Keep a failure on screen until the listener retries.
                _downloads.value[spec.id]?.takeIf { it.error != null }?.let { next[spec.id] = it }
                continue
            }
            val status = dm?.let { query(it, downloadId) }
            if (status == null) {
                prefs.edit().remove(spec.id).apply() // Android forgot it (cleared): start over
                continue
            }
            when (status.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val temp = tempOf(spec)
                    if (temp.isFile && (temp.renameTo(File(dir, spec.fileName)) || File(dir, spec.fileName).isFile)) {
                        prefs.edit().remove(spec.id).apply()
                    } else {
                        prefs.edit().remove(spec.id).apply()
                        next[spec.id] = ModelDownload(error = "The downloaded file is missing")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    runCatching { dm?.remove(downloadId) }
                    prefs.edit().remove(spec.id).apply()
                    next[spec.id] = ModelDownload(error = failureText(status.reason))
                }
                else -> next[spec.id] = ModelDownload(
                    fraction = status.total.takeIf { it > 0 }?.let { (status.done.toFloat() / it).coerceIn(0f, 1f) },
                    waiting = if (status.status == DownloadManager.STATUS_PAUSED) when (status.reason) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> DownloadWait.WIFI
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> if (wifiOnly()) DownloadWait.WIFI else DownloadWait.NETWORK
                        else -> DownloadWait.RETRY
                    } else null,
                )
            }
        }
        _downloads.value = next
        _installed.value = scanInstalled()
        if (next.values.any { it.error == null }) watch()
    }

    private data class Status(val status: Int, val reason: Int, val done: Long, val total: Long)

    private fun query(dm: DownloadManager, downloadId: Long): Status? = runCatching {
        dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
            if (!c.moveToFirst()) return@use null
            Status(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }.getOrNull()

    private fun failureText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space"
        DownloadManager.ERROR_CANNOT_RESUME -> "The download couldn't be resumed; try again"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "The server refused the download ($reason)"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage not available"
        else -> "Download failed ($reason)"
    }

    /** Progress for the screen while a download runs; Android does the downloading itself. */
    private fun watch() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(2_000)
                reconcile()
                if (_downloads.value.values.none { it.error == null }) break
            }
        }
    }

    companion object {
        const val NANO = "nano"
        private const val MODELS = "models"
        private const val TAG = "LocalModels"
    }
}

/** Android finished (or failed) a model download, possibly while the app was closed: move it into place. */
class ModelDownloadReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        (context.applicationContext as? com.gpsradio.app.GpsRadioApp)?.localModels?.reconcile()
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

interface GpuGuard {
    fun gpuAllowed(): Boolean
    fun starting()
    fun finished(ok: Boolean)
}

/**
 * An open model file run by LiteRT-LM: on the GPU when the phone allows (much faster on Snapdragon's Adreno),
 * else on the CPU. Loaded once and kept.
 */
private class LiteRtWriter(
    override val name: String,
    private val file: File,
    private val cacheDir: File,
    private val gpu: GpuGuard,
) : LocalWriter {
    private val lock = Mutex()
    private var engine: Engine? = null
    /** "GPU" or "CPU" once loaded. */
    @Volatile var backend: String? = null

    val loaded: Boolean get() = backend != null

    /**
     * Loading and writing run here, apart from the caller: the native calls can't be interrupted, so a caller's
     * time limit (the radio waiting for a story) could otherwise never fire and the next story would never play.
     * The caller stops waiting on time; work still queued for the model is dropped.
     */
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun <T> onWorker(block: () -> T): T {
        val job = worker.async { lock.withLock { block() } }
        try {
            return job.await()
        } catch (e: CancellationException) {
            job.cancel() // still waiting for the model: never run it
            throw e
        }
    }

    override suspend fun available() = file.isFile

    override suspend fun prepare(): Boolean = onWorker { load() != null }

    private fun load(): Engine? {
        engine?.let { return it }
        if (!file.isFile) return null
        if (gpu.gpuAllowed()) {
            gpu.starting()
            val onGpu = runCatching {
                Engine(EngineConfig(modelPath = file.path, backend = Backend.GPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path)).also { it.initialize() }
            }
            // "pending" stays set until the first answer on the GPU: some drivers crash only then (LiteRT-LM #1860).
            if (onGpu.isFailure) gpu.finished(false)
            onGpu.onFailure { Log.w("LiteRtWriter", "GPU load failed, using the CPU", it) }
            onGpu.getOrNull()?.let { engine = it; backend = "GPU"; gpuProven = false; return it }
        }
        return Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path))
            .also { it.initialize(); engine = it; backend = "CPU" }
    }

    /** The GPU has answered once without killing the app. */
    private var gpuProven = true

    override suspend fun write(prompt: String): String? = onWorker {
        val e = load() ?: return@onWorker null
        val reply = runCatching { generate(e, prompt) }
        if (backend == "GPU" && !gpuProven) {
            gpu.finished(reply.isSuccess)
            gpuProven = reply.isSuccess
            if (reply.isFailure) {
                // The GPU loaded but can't answer: the CPU from now on.
                Log.w("LiteRtWriter", "GPU answer failed, using the CPU", reply.exceptionOrNull())
                close()
                return@onWorker load()?.let { generate(it, prompt) }
            }
        }
        reply.getOrThrow()
    }

    private fun generate(e: Engine, prompt: String): String =
        e.createConversation(ConversationConfig()).use { conversation ->
            conversation.sendMessage(Contents.of(prompt)).contents.contents
                .filterIsInstance<Content.Text>().joinToString("") { it.text }
        }

    fun close() {
        runCatching { engine?.close() }
        engine = null
        backend = null
    }

    companion object {
        /** Prompt (facts up to ~1,000 characters) plus a short story: a small context loads and answers faster. */
        const val MAX_TOKENS = 2048
    }
}
