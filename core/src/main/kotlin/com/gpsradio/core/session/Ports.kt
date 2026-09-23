package com.gpsradio.core.session

import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.Topic

/** Plays one audio clip; returns when playback ends. Cancelling the coroutine must stop playback. */
fun interface AudioOutput {
    suspend fun play(audio: ByteArray)
}

interface SpeechService {
    suspend fun synthesize(text: String, language: String, style: HostStyle = HostStyle.ENTERTAINING): ByteArray
    /** [prompt] can carry nearby place names to help with proper nouns. */
    suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String? = null): String
}

/** Persists the serialized heard-story history between sessions. */
interface HistoryStore {
    fun load(): String?
    fun save(serialized: String)
}

/** Reverse-geocodes to a coarse area name (city/region/country) for localized web search. */
fun interface AreaLabeler {
    suspend fun label(point: GeoPoint): AreaLabel?
}

/** User settings the session reads each time it needs them, so changes apply immediately. */
data class SessionConfig(
    /** Resolved default narration language (BCP-47). */
    val language: String,
    val interests: Set<Topic>,
    val style: HostStyle = HostStyle.ENTERTAINING,
)

class OpenAiSpeech(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
) : SpeechService {
    /** Small in-memory cache so replays and repeated lines cost nothing. */
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 12
    }

    override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray {
        val m = models()
        val key = "${m.ttsModel}|${m.ttsVoice}|$language|${style.key}|$text"
        synchronized(cache) { cache[key] }?.let { return it }
        val bytes = openAi.speech(
            text = text,
            model = m.ttsModel,
            voice = m.ttsVoice,
            instructions = style.voiceDirection + " Language: ${Languages.displayName(language)}.",
        )
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?): String =
        openAi.transcribe(audio, fileName, mimeType, models().transcriptionModel, prompt)
}
