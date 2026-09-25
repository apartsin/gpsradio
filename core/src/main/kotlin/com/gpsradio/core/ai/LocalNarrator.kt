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

    /**
     * Loads the model (the first load of a 2–4 GB model takes a while); true when it's ready to write. Kept apart
     * from [write] so a slow first load isn't mistaken for a slow answer.
     */
    suspend fun prepare(): Boolean = available()

    /** One completion for [prompt]; null when the model declined or failed. */
    suspend fun write(prompt: String): String?

    /**
     * A completion that passes [accept] (e.g. it's in the listener's language). A writer with several models
     * tries the next one when a reply is refused; a single model just checks its own.
     */
    suspend fun writeChecked(prompt: String, accept: (String) -> Boolean): String? = write(prompt)?.takeIf(accept)
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
    /** The first load of the model: slower than any answer. */
    private val prepareMs: Long = 150_000,
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
        if (withTimeoutOrNull(prepareMs) { writer.prepare() } != true) return null
        val ok = { raw: String -> clean(raw)?.let { fitsLanguage(it, req.language) } == true }
        val raw = withTimeoutOrNull(timeoutMs) { writer.writeChecked(prompt(place.name, facts, req.language), ok) } ?: return null
        val text = clean(raw)?.takeIf { fitsLanguage(it, req.language) } ?: return null
        return Segment(
            text = text,
            entityId = place.id,
            title = place.name,
            sources = listOfNotNull(place.url?.let { SourceRef(place.name, it) }),
            imageUrl = place.imageUrl,
            point = place.point,
        )
    }

    /** A spoken answer from the on-device model, grounded in the place on air and what's nearby. */
    override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
        if (!writer.available()) return notes.converse(req, onSearching)
        if (withTimeoutOrNull(prepareMs) { writer.prepare() } != true) throw IllegalStateException("The on-device model isn't ready")
        val ok = { raw: String -> clean(raw, minChars = 2)?.let { fitsLanguage(it, req.language) } == true }
        val raw = withTimeoutOrNull(timeoutMs) { writer.writeChecked(answerPrompt(req, factChars), ok) }
            ?: throw IllegalStateException("The on-device model didn't answer")
        val text = clean(raw, minChars = 2)?.takeIf { fitsLanguage(it, req.language) }
            ?: throw IllegalStateException("The on-device model gave no answer in the listener's language")
        return ConversationReply(reply = text, entityId = req.active?.place?.id)
    }

    override suspend fun canConverse(): Boolean = runCatching { writer.available() }.getOrDefault(false)

    override suspend fun webAnswer(question: String, language: String, area: AreaLabel?): String =
        notes.webAnswer(question, language, area)

    companion object {
        /**
         * A reply in the wrong script is useless on air (a small model may answer a Russian prompt in English):
         * Cyrillic, Hebrew and Greek listeners need mostly their script; Latin-script languages need mostly Latin.
         */
        fun fitsLanguage(text: String, language: String): Boolean {
            val letters = text.filter { it.isLetter() }
            if (letters.length < 2) return true
            fun share(test: (Char) -> Boolean) = letters.count(test).toDouble() / letters.length
            return when (language.substringBefore('-').lowercase()) {
                "ru", "uk", "be", "bg", "sr", "mk", "kk" -> share { it in '\u0400'..'\u04FF' } >= 0.6
                "he", "yi" -> share { it in '\u0590'..'\u05FF' } >= 0.6
                "el" -> share { it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF' } >= 0.6
                "ar", "fa", "ja", "zh", "ko", "hi", "th" -> true
                else -> share { it.code < 0x250 } >= 0.8
            }
        }

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

        fun answerPrompt(req: ConversationRequest, factChars: Int = 1_500): String = buildString {
            appendLine("You are the host of a local radio show. A listener asks you something by voice.")
            appendLine("Answer in ${languageName(req.language)}, in 1 to 3 short spoken sentences, no lists.")
            appendLine("Use the facts below when they help. If you don't know, say so briefly; never invent names, dates or numbers.")
            req.area?.let { a -> listOfNotNull(a.city, a.region, a.countryCode).takeIf { it.isNotEmpty() } }
                ?.let { appendLine("Where the listener is: ${it.joinToString(", ")}") }
            req.active?.place?.let { p ->
                appendLine()
                appendLine("On air now: ${p.name}")
                (p.extract ?: p.description)?.let { appendLine(NarrationFallback.stripParentheticals(it).take(factChars)) }
            }
            if (req.nearby.isNotEmpty()) {
                appendLine()
                appendLine("Nearby: " + req.nearby.take(8).joinToString("; ") { c ->
                    c.place.name + (c.place.description?.let { " ($it)" } ?: "")
                })
            }
            val recent = req.history.takeLast(4)
            if (recent.isNotEmpty()) {
                appendLine()
                recent.forEach { appendLine((if (it.fromUser) "Listener: " else "Host: ") + it.text) }
            }
            appendLine()
            appendLine("Listener: ${req.utterance}")
            append("Host (reply with the answer only): /no_think")
        }

        /** Plain spoken text, or null when the model's reply is unusable. */
        fun clean(raw: String, minChars: Int = 40): String? {
            val text = raw
                // Reasoning models (Qwen3) may think out loud first.
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .replace("**", "")
                .replace(Regex("(?m)^\\s*(#+|[-*•]|\\d+[.)])\\s+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('"', '«', '»')
                .trim()
            if (text.length < minChars) return null
            if (text.length <= 900) return text
            val cut = text.take(900)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
            return if (end > 200) cut.substring(0, end + 1) else null
        }
    }
}
