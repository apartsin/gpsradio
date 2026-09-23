package com.gpsradio.core.journal

import com.gpsradio.core.favorites.ShareText
import com.gpsradio.core.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One story the listener heard to the end: the trip journal (spec A §18, "trip journal"). */
@Serializable
data class JournalEntry(
    val placeId: String,
    val name: String,
    val timeMs: Long,
    val point: GeoPoint,
    /** The first sentence of the story as it was told. */
    val firstSentence: String,
    val sourceUrl: String? = null,
    /** Local calendar day, yyyy-MM-dd. */
    val day: String,
) {
    companion object {
        fun dayOf(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toString()

        fun firstSentence(text: String, max: Int = 220): String {
            val t = text.trim().replace(Regex("\\s+"), " ")
            val s = Regex("^.+?[.!?…](?=\\s|$)").find(t)?.value ?: t
            return if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
        }
    }
}

/** Persists the serialized journal between sessions. */
interface JournalStore {
    fun load(): String?
    fun save(serialized: String)
}

/** Heard stories grouped by local day. Hearing a place again on the same day updates its entry. */
class Journal(
    /** Older days are dropped to keep the file small. */
    private val maxDays: Int = 90,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val items = ArrayList<JournalEntry>()

    /** All entries, newest first. */
    val all: List<JournalEntry> get() = items.sortedByDescending { it.timeMs }

    /** Days that have entries, newest first. */
    val days: List<String> get() = items.map { it.day }.distinct().sortedDescending()

    fun entriesFor(day: String): List<JournalEntry> = items.filter { it.day == day }.sortedBy { it.timeMs }

    fun record(placeId: String, name: String, point: GeoPoint, storyText: String, sourceUrl: String?, nowMs: Long): JournalEntry {
        val entry = JournalEntry(
            placeId = placeId,
            name = name,
            timeMs = nowMs,
            point = point,
            firstSentence = JournalEntry.firstSentence(storyText),
            sourceUrl = sourceUrl,
            day = JournalEntry.dayOf(nowMs, zone()),
        )
        items.removeAll { it.placeId == placeId && it.day == entry.day }
        items += entry
        prune(nowMs)
        return entry
    }

    fun remove(placeId: String, day: String) = items.removeAll { it.placeId == placeId && it.day == day }

    fun serialize(): String = json.encodeToString(ListSerializer(JournalEntry.serializer()), items.sortedBy { it.timeMs })

    fun restore(serialized: String?) {
        if (serialized.isNullOrBlank()) return
        val loaded = runCatching { json.decodeFromString(ListSerializer(JournalEntry.serializer()), serialized) }.getOrNull() ?: return
        items.clear()
        items.addAll(loaded)
    }

    private fun prune(nowMs: Long) {
        val oldest = Instant.ofEpochMilli(nowMs).atZone(zone()).toLocalDate().minusDays(maxDays.toLong())
        items.removeAll { runCatching { LocalDate.parse(it.day) < oldest }.getOrDefault(false) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Plain-text share message for one journal entry (Android share sheet). */
object JournalText {
    fun share(e: JournalEntry): String = buildString {
        append("📍 ").append(e.name)
        if (e.firstSentence.isNotBlank()) append("\n\n").append(e.firstSentence)
        e.sourceUrl?.let { append("\n\nRead more: ").append(it) }
        append("\nMap: ").append(ShareText.mapUrl(e.point))
        append("\n\nHeard on GPS Radio 📻")
    }

    fun subject(e: JournalEntry): String = "Heard on GPS Radio: ${e.name}"
}

/** GPX 1.1 export of a day's journal: one waypoint per story, plus a track in listening order. */
object JournalGpx {
    fun build(entries: List<JournalEntry>, title: String): String = buildString {
        val sorted = entries.sortedBy { it.timeMs }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GPS Radio\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>").append(esc(title)).append("</name>")
        sorted.firstOrNull()?.let { append("<time>").append(time(it.timeMs)).append("</time>") }
        append("</metadata>\n")
        sorted.forEach { e ->
            append("  <wpt lat=\"").append(e.point.lat).append("\" lon=\"").append(e.point.lon).append("\">")
            append("<time>").append(time(e.timeMs)).append("</time>")
            append("<name>").append(esc(e.name)).append("</name>")
            append("<desc>").append(esc(e.firstSentence)).append("</desc>")
            e.sourceUrl?.let { append("<link href=\"").append(esc(it)).append("\"/>") }
            append("</wpt>\n")
        }
        if (sorted.size >= 2) {
            append("  <trk><name>").append(esc(title)).append("</name><trkseg>\n")
            sorted.forEach { e ->
                append("    <trkpt lat=\"").append(e.point.lat).append("\" lon=\"").append(e.point.lon).append("\">")
                append("<time>").append(time(e.timeMs)).append("</time></trkpt>\n")
            }
            append("  </trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    fun fileName(day: String) = "gpsradio-$day.gpx"

    private fun time(ms: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS))

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
