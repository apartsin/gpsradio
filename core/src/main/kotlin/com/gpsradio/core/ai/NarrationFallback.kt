package com.gpsradio.core.ai

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.SourceRef
import com.gpsradio.core.model.TravelMode

/**
 * Keyless / degraded narration (spec A §18.4, spec B §17): "raw notes" built on the device from the
 * place's own source text, with no model call. It reads the first few sentences of the Wikipedia
 * extract behind a short spoken lead-in, so it never adds a claim the source does not make.
 * Conversation needs a model and is not available here.
 */
class NarrationFallback(
    private val maxSentences: Int = 3,
    private val maxChars: Int = 480,
) : Narrator {

    override suspend fun narrate(req: NarrationRequest): Segment {
        val c = req.candidate
        val textLang = textLanguage(c.place, req.language)
        val body = notes(c.place, maxSentences, maxChars)
        val lead = leadIn(c, req.location.travelMode, req.location.headingDeg, textLang)
        val text = if (body.isBlank()) lead.trimEnd(':', ' ') + "." else "$lead $body"
        val sources = listOfNotNull(c.place.url?.let { SourceRef(c.place.name, it) })
        return Segment(
            text = text,
            entityId = c.place.id,
            title = c.place.name,
            sources = sources,
            imageUrl = c.place.imageUrl,
            point = c.place.point,
            language = textLang.takeIf { base(it) != base(req.language) },
        )
    }

    override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply =
        throw UnsupportedOperationException("Questions need an OpenAI key")

    override suspend fun webAnswer(question: String, language: String, area: AreaLabel?): String =
        throw UnsupportedOperationException("Web search needs an OpenAI key")

    companion object {
        private val ABBREVIATIONS = setOf(
            "st", "dr", "mr", "mrs", "ms", "prof", "c", "ca", "approx", "no", "nr", "vs", "etc", "e.g", "i.e",
            "jr", "sr", "mt", "ft", "hl", "bzw", "z.b", "u.a", "jh", "jhd", "str", "gen", "col", "lt",
        )

        private fun base(tag: String) = tag.substringBefore('-').lowercase()

        /** Language the notes are written in: the Wikipedia edition the extract came from. */
        fun textLanguage(place: PlaceCandidate, sessionLanguage: String): String {
            val wiki = place.source.removePrefix("wikipedia:").takeIf { place.source.startsWith("wikipedia:") && it.isNotBlank() }
            return if (wiki == null || wiki == base(sessionLanguage)) sessionLanguage else wiki
        }

        /** "Quick note about Ort Castle, about 300 metres ahead:" (English); other languages just name the place. */
        fun leadIn(c: RankedCandidate, mode: TravelMode, headingDeg: Double?, language: String): String {
            if (base(language) != "en") return "${c.place.name}:"
            val where = when {
                c.distanceM < 60 -> "right here"
                else -> {
                    val direction = if (headingDeg != null && mode != TravelMode.STATIONARY) {
                        Geo.relativeDirection(c.bearingDeg, headingDeg)
                    } else "to the " + Geo.compass(c.bearingDeg)
                    RadioAgent.describeDistance(c.distanceM) + " " + direction
                }
            }
            return "Quick note about ${c.place.name}, $where:"
        }

        /** The first few sentences of the place's source text, cleaned for speech. */
        fun notes(place: PlaceCandidate, maxSentences: Int = 3, maxChars: Int = 480): String {
            val raw = place.extract?.takeIf { it.isNotBlank() } ?: place.description ?: return ""
            val prose = if (place.source == "openstreetmap") osmProse(raw, place.description) else raw
            val picked = ArrayList<String>()
            var length = 0
            for (s in sentences(stripParentheticals(prose))) {
                if (picked.size >= maxSentences) break
                if (picked.isNotEmpty() && length + 1 + s.length > maxChars) break
                picked += s
                length += s.length + if (picked.size > 1) 1 else 0
            }
            val text = picked.joinToString(" ")
            return if (text.length > maxChars) text.take(maxChars).substringBeforeLast(' ') + "…" else text
        }

        /** OSM facts ("historic: castle; heritage-listed") read as short phrases; a description tag wins. */
        private fun osmProse(facts: String, description: String?): String {
            description?.takeIf { it.isNotBlank() }?.let { return it.trim().let { d -> if (d.last() in ".!?") d else "$d." } }
            return facts.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") { part ->
                val phrase = part.replace(": ", " ").replace('_', ' ').trim()
                phrase.replaceFirstChar { it.uppercase() } + "."
            }
        }

        /** Drops "(pronounced …)", "(German: …)" and similar asides that sound awkward read aloud. */
        fun stripParentheticals(s: String): String {
            var out = s
            repeat(3) { out = out.replace(Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]"), "") }
            return out.replace(Regex("\\s+"), " ").replace(" ,", ",").replace(" .", ".").trim()
        }

        /** Splits prose into sentences without breaking at common abbreviations, initials or decimals. */
        fun sentences(text: String): List<String> {
            val t = text.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            var start = 0
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                val atEnd = i == t.length - 1
                if (ch in ".!?。" && (atEnd || t[i + 1] == ' ')) {
                    val candidate = t.substring(start, i + 1).trim()
                    val lastWord = candidate.dropLast(1).substringAfterLast(' ').trim('"', '\'', '(', '«', '„').lowercase()
                    val nextStartsLower = !atEnd && i + 2 < t.length && t[i + 2].isLowerCase()
                    val isAbbrev = ch == '.' && (
                        lastWord in ABBREVIATIONS ||
                            (lastWord.length == 1 && lastWord[0].isLetter()) ||
                            // German-style ordinals: "im 11. Jahrhundert".
                            (lastWord.length <= 2 && lastWord.isNotEmpty() && lastWord.all { it.isDigit() }) ||
                            nextStartsLower
                        )
                    if (!isAbbrev || atEnd) {
                        out += candidate
                        start = i + 1
                    }
                }
                i++
            }
            if (start < t.length) t.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            return out
        }
    }
}
