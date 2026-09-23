package com.gpsradio.core.events

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A public event happening today near the listener (spec A §30). */
@Serializable
data class LocalEvent(
    val title: String,
    /** concert, festival, market, fireworks, parade, theatre, cinema, exhibition, sports, other. */
    val category: String,
    val venue: String,
    val startMs: Long,
    val endMs: Long? = null,
    /** Source page for the event (required: no link, no event). */
    val url: String,
    /** One line on why a visitor would enjoy it. */
    val why: String = "",
    val distanceKm: Double? = null,
) {
    val id: String get() = "${title.lowercase().trim()}|$startMs"
}

/** Something that can list today's events near a point; [EventScout] is the web-search implementation. */
fun interface EventSource {
    suspend fun find(area: AreaLabel, point: GeoPoint, nowMs: Long, zone: ZoneId, language: String): List<LocalEvent>
}

/**
 * Finds tourist-worthy events happening now or in the next hours with OpenAI web search (the only
 * keyless source that covers "what's on tonight" worldwide), then filters them hard: visitor-worthy
 * categories only, no classes/courses/meetings/services, a source link for each, today's time window.
 */
class EventScout(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
) : EventSource {
    override suspend fun find(area: AreaLabel, point: GeoPoint, nowMs: Long, zone: ZoneId, language: String): List<LocalEvent> {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val place = listOfNotNull(area.city, area.region, area.countryCode).joinToString(", ")
        val input = buildJsonObject {
            put("area", place)
            put("approx_coordinates", "%.3f, %.3f".format(Locale.ROOT, point.lat, point.lon))
            put("local_now", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16))
            put("weekday", now.dayOfWeek.name.lowercase())
            put("window_hours", WINDOW_HOURS)
            put("output_language", "${com.gpsradio.core.lang.Languages.displayName(language)} ($language)")
        }
        val res = openAi.respond(
            OpenAiClient.ResponseRequest(
                model = models().conversationModel,
                instructions = INSTRUCTIONS,
                input = listOf(OpenAiClient.Message("user", input.toString())),
                webSearch = true,
                userArea = area,
                jsonSchema = "local_events" to schema,
                maxOutputTokens = 1500,
                cacheKey = "gpsradio-events",
            ),
        )
        return parse(res.text, nowMs, zone)
    }

    companion object {
        const val WINDOW_HOURS = 10
        /** Events further than this from the listener are not "nearby". */
        const val MAX_KM = 25.0

        val CATEGORIES = listOf("concert", "festival", "market", "fireworks", "parade", "theatre", "cinema", "exhibition", "sports", "other")

        private val json = Json { ignoreUnknownKeys = true }

        /** Things a tourist doesn't come for, whatever the model says. */
        private val irrelevant = Regex(
            listOf(
                "yoga", "pilates", "fitness", "zumba", "workout", "bootcamp", "meditation", "\\bclass(es)?\\b", "\\bcourse\\b",
                "workshop", "lesson", "seminar", "webinar", "training", "lecture series", "meeting", "networking", "conference",
                "support group", "\\bmass\\b", "worship", "church service", "bingo", "book club", "open office", "consultation",
                "kurs", "курс", "занятие", "йога", "тренинг", "семинар", "вебинар",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )

        fun isRelevant(e: LocalEvent): Boolean =
            e.category in CATEGORIES && !irrelevant.containsMatchIn("${e.title} ${e.why}") && e.url.startsWith("http")

        const val INSTRUCTIONS = """
You find public events for a visitor. Use web search. Given the area and local time, list events in or near that
area that happen TODAY and start within window_hours from local_now (or are running now), that a tourist would
enjoy: concerts and live music, festivals, markets, fireworks, parades, open-air cinema, theatre, special exhibitions
and openings, notable sports matches, fairs.
Exclude: classes and courses of any kind (yoga, fitness, dance, language, cooking), workshops, lectures, meetings,
conferences, networking, regular religious services, support groups, private or members-only events, and anything
you cannot confirm happens today.
Every event needs a real source page URL from your search. Times are local, "YYYY-MM-DDTHH:MM"; end_local may be "".
distance_km is your best estimate from approx_coordinates, or -1 if unknown. "why" is one short line on why a
visitor would enjoy it. Write "title" and "why" in output_language (translate them; keep proper names such as bands,
venues and festival names). Return at most 6 events, best first; return an empty list rather than guess.
"""

        val schema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("events") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonObject("properties") {
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("category") { put("type", "string"); putJsonArray("enum") { CATEGORIES.forEach { add(it) } } }
                            putJsonObject("venue") { put("type", "string") }
                            putJsonObject("start_local") { put("type", "string") }
                            putJsonObject("end_local") { put("type", "string") }
                            putJsonObject("url") { put("type", "string") }
                            putJsonObject("why") { put("type", "string") }
                            putJsonObject("distance_km") { put("type", "number") }
                        }
                        putJsonArray("required") {
                            listOf("title", "category", "venue", "start_local", "end_local", "url", "why", "distance_km").forEach { add(it) }
                        }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("events")) }
        }

        @Serializable private data class Raw(
            val title: String = "",
            val category: String = "other",
            val venue: String = "",
            val start_local: String = "",
            val end_local: String = "",
            val url: String = "",
            val why: String = "",
            val distance_km: Double = -1.0,
        )
        @Serializable private data class RawList(val events: List<Raw> = emptyList())

        private fun localMs(s: String, zone: ZoneId): Long? = runCatching {
            LocalDateTime.parse(s.trim().take(16), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()

        /**
         * Keeps events that are relevant, linked, nearby and in today's window: running now, or starting
         * within [WINDOW_HOURS] and on today's local date.
         */
        fun parse(text: String, nowMs: Long, zone: ZoneId): List<LocalEvent> {
            val raw = runCatching { json.decodeFromString(RawList.serializer(), text.trim()) }.getOrNull() ?: return emptyList()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return raw.events.mapNotNull { r ->
                val start = localMs(r.start_local, zone) ?: return@mapNotNull null
                val end = r.end_local.takeIf { it.isNotBlank() }?.let { localMs(it, zone) }
                LocalEvent(
                    r.title.trim(), r.category, r.venue.trim(), start, end, r.url.trim(), r.why.trim(),
                    r.distance_km.takeIf { it >= 0 },
                )
            }.filter { e ->
                val running = e.startMs <= nowMs && (e.endMs ?: (e.startMs + 3 * 3_600_000L)) > nowMs
                val upcoming = e.startMs in nowMs..(nowMs + WINDOW_HOURS * 3_600_000L) &&
                    Instant.ofEpochMilli(e.startMs).atZone(zone).toLocalDate() == today
                e.title.isNotBlank() && isRelevant(e) && (running || upcoming) && (e.distanceKm ?: 0.0) <= MAX_KM
            }.distinctBy { it.id }.sortedBy { it.startMs }
        }

        /** "20:00" local. */
        fun clock(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

        /** A plain spoken line (used without a model), e.g. "Today nearby: at 20:00, Jazz on the Lake at the Esplanade." */
        fun spoken(events: List<LocalEvent>, nowMs: Long, zone: ZoneId): String =
            "Today nearby: " + events.take(3).joinToString("; ") { e ->
                val `when` = if (e.startMs <= nowMs) "right now" else "at ${clock(e.startMs, zone)}"
                "$`when`, ${e.title}" + (e.venue.takeIf { it.isNotBlank() }?.let { " at $it" } ?: "")
            } + "."
    }
}
