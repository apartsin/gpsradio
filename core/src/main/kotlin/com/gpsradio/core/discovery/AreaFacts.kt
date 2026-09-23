package com.gpsradio.core.discovery

/** Fetches the article about a town or region (for area stories); null when there is none. */
fun interface AreaInfoSource {
    suspend fun article(lang: String, title: String): WikipediaClient.Article?
}

/** One angle on the current town or region, told as its own segment. */
enum class AreaFacetKind(val key: String, private val headingWords: List<String>) {
    OVERVIEW("overview", emptyList()),
    HISTORY("history", listOf("history", "geschichte", "histoire", "historia", "storia", "история", "historie", "dzieje")),
    PEOPLE(
        "people",
        listOf(
            "notable", "people", "personalities", "residents", "famous", "persönlichkeiten", "söhne und töchter",
            "personnalités", "personajes", "personalità", "известные", "люди", "уроженцы",
        ),
    ),
    CULTURE(
        "culture",
        listOf(
            "culture", "cuisine", "food", "tradition", "customs", "festival", "kultur", "brauchtum", "kulinar",
            "gastronom", "cultura", "culinaria", "культура", "кухня", "традиции",
        ),
    ),
    GEOGRAPHY(
        "geography",
        listOf("geography", "geographie", "geografie", "climate", "klima", "géographie", "geografía", "geografia", "география", "климат"),
    );

    fun matches(heading: String): Boolean {
        val h = heading.lowercase()
        return headingWords.any { it in h }
    }
}

/** Facts for one facet, straight from the fetched article (never invented). */
data class AreaFacet(
    val area: String,
    val kind: AreaFacetKind,
    val facts: String,
    val url: String? = null,
) {
    val id: String get() = "$area#${kind.key}"
}

object AreaFacts {
    const val MAX_FACTS_CHARS = 1500
    const val MIN_FACTS_CHARS = 200

    private val heading = Regex("^(={2,})\\s*(.+?)\\s*\\1\\s*$")

    /** Splits a plain-text article into (heading, text) top-level sections; the intro has heading "". */
    fun sections(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var current = ""
        val buf = StringBuilder()
        fun flush() {
            val body = buf.toString().trim()
            if (body.isNotEmpty() || current.isEmpty()) out += current to body
            buf.clear()
        }
        text.lines().forEach { line ->
            val m = heading.matchEntire(line.trim())
            when {
                m != null && m.groupValues[1].length == 2 -> { flush(); current = m.groupValues[2] }
                m != null -> Unit // sub-headings: keep their text in the parent section
                else -> buf.append(line).append('\n')
            }
        }
        flush()
        return out.filter { it.second.isNotEmpty() }
    }

    /** The facets this article supports, in telling order; each has enough grounded text to narrate. */
    fun facets(area: String, article: WikipediaClient.Article): List<AreaFacet> {
        val secs = sections(article.text)
        return AreaFacetKind.entries.mapNotNull { kind ->
            val text = if (kind == AreaFacetKind.OVERVIEW) {
                secs.firstOrNull { it.first.isEmpty() }?.second
            } else {
                secs.filter { kind.matches(it.first) }.joinToString("\n") { it.second }.ifBlank { null }
            }
            text?.replace(Regex("\\n{2,}"), "\n")?.trim()?.takeIf { it.length >= MIN_FACTS_CHARS }
                ?.let { AreaFacet(area, kind, it.take(MAX_FACTS_CHARS), article.url) }
        }
    }
}
