package com.gpsradio.core.lang

import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.StoryBasis
import java.net.URI

/** A source named the way a presenter says it: "Wikipedia", "OpenStreetMap", "visit-gmunden.at". */
object SourceNames {
    fun of(url: String?, source: String? = null): String {
        val host = url?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
        return when {
            host == null -> when (source?.lowercase()) {
                "wikipedia" -> "Wikipedia"
                "osm", "openstreetmap" -> "OpenStreetMap"
                "wikidata" -> "Wikidata"
                else -> source ?: "?"
            }
            host.endsWith("wikipedia.org") -> "Wikipedia"
            host.endsWith("wikidata.org") -> "Wikidata"
            host.endsWith("wikimedia.org") -> "Wikimedia Commons"
            host.endsWith("openstreetmap.org") -> "OpenStreetMap"
            else -> host
        }
    }

    /** Russian listeners hear the Russian names of the big projects. */
    fun localized(name: String, language: String): String = if (language.substringBefore('-').lowercase() != "ru") name else when (name) {
        "Wikipedia" -> "Википедия"
        "Wikidata" -> "Викиданные"
        "Wikimedia Commons" -> "Викисклад"
        else -> name
    }
}

/**
 * "Where's that from?" answered on the device (spec A §45): instant, free and offline. Names the sources of the last
 * story like a presenter would (never a URL) and how well-founded it is; the links themselves are in the transcript.
 */
object SourceLines {
    private data class T(val from: String, val none: String, val legend: String, val disputed: String, val links: String, val and: String)

    private val texts = mapOf(
        "en" to T("That was from %s.", "I don't have a source for the last story, sorry.", "Parts of it are legend.",
            "Some of it is disputed.", "The links are in the transcript.", "and"),
        "ru" to T("Это было по материалам: %s.", "Для последней истории у меня нет источника, извините.", "Часть этого — легенда.",
            "Кое-что из этого спорно.", "Ссылки — в расшифровке.", "и"),
        "he" to T("זה היה מתוך %s.", "אין לי מקור לסיפור האחרון, סליחה.", "חלק מזה אגדה.", "חלק מזה שנוי במחלוקת.",
            "הקישורים בתמליל.", "ו"),
        "de" to T("Das stammte aus: %s.", "Für die letzte Geschichte habe ich leider keine Quelle.", "Teile davon sind Legende.",
            "Einiges davon ist umstritten.", "Die Links stehen im Verlauf.", "und"),
        "es" to T("Eso venía de %s.", "Lo siento, no tengo fuente para la última historia.", "Parte de ello es leyenda.",
            "Algo de ello es discutido.", "Los enlaces están en la transcripción.", "y"),
        "fr" to T("C'était tiré de %s.", "Désolé, je n'ai pas de source pour la dernière histoire.", "Une partie relève de la légende.",
            "Certains points sont discutés.", "Les liens sont dans la transcription.", "et"),
    )

    fun spoken(language: String, story: Segment?): String {
        val t = texts[language.substringBefore('-').lowercase()] ?: texts.getValue("en")
        val names = story?.sources.orEmpty().map { SourceNames.localized(SourceNames.of(it.url), language) }.filter { it != "?" }.distinct()
        if (names.isEmpty()) return t.none
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " " + t.and + " " + names.last()
        val basis = when (story?.basis) {
            StoryBasis.LEGEND, StoryBasis.MIXED -> " " + t.legend
            StoryBasis.DISPUTED -> " " + t.disputed
            else -> ""
        }
        return t.from.format(list) + basis + " " + t.links
    }
}
