package com.gpsradio.app.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The phone's own speech recognition (free, works offline when the language pack is installed): the fallback
 * for the mic when OpenAI can't be used (offline, no key, daily limit reached) or when the listener chose
 * "On this phone" in Settings. One utterance per call; use from the main thread only.
 */
class AndroidSpeechAsr(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var pending: ((String?) -> Unit)? = null

    val isListening: Boolean get() = recognizer != null

    /** Some recognizer (on-device or the phone's default) is present. */
    fun isAvailable(): Boolean = runCatching {
        onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(appContext)
    }.getOrDefault(false)

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /**
     * Listens for one utterance in [languageTag] (e.g. "ru-RU"). [onPartial] gets the words so far; [onResult] is
     * called exactly once with the text, or null if nothing was understood, recognition is unavailable or it was
     * cancelled.
     */
    fun listenOnce(languageTag: String, onPartial: (String) -> Unit, onResult: (String?) -> Unit) {
        cancel()
        val onDevice = runCatching { onDeviceAvailable() }.getOrDefault(false)
        val created: SpeechRecognizer? = runCatching {
            when {
                onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                SpeechRecognizer.isRecognitionAvailable(appContext) -> SpeechRecognizer.createSpeechRecognizer(appContext)
                else -> null
            }
        }.getOrNull()
        if (created == null) {
            onResult(null)
            return
        }
        recognizer = created
        pending = onResult
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (recognizer === created) finish(null)
            }

            override fun onResults(results: Bundle?) {
                if (recognizer === created) finish(firstText(results))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer === created) firstText(partialResults)?.let(onPartial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { created.startListening(intent) }.onFailure { finish(null) }
    }

    /** Stops listening without a result; a pending [listenOnce] gets null. */
    fun cancel() {
        if (recognizer == null) return
        runCatching { recognizer?.cancel() }
        finish(null)
    }

    private fun finish(text: String?) {
        val r = recognizer
        val callback = pending
        recognizer = null
        pending = null
        runCatching { r?.destroy() }
        callback?.invoke(text?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun firstText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
