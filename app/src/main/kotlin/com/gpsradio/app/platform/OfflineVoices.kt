package com.gpsradio.app.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** One TextToSpeech engine installed on the phone (e.g. Google, Samsung, Xiaomi, RHVoice). */
data class TtsEngineOption(val packageName: String, val label: String)

/** What Settings shows about the phone's own voices. */
data class OfflineVoiceInfo(
    val engines: List<TtsEngineOption> = emptyList(),
    /** An offline voice for the session language is installed; null = not known (yet). */
    val voiceInstalled: Boolean? = null,
)

object OfflineVoices {
    /**
     * The best voice for [tag] that works without the network and is fully installed: the exact country first,
     * then the highest quality, then the lowest latency. Null if the engine lists none.
     */
    fun bestOfflineVoice(tts: TextToSpeech, tag: String): Voice? {
        val wanted = Locale.forLanguageTag(tag)
        if (wanted.language.isNullOrEmpty()) return null
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull() ?: return null
        return voices
            .filter { v ->
                v.locale?.language == wanted.language &&
                    !v.isNetworkConnectionRequired &&
                    v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            .sortedWith(
                compareByDescending<Voice> { wanted.country.isNotEmpty() && it.locale?.country == wanted.country }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency },
            )
            .firstOrNull()
    }

    /**
     * Looks up the installed engines and whether [engine] (null = system default) has an offline voice for
     * [languageTag]. Main thread only; [onResult] is called once on the main thread. Returns a function that
     * cancels the lookup and releases the engine.
     */
    fun query(context: Context, engine: String?, languageTag: String, onResult: (OfflineVoiceInfo) -> Unit): () -> Unit {
        val main = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var done = false
        fun release() {
            done = true
            runCatching { tts?.shutdown() }
            tts = null
        }
        val listener = TextToSpeech.OnInitListener { status ->
            // onInit can fire synchronously inside the constructor: read the engine on the next main-loop turn.
            main.post {
                if (done) return@post
                val t = tts
                val info = if (t == null || status != TextToSpeech.SUCCESS) {
                    OfflineVoiceInfo(engines = t?.let { engines(it) }.orEmpty(), voiceInstalled = false)
                } else {
                    OfflineVoiceInfo(engines = engines(t), voiceInstalled = hasOfflineVoice(t, languageTag))
                }
                release()
                onResult(info)
            }
        }
        tts = runCatching { TextToSpeech(context.applicationContext, listener, engine?.takeIf { it.isNotBlank() }) }.getOrNull()
        if (tts == null) {
            done = true
            main.post { onResult(OfflineVoiceInfo(voiceInstalled = false)) }
        }
        return { release() }
    }

    private fun engines(tts: TextToSpeech): List<TtsEngineOption> = runCatching {
        tts.engines.orEmpty().map { TtsEngineOption(it.name, it.label?.takeIf { l -> l.isNotBlank() } ?: it.name) }
    }.getOrDefault(emptyList())

    private fun hasOfflineVoice(tts: TextToSpeech, tag: String): Boolean {
        if (bestOfflineVoice(tts, tag) != null) return true
        // Engines that list no voices at all: fall back to the older language check.
        val listed = runCatching { tts.voices }.getOrNull()
        if (!listed.isNullOrEmpty()) return false
        val locale = Locale.forLanguageTag(tag)
        return runCatching { tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
    }
}
