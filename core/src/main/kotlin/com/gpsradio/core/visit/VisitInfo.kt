package com.gpsradio.core.visit

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.PlaceFeature
import com.gpsradio.core.model.RoadTripKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Practical facts for visiting a place today (spec A §31): hours, admission, what kind of visit it is,
 * how long to spend, how hard the walk is, what to expect. Every field is optional: unknown stays null,
 * and the host says it couldn't be confirmed rather than guessing.
 */
@Serializable
data class VisitInfo(
    /** true/false when confirmed for today; null when unknown. */
    val openToday: Boolean? = null,
    /** e.g. "10:00–17:00". */
    val hoursToday: String? = null,
    /** e.g. "adults €8, children free", or "free". */
    val admission: String? = null,
    /** drive_by, short_stop, visit, walk, hike. */
    val visitType: String? = null,
    /** Typical time to spend there, minutes. */
    val visitMinutes: Int? = null,
    /** none, easy, moderate, difficult. */
    val walkEffort: String? = null,
    /** e.g. "20 min uphill on a gravel path from the car park". */
    val walkNote: String? = null,
    /** One or two lines on what to expect (view, crowds, parking, facilities). */
    val expect: String? = null,
    val sourceUrl: String? = null,
    /** "web" (checked online today), "osm" (OpenStreetMap tags), "mixed" (web plus OSM hours/admission), or "none". */
    val source: String = "web",
    val checkedMs: Long = 0,
) {
    /**
     * Short line for cards, in the listener's language, e.g. "open 10:00–17:00 · adults €8 · ~45 min visit ·
     * easy walk" (en) or "открыто 10:00–17:00 · … · ~45 мин · лёгкая прогулка" (ru). The admission text is
     * already in that language (the web check writes it so).
     */
    fun summary(language: String = "en"): String {
        val w = SummaryWords.of(language)
        return listOfNotNull(
            when (openToday) {
                false -> w.closedToday
                true -> hoursToday?.let { "${w.open} $it" } ?: w.openToday
                null -> hoursToday
            },
            admission,
            visitMinutes?.let { w.minutes(it) },
            walkEffort?.takeIf { it != "none" }?.let { w.walk(it) },
        ).joinToString(" · ")
    }
}

/** The few fixed words of [VisitInfo.summary], per language (English fallback). */
private class SummaryWords(
    val open: String,
    val openToday: String,
    val closedToday: String,
    val minutes: (Int) -> String,
    val walk: (String) -> String,
) {
    companion object {
        fun of(language: String): SummaryWords = when (language.substringBefore('-').lowercase()) {
            "ru" -> SummaryWords("открыто", "открыто сегодня", "сегодня закрыто", { "~$it мин" }, { e ->
                when (e) { "easy" -> "лёгкая прогулка"; "moderate" -> "прогулка средней сложности"; "difficult" -> "трудная прогулка"; else -> e }
            })
            "he" -> SummaryWords("פתוח", "פתוח היום", "סגור היום", { "כ-$it דק׳" }, { e ->
                when (e) { "easy" -> "הליכה קלה"; "moderate" -> "הליכה בינונית"; "difficult" -> "הליכה קשה"; else -> e }
            })
            "de" -> SummaryWords("geöffnet", "heute geöffnet", "heute geschlossen", { "~$it Min." }, { e ->
                when (e) { "easy" -> "leichter Weg"; "moderate" -> "mittlerer Weg"; "difficult" -> "schwieriger Weg"; else -> e }
            })
            else -> SummaryWords("open", "open today", "closed today", { "~$it min visit" }, { "$it walk" })
        }
    }
}

/** A port so tests can fake the web lookup; [VisitScout] is the web-search implementation. */
fun interface VisitSource {
    /** [language]: the listener's language; all free text (admission, walk note, what to expect) comes back in it. */
    suspend fun lookup(place: PlaceCandidate, area: AreaLabel?, nowMs: Long, zone: ZoneId, language: String): VisitInfo?
}

object Visits {
    private val paidKinds = Regex(
        "museum|gallery|zoo|aquarium|theme_park|castle|palace|schloss|burg|fortress|cave|mine|tower|observatory|" +
            "attraction|monastery|abbey|cathedral|park",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Worth checking hours/fees for: detours offered while driving, places to eat or shop, and sights
     * that usually have opening hours or tickets. Not for statues, viewpoints, lakes or peaks.
     */
    fun worthChecking(place: PlaceCandidate, roadTrip: RoadTripKind?): Boolean =
        roadTrip == RoadTripKind.WORTH_A_STOP ||
            PlaceFeature.EAT_DRINK in place.features || PlaceFeature.SHOP in place.features ||
            place.openingHours != null || place.fee != null ||
            paidKinds.containsMatchIn("${place.category} ${place.description.orEmpty()}")

    /** What OSM tags alone say (no network): today's hours from `opening_hours`, and `fee`/`charge`. */
    fun fromOsm(place: PlaceCandidate, nowMs: Long, zone: ZoneId): VisitInfo? {
        if (place.openingHours == null && place.fee == null) return null
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDateTime()
        val today = place.openingHours?.let { OpeningHours.today(it, now) }
        return VisitInfo(
            openToday = today?.let { it.isNotEmpty() },
            hoursToday = today?.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.first}–${it.second}" },
            admission = place.fee,
            source = "osm",
            checkedMs = nowMs,
        )
    }
}

/**
 * A small evaluator for OSM `opening_hours` covering the common forms: "24/7", "Mo-Fr 09:00-17:00",
 * "Sa,Su 10:00-12:00,13:00-18:00", "Mo off", rules separated by ";" (later rules win). Anything more
 * exotic (holidays, months, weeks, sunrise) returns null, so the host says it couldn't confirm.
 */
object OpeningHours {
    private val days = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val timeRange = Regex("""^(\d{1,2}:\d{2})-(\d{1,2}:\d{2})$""")

    /** Today's open intervals ("09:00" to "17:00"); empty when closed today; null when the spec isn't understood. */
    fun today(spec: String, now: LocalDateTime): List<Pair<String, String>>? {
        val s = spec.trim()
        if (s == "24/7") return listOf("00:00" to "24:00")
        val dow = now.dayOfWeek
        var result: List<Pair<String, String>>? = null
        var matchedAny = false
        for (rule in s.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = rule.split(Regex("\\s+"), limit = 2)
            val (daySel, times) = if (parts[0].first().isDigit()) "Mo-Su" to rule else parts[0] to parts.getOrElse(1) { "" }
            if (Regex("[A-Za-z]").containsMatchIn(times.replace("off", "").replace("closed", ""))) return null
            val applies = dayMatches(daySel, dow) ?: return null
            if (!applies) { matchedAny = true; continue }
            matchedAny = true
            val t = times.trim()
            result = if (t == "off" || t == "closed") emptyList() else {
                t.split(',').map { r -> timeRange.find(r.trim())?.let { it.groupValues[1] to it.groupValues[2] } ?: return null }
            }
        }
        if (!matchedAny) return null
        return result ?: emptyList()
    }

    /** "Mo-Fr", "Sa,Su", "Tu" → whether [d] is included; null when not understood (e.g. "PH"). */
    private fun dayMatches(sel: String, d: DayOfWeek): Boolean? {
        var any = false
        for (part in sel.split(',')) {
            val range = part.split('-')
            val a = days.indexOf(range[0])
            val b = if (range.size == 2) days.indexOf(range[1]) else a
            if (a < 0 || b < 0) return null
            val i = d.value - 1
            if (if (a <= b) i in a..b else i >= a || i <= b) any = true
        }
        return any
    }

    /** Whether [now] falls in one of today's intervals (an interval ending after midnight, e.g. 18:00–02:00, included). */
    fun openAt(intervals: List<Pair<String, String>>, now: LocalTime): Boolean = intervals.any { (a, b) ->
        val start = LocalTime.parse(a.padStart(5, '0'))
        if (b == "24:00") return@any !now.isBefore(start)
        val end = LocalTime.parse(b.padStart(5, '0'))
        if (end.isAfter(start)) now >= start && now < end else now >= start || now < end
    }
}

/** Checks today's hours, admission and what a visit involves with OpenAI web search. */
class VisitScout(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
) : VisitSource {
    override suspend fun lookup(place: PlaceCandidate, area: AreaLabel?, nowMs: Long, zone: ZoneId, language: String): VisitInfo? {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val input = buildJsonObject {
            put("place", place.name)
            put("category", place.category)
            area?.let { a -> put("area", listOfNotNull(a.city, a.region, a.countryCode).joinToString(", ")) }
            put("coordinates", "%.4f, %.4f".format(java.util.Locale.ROOT, place.point.lat, place.point.lon))
            put("output_language", "${com.gpsradio.core.lang.Languages.displayName(language)} ($language)")
            put("local_date", now.format(DateTimeFormatter.ISO_LOCAL_DATE))
            put("weekday", now.dayOfWeek.name.lowercase())
            place.openingHours?.let { put("osm_opening_hours", it) }
            place.fee?.let { put("osm_fee", it) }
        }
        val res = openAi.respond(
            OpenAiClient.ResponseRequest(
                model = models().researchModel,
                instructions = INSTRUCTIONS,
                input = listOf(OpenAiClient.Message("user", input.toString())),
                webSearch = true,
                userArea = area,
                jsonSchema = "visit_info" to schema,
                maxOutputTokens = 700,
                cacheKey = "gpsradio-visit",
            ),
        )
        return parse(res.text, nowMs)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        const val INSTRUCTIONS = """
You check practical visitor information for ONE place, for today, using web search (official site first).
Report only what you can confirm for today's date and weekday; use null/"" for anything you cannot confirm.
- open_today: true/false if confirmed for today, else null. hours_today like "10:00–17:00" (local), else "".
- admission: short, e.g. "adults €8, children free" or "free"; "" if unknown.
- visit_type: drive_by (seen from the road), short_stop (under ~20 min), visit (a proper visit), walk, or hike.
- visit_minutes: typical time visitors spend, or 0 if unknown.
- walk_effort: none, easy, moderate or difficult (from parking/stop to the sight); walk_note: one short line, e.g.
  "15 min uphill on a gravel path", or "".
- expect: one or two short lines on what a visitor will find (view, crowds, parking, facilities), or "".
- source_url: the page you used. Write admission, walk_note and expect in output_language (translate what you
  found); no marketing language.
"""

        val schema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("open_today") { putJsonArray("type") { add("boolean"); add("null") } }
                putJsonObject("hours_today") { put("type", "string") }
                putJsonObject("admission") { put("type", "string") }
                putJsonObject("visit_type") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("drive_by", "short_stop", "visit", "walk", "hike").forEach { add(it) } }
                }
                putJsonObject("visit_minutes") { put("type", "integer") }
                putJsonObject("walk_effort") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("none", "easy", "moderate", "difficult").forEach { add(it) } }
                }
                putJsonObject("walk_note") { put("type", "string") }
                putJsonObject("expect") { put("type", "string") }
                putJsonObject("source_url") { put("type", "string") }
            }
            putJsonArray("required") {
                listOf("open_today", "hours_today", "admission", "visit_type", "visit_minutes", "walk_effort", "walk_note", "expect", "source_url")
                    .forEach { add(JsonPrimitive(it)) }
            }
        }

        @Serializable private data class Raw(
            val open_today: Boolean? = null,
            val hours_today: String = "",
            val admission: String = "",
            val visit_type: String = "",
            val visit_minutes: Int = 0,
            val walk_effort: String = "",
            val walk_note: String = "",
            val expect: String = "",
            val source_url: String = "",
        )

        /** Blank → null; a result without a source link is not trusted for hours or prices. */
        fun parse(text: String, nowMs: Long): VisitInfo? {
            val r = runCatching { json.decodeFromString(Raw.serializer(), text.trim()) }.getOrNull() ?: return null
            val linked = r.source_url.startsWith("http")
            return VisitInfo(
                openToday = r.open_today.takeIf { linked },
                hoursToday = r.hours_today.ifBlank { null }?.takeIf { linked },
                admission = r.admission.ifBlank { null }?.takeIf { linked },
                visitType = r.visit_type.ifBlank { null },
                visitMinutes = r.visit_minutes.takeIf { it > 0 },
                walkEffort = r.walk_effort.ifBlank { null },
                walkNote = r.walk_note.ifBlank { null },
                expect = r.expect.ifBlank { null },
                sourceUrl = r.source_url.takeIf { linked },
                source = "web",
                checkedMs = nowMs,
            )
        }
    }
}
