package com.gpsradio.app.platform

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.session.SpeechService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device voice for the keyless preview and the offline/degraded mode (spec A §18.4): Android's
 * own TextToSpeech renders each segment to a WAV file, which [MediaAudioOutput] plays like any clip.
 * Needs no key and no network (for engines with offline voices). Speech recognition is not offered.
 */
class AndroidTtsSpeech(context: Context) : SpeechService {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var engine: TextToSpeech? = null

    override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray = lock.withLock {
        val tts = engine ?: init().also { engine = it }
        val file = withContext(Dispatchers.IO) { File.createTempFile("tts", ".wav", appContext.cacheDir) }
        try {
            withContext(Dispatchers.Main) {
                applyLanguage(tts, language)
                tts.setSpeechRate(if (style == HostStyle.CHILL) 0.95f else 1.0f)
                render(tts, text.take(TextToSpeech.getMaxSpeechInputLength() - 1), file)
            }
            withContext(Dispatchers.IO) { file.readBytes() }.also {
                if (it.size <= WAV_HEADER_BYTES) throw IOException("On-device speech produced no audio")
            }
        } catch (e: IOException) {
            // The engine service may have died; start a fresh one next time.
            engine = null
            withContext(Dispatchers.Main) { runCatching { tts.shutdown() } }
            throw e
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?): String =
        throw IOException("Speech recognition needs an OpenAI key")

    /** Creates the engine and waits for onInit, which Android delivers asynchronously on the main thread. */
    private suspend fun init(): TextToSpeech = withContext(Dispatchers.Main) {
        var created: TextToSpeech? = null
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                // onInit can also fire synchronously (no engine installed), so the result is read after resuming.
                created = TextToSpeech(appContext) { status ->
                    if (cont.isActive) {
                        if (status == TextToSpeech.SUCCESS) cont.resume(Unit)
                        else cont.resumeWithException(IOException("On-device speech is not available on this phone"))
                    }
                }
            }
            created ?: throw IOException("On-device speech is not available on this phone")
        } catch (e: Throwable) {
            runCatching { created?.shutdown() }
            throw e
        }
    }

    /** Uses the exact locale if the engine has it, else the base language, else keeps the engine default. */
    private fun applyLanguage(tts: TextToSpeech, tag: String) {
        val exact = Locale.forLanguageTag(tag)
        val candidates = listOf(exact, Locale(exact.language))
        for (locale in candidates) {
            if (locale.language.isNullOrEmpty()) continue
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(locale)
                return
            }
        }
    }

    /** synthesizeToFile completes through the UtteranceProgressListener (called on a binder thread). */
    private suspend fun render(tts: TextToSpeech, text: String, file: File): Unit = suspendCancellableCoroutine<Unit> { cont ->
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resumeWithException(IOException("On-device speech failed"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id && cont.isActive) cont.resumeWithException(IOException("On-device speech failed ($errorCode)"))
            }
        })
        cont.invokeOnCancellation { runCatching { tts.stop() } }
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        if (tts.synthesizeToFile(text, params, file, id) != TextToSpeech.SUCCESS && cont.isActive) {
            cont.resumeWithException(IOException("On-device speech could not start"))
        }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
