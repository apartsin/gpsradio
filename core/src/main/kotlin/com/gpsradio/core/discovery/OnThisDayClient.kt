package com.gpsradio.core.discovery

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.net.HttpException
import com.gpsradio.core.net.fetchString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** A historical event on today's date, with the Wikipedia pages that ground it. */
data class OnThisDayEvent(
    val year: Int?,
    val text: String,
    val pages: List<Page> = emptyList(),
) {
    data class Page(
        val title: String,
        val description: String? = null,
        val extract: String? = null,
        val url: String? = null,
        val point: GeoPoint? = null,
    )
}

/** Source of "on this day" events for a language edition and calendar date. */
fun interface OnThisDaySource {
    suspend fun events(lang: String, month: Int, day: Int): List<OnThisDayEvent>
}

/**
 * Wikipedia's keyless "on this day" feed (`/api/rest_v1/feed/onthisday/events/MM/DD`).
 * Not every language edition has the feed; those fall back to English (the host narrates in
 * the listener's language anyway). Results are cached per language and date.
 */
class OnThisDayClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    /** Feed base for a language edition; the client appends `MM/DD`. Tests point it at a local server. */
    private val baseUrl: (String) -> HttpUrl = { lang -> "https://$lang.wikipedia.org/api/rest_v1/feed/onthisday/events".toHttpUrl() },
    private val fallbackLang: String = "en",
) : OnThisDaySource {
    private val cache = HashMap<String, List<OnThisDayEvent>>()

    override suspend fun events(lang: String, month: Int, day: Int): List<OnThisDayEvent> {
        val key = "$lang|$month|$day"
        synchronized(cache) { cache[key] }?.let { return it }
        val events = try {
            fetch(lang, month, day)
        } catch (e: HttpException) {
            // No feed for this edition: use the fallback edition instead.
            if (e.code in listOf(400, 403, 404, 501) && lang != fallbackLang) fetch(fallbackLang, month, day) else throw e
        }
        synchronized(cache) { cache[key] = events }
        return events
    }

    private suspend fun fetch(lang: String, month: Int, day: Int): List<OnThisDayEvent> {
        val url = baseUrl(lang).newBuilder()
            .addPathSegment("%02d".format(month))
            .addPathSegment("%02d".format(day))
            .build()
        val body = http.fetchString(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build())
        return parse(body)
    }

    @Serializable private data class Feed(val events: List<EventItem> = emptyList())
    @Serializable private data class EventItem(val text: String = "", val year: Int? = null, val pages: List<PageItem> = emptyList())
    @Serializable private data class PageItem(
        val title: String = "",
        val titles: Titles? = null,
        val description: String? = null,
        val extract: String? = null,
        val coordinates: Coordinates? = null,
        val content_urls: ContentUrls? = null,
    )
    @Serializable private data class Titles(val normalized: String? = null)
    @Serializable private data class Coordinates(val lat: Double, val lon: Double)
    @Serializable private data class ContentUrls(val desktop: PageUrl? = null)
    @Serializable private data class PageUrl(val page: String? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Sad or violent events are still told (respectfully) but are not the first choice for a light filler. */
        private val sombre = Regex(
            "\\b(kill|killed|killing|dead|deaths?|died|massacre|murder|bomb|bombing|attack|war|earthquake|disaster|crash|shooting|genocide|terror\\w*|execut\\w*)\\b",
            RegexOption.IGNORE_CASE,
        )

        fun parse(body: String): List<OnThisDayEvent> =
            json.decodeFromString(Feed.serializer(), body).events
                .filter { it.text.isNotBlank() }
                .map { e ->
                    OnThisDayEvent(
                        year = e.year,
                        text = e.text.trim(),
                        pages = e.pages.map { p ->
                            OnThisDayEvent.Page(
                                title = p.titles?.normalized ?: p.title.replace('_', ' '),
                                description = p.description,
                                extract = p.extract,
                                url = p.content_urls?.desktop?.page,
                                point = p.coordinates?.let { GeoPoint(it.lat, it.lon) },
                            )
                        },
                    )
                }

        /** Names to look for: city, region and the country in English, the edition's language and its own. */
        fun areaNames(area: AreaLabel?, lang: String): Map<String, Double> {
            if (area == null) return emptyMap()
            val names = LinkedHashMap<String, Double>()
            area.city?.takeIf { it.isNotBlank() }?.let { names[it] = 3.0 }
            area.region?.takeIf { it.isNotBlank() }?.let { names[it] = 2.0 }
            area.countryCode?.takeIf { it.length == 2 }?.let { cc ->
                val country = Locale("", cc.uppercase())
                listOf(Locale.ENGLISH, Locale(lang)).forEach { l ->
                    country.getDisplayCountry(l).takeIf { it.isNotBlank() && !it.equals(cc, ignoreCase = true) }?.let { names.putIfAbsent(it, 2.0) }
                }
            }
            return names
        }

        /** How related an event is to where the listener is (and how suitable for a light filler). */
        fun relevance(event: OnThisDayEvent, area: AreaLabel?, point: GeoPoint?, lang: String): Double {
            val haystack = buildString {
                append(event.text)
                event.pages.forEach { p -> append(' ').append(p.title).append(' ').append(p.description.orEmpty()) }
            }
            var score = 0.0
            areaNames(area, lang).forEach { (name, weight) ->
                if (Regex("\\b" + Regex.escape(name) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(haystack)) score += weight
            }
            if (point != null) {
                val nearest = event.pages.mapNotNull { it.point }.minOfOrNull { Geo.distanceM(point, it) }
                if (nearest != null) score += when {
                    nearest < 50_000 -> 3.0
                    nearest < 300_000 -> 1.5
                    else -> 0.0
                }
            }
            if (event.pages.any { (it.extract?.length ?: 0) >= 200 }) score += 0.5
            if (sombre.containsMatchIn(event.text)) score -= 1.5
            return score
        }

        /** How close an event's own place must be to count as local. */
        const val LOCAL_RADIUS_M = 100_000.0

        /**
         * Whether an event really belongs to where the listener is: it names their town or region, or one of its
         * places is within [LOCAL_RADIUS_M]. Sharing only the country is not enough (spec A §35).
         */
        fun isLocal(event: OnThisDayEvent, area: AreaLabel?, point: GeoPoint?): Boolean {
            val haystack = buildString {
                append(event.text)
                event.pages.forEach { p -> append(' ').append(p.title).append(' ').append(p.description.orEmpty()) }
            }
            val named = listOfNotNull(area?.city, area?.region).filter { it.isNotBlank() }.any { name ->
                Regex("\\b" + Regex.escape(name) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
            }
            val near = point != null && event.pages.mapNotNull { it.point }.any { Geo.distanceM(point, it) <= LOCAL_RADIUS_M }
            return named || near
        }

        /**
         * The most relevant event about the listener's own area, not told before; null when none is local:
         * then there is no "on this day" segment rather than one about somewhere else.
         */
        fun pick(
            events: List<OnThisDayEvent>,
            area: AreaLabel?,
            point: GeoPoint?,
            lang: String,
            exclude: Set<String> = emptySet(),
        ): OnThisDayEvent? = events
            .filter { it.text !in exclude && isLocal(it, area, point) }
            .maxByOrNull { relevance(it, area, point, lang) }
    }
}
