package com.gpsradio.core.ai

import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.SourceRef
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * A text model that runs on the phone (spec A §69): Gemini Nano through Android AICore, or an open model
 * (Gemma, Qwen) through LiteRT-LM / llama.cpp. Free, private, works offline, but small: it only rewrites
 * facts it is given and never researches.
 */
interface LocalWriter {
    /** Short name for the settings screen and logs, e.g. "Gemini Nano". */
    val name: String

    /** True when the model is on the phone and ready (it may still be downloading). */
    suspend fun available(): Boolean

    /** One completion for [prompt]; null when the model declined or failed. */
    suspend fun write(prompt: String): String?
}

/**
 * Free narration when OpenAI can't be used (offline, no key, over budget): the on-device model retells the
 * place's own source text as a short radio story in the listener's language, so an English Wikipedia
 * extract can still be told in Russian. Without a local model, or when it fails or is slow, the plain
 * notes of [NarrationFallback] are read instead.
 */
class LocalNarrator(
    private val writer: LocalWriter,
    private val notes: NarrationFallback = NarrationFallback(),
    private val timeoutMs: Long = 45_000,
    private val factChars: Int = 1_500,
) : Narrator {

    override suspend fun narrate(req: NarrationRequest): Segment {
        val story = runCatching { if (writer.available()) retell(req) else null }.getOrNull()
        return story ?: notes.narrate(req)
    }

    private suspend fun retell(req: NarrationRequest): Segment? {
        val place = req.candidate.place
        val facts = NarrationFallback.stripParentheticals(
            place.extract?.takeIf { it.isNotBlank() } ?: place.description ?: return null,
        ).take(factChars)
        val raw = withTimeoutOrNull(timeoutMs) { writer.write(prompt(place.name, facts, req.language)) } ?: return null
        val text = clean(raw) ?: return null
        return Segment(
            text = text,
            entityId = place.id,
            title = place.name,
            sources = listOfNotNull(place.url?.let { SourceRef(place.name, it) }),
            imageUrl = place.imageUrl,
            point = place.point,
        )
    }

    override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply =
        notes.converse(req, onSearching)

    override suspend fun webAnswer(question: String, language: String, area: AreaLabel?): String =
        notes.webAnswer(question, language, area)

    companion object {
        fun languageName(tag: String): String =
            Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

        fun prompt(place: String, facts: String, language: String): String = """
            You are the host of a local radio show. Tell listeners about "$place" in ${languageName(language)}.
            Use ONLY the facts below; do not add names, dates or numbers that are not in them.
            Write 3 to 4 short spoken sentences: start with the most surprising fact, no lists, no headings,
            no greeting, no question at the end. Reply with the story text only. /no_think

            Facts:
            $facts
        """.trimIndent()

        /** Plain spoken text, or null when the model's reply is unusable. */
        fun clean(raw: String): String? {
            val text = raw
                // Reasoning models (Qwen3) may think out loud first.
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .replace("**", "")
                .replace(Regex("(?m)^\\s*(#+|[-*•]|\\d+[.)])\\s+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('"', '«', '»')
                .trim()
            if (text.length < 40) return null
            if (text.length <= 900) return text
            val cut = text.take(900)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
            return if (end > 200) cut.substring(0, end + 1) else null
        }
    }
}
