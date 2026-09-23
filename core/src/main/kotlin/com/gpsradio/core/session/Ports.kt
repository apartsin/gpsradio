package com.gpsradio.core.session

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
    suspend fun synthesize(text: String, language: String): ByteArray
    suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String): String
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
)

class OpenAiSpeech(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
) : SpeechService {
    override suspend fun synthesize(text: String, language: String): ByteArray {
        val m = models()
        return openAi.speech(
            text = text,
            model = m.ttsModel,
            voice = m.ttsVoice,
            instructions = "Warm, engaging radio host telling a story to one listener. " +
                "Natural pace. Language: ${Languages.displayName(language)}.",
        )
    }

    override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String): String =
        openAi.transcribe(audio, fileName, mimeType, models().transcriptionModel)
}
