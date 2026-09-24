package com.gpsradio.core.session

import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.editorial.Pacing
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
    /** Use the natural, hands-free Realtime voice for conversations. */
    val liveVoice: Boolean = false,
    /**
     * Always listening (like ChatGPT voice mode): with [liveVoice], the mic stays open and the listener
     * can talk at any time, even over a story. The listener can switch it off anytime (spec A §33).
     */
    val handsFree: Boolean = false,
    val voice: String = "alloy",
    /** Voice of the live (Realtime) host; marin and cedar are the most natural gpt-realtime voices. */
    val realtimeVoice: String = "marin",
    val liveModel: String = "gpt-realtime",
    val transcriptionModel: String = "gpt-4o-mini-transcribe",
    /** Ask drivers once per session where they're heading. */
    val askAboutTrip: Boolean = true,
    /**
     * Keyless preview: stories are read from source facts with the on-device voice and OpenAI is
     * never called; questions are unavailable. Needs the session's fallback narrator and speech.
     */
    val previewMode: Boolean = false,
    /** Short stings before stories and answers, and a blip when listening starts. */
    val soundEffects: Boolean = true,
    /** How often the radio speaks: scales the speak threshold, segment gaps and fillers. */
    val pacing: Pacing = Pacing.BALANCED,
    /** Look up public events today nearby (web search) and mention them (spec A §30). */
    val localEvents: Boolean = false,
    /** Once per session, after a few stories, ask what the listener would like more of (never a knowledge quiz). */
    val askPreferences: Boolean = false,
    /**
     * The listener can answer out loud (the mic is open). When false the radio asks nothing it would need an
     * answer to: no "want the full story?" teasers, detour offers or trip/preference questions (spec A §36).
     */
    val canReply: Boolean = true,
    /**
     * Today's estimated OpenAI spend reached the listener's daily cap (spec A §41): until tomorrow the radio uses
     * the free on-device notes and voice, no research and no live voice.
     */
    val budgetReached: Boolean = false,
    /** True when running on the app's built-in key rather than the listener's own (changes the out-of-credit advice). */
    val usingBuiltInKey: Boolean = false,
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
            instructions = style.voiceDirection + " " + TALKING_NOT_READING + " Language: ${Languages.displayName(language)}.",
        )
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?): String =
        openAi.transcribe(audio, fileName, mimeType, models().transcriptionModel, prompt)
}

/** Delivery for every TTS clip (spec A §34): a host talking, never someone reading a text aloud. */
const val TALKING_NOT_READING =
    "Deliver it as spontaneous, casual talk, as if telling a friend off the top of your head: conversational " +
        "intonation, natural emphasis and little pauses. Never sound like reading a text or an announcement."
