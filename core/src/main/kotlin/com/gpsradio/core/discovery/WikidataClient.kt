package com.gpsradio.core.discovery

import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.net.fetchString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Wikidata (keyless SPARQL): film and TV locations, dated historical events, and birthplaces of notable
 * Jewish or Israeli people near a point (spec A §29). Results are grounded: every film/event is a Wikidata item, and events carry their
 * Wikipedia article title so the story can use its intro as facts.
 */
class WikidataClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val endpoint: HttpUrl = "https://query.wikidata.org/sparql".toHttpUrl(),
) {
    data class Film(val qid: String, val title: String, val year: Int?)

    /** A place where films were shot: the location item and what was filmed there. */
    data class FilmLocation(val qid: String, val name: String, val point: GeoPoint, val films: List<Film>)

    /** A dated event with coordinates, e.g. a battle or a treaty signing. */
    data class Event(val qid: String, val name: String, val point: GeoPoint, val year: Int?, val description: String?, val article: String?)

    suspend fun filmLocations(center: GeoPoint, radiusM: Int, lang: String, limit: Int = 60): List<FilmLocation> {
        val rows = query(filmQuery(center, radiusM, lang, limit))
        return rows.groupBy { it["loc"]?.let(::qid) }
            .mapNotNull { (loc, rs) ->
                loc ?: return@mapNotNull null
                val first = rs.first()
                val point = first["coord"]?.let(::parsePoint) ?: return@mapNotNull null
                val name = first["locLabel"]?.takeUnless { looksLikeQid(it) } ?: return@mapNotNull null
                val films = rs.mapNotNull { r ->
                    val f = r["film"]?.let(::qid) ?: return@mapNotNull null
                    val title = r["filmLabel"]?.takeUnless { looksLikeQid(it) } ?: return@mapNotNull null
                    Film(f, title, r["date"]?.take(4)?.toIntOrNull())
                }.distinctBy { it.qid }.sortedBy { it.year ?: Int.MAX_VALUE }
                if (films.isEmpty()) null else FilmLocation(loc, name, point, films)
            }
    }

    data class Person(val qid: String, val name: String, val description: String?)

    /** A place near the listener that is the birthplace of notable Jewish or Israeli people. */
    data class JewishConnection(val qid: String, val name: String, val point: GeoPoint, val people: List<Person>)

    suspend fun jewishConnections(center: GeoPoint, radiusM: Int, lang: String, limit: Int = 40): List<JewishConnection> =
        query(jewishQuery(center, radiusM, lang, limit)).groupBy { it["place"]?.let(::qid) }.mapNotNull { (place, rs) ->
            place ?: return@mapNotNull null
            val first = rs.first()
            val point = first["coord"]?.let(::parsePoint) ?: return@mapNotNull null
            val name = first["placeLabel"]?.takeUnless { looksLikeQid(it) } ?: return@mapNotNull null
            val people = rs.mapNotNull { r ->
                val q = r["person"]?.let(::qid) ?: return@mapNotNull null
                val n = r["personLabel"]?.takeUnless { looksLikeQid(it) } ?: return@mapNotNull null
                Person(q, n, r["personDescription"])
            }.distinctBy { it.qid }
            if (people.isEmpty()) null else JewishConnection(place, name, point, people)
        }

    suspend fun events(center: GeoPoint, radiusM: Int, lang: String, limit: Int = 40): List<Event> =
        query(eventQuery(center, radiusM, lang, limit)).mapNotNull { r ->
            val id = r["e"]?.let(::qid) ?: return@mapNotNull null
            val name = r["eLabel"]?.takeUnless { looksLikeQid(it) } ?: return@mapNotNull null
            val point = r["coord"]?.let(::parsePoint) ?: return@mapNotNull null
            Event(id, name, point, r["date"]?.let(::year), r["eDescription"], r["article"]?.substringAfterLast("/wiki/")?.let(::decodeTitle))
        }.distinctBy { it.qid }

    private suspend fun query(sparql: String): List<Map<String, String>> {
        val url = endpoint.newBuilder().addQueryParameter("query", sparql).addQueryParameter("format", "json").build()
        val body = http.fetchString(
            Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/sparql-results+json").build(),
        )
        return parse(body)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Films, TV series and episodes, documentaries, music videos. */
        private const val SCREEN_TYPES = "wd:Q11424 wd:Q5398426 wd:Q21191270 wd:Q93204 wd:Q193977 wd:Q24862 wd:Q506240"

        private fun around(center: GeoPoint, radiusM: Int, subject: String) = """
            SERVICE wikibase:around {
              $subject wdt:P625 ?coord .
              bd:serviceParam wikibase:center "Point(${fmt(center.lon)} ${fmt(center.lat)})"^^geo:wktLiteral .
              bd:serviceParam wikibase:radius "${fmt(radiusM / 1000.0)}" .
            }
        """.trimIndent()

        fun filmQuery(center: GeoPoint, radiusM: Int, lang: String, limit: Int) = """
            SELECT ?loc ?locLabel ?coord ?film ?filmLabel ?date WHERE {
              ${around(center, radiusM, "?loc")}
              ?film wdt:P915 ?loc .
              ?film wdt:P31 ?type . VALUES ?type { $SCREEN_TYPES }
              OPTIONAL { ?film wdt:P577 ?date . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "$lang,en" . }
            } LIMIT $limit
        """.trimIndent()

        /** Items with coordinates and a point in time or start date: events, not buildings (which have an inception). */
        fun eventQuery(center: GeoPoint, radiusM: Int, lang: String, limit: Int) = """
            SELECT ?e ?eLabel ?eDescription ?coord ?date ?article WHERE {
              ${around(center, radiusM, "?e")}
              { ?e wdt:P585 ?date . } UNION { ?e wdt:P580 ?date . }
              OPTIONAL { ?article schema:about ?e ; schema:isPartOf <https://$lang.wikipedia.org/> . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "$lang,en" . }
            } LIMIT $limit
        """.trimIndent()

        /**
         * Places nearby that are the birthplace of well-known (many Wikipedia editions) people who were
         * Israeli citizens, of Jewish faith or of Jewish ethnicity.
         */
        fun jewishQuery(center: GeoPoint, radiusM: Int, lang: String, limit: Int) = """
            SELECT ?place ?placeLabel ?coord ?person ?personLabel ?personDescription ?links WHERE {
              ${around(center, radiusM, "?place")}
              ?person wdt:P19 ?place .
              { ?person wdt:P27 wd:Q801 . } UNION { ?person wdt:P140 wd:Q9268 . } UNION { ?person wdt:P172 wd:Q7325 . }
              ?person wikibase:sitelinks ?links . FILTER(?links >= 8)
              SERVICE wikibase:label { bd:serviceParam wikibase:language "$lang,en" . }
            } ORDER BY DESC(?links) LIMIT $limit
        """.trimIndent()

        fun peopleText(people: List<Person>, max: Int = 5): String =
            people.take(max).joinToString("; ") { p -> p.description?.let { "${p.name} ($it)" } ?: p.name } +
                if (people.size > max) "; and ${people.size - max} more" else ""

        /** SPARQL JSON results → one map of variable → value per row. */
        fun parse(body: String): List<Map<String, String>> {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            val bindings = (root["results"] as? JsonObject)?.get("bindings")?.jsonArray ?: return emptyList()
            return bindings.map { b ->
                b.jsonObject.mapNotNull { (k, v) -> ((v as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            }
        }

        /** "Point(13.8 47.9)" → GeoPoint(47.9, 13.8). */
        fun parsePoint(wkt: String): GeoPoint? {
            val nums = Regex("""Point\(\s*(-?[\d.]+)\s+(-?[\d.]+)\s*\)""").find(wkt) ?: return null
            return GeoPoint(nums.groupValues[2].toDouble(), nums.groupValues[1].toDouble())
        }

        /** "1809-05-03T00:00:00Z" → 1809; "-0480-..." → -480. */
        fun year(date: String): Int? = Regex("""^(-?\d{1,4})-""").find(date.trim())?.groupValues?.get(1)?.toIntOrNull()

        private fun qid(uri: String) = uri.substringAfterLast('/').takeIf { it.startsWith("Q") }
        private fun looksLikeQid(label: String) = Regex("^Q\\d+$").matches(label)
        private fun decodeTitle(t: String) = java.net.URLDecoder.decode(t, "UTF-8").replace('_', ' ')
        private fun fmt(d: Double) = String.format(Locale.ROOT, "%.5f", d)

        fun filmsText(films: List<Film>, max: Int = 6): String =
            films.take(max).joinToString(", ") { f -> f.year?.let { "${f.title} ($it)" } ?: f.title } +
                if (films.size > max) " and ${films.size - max} more" else ""
    }
}
