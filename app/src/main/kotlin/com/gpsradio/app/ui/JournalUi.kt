package com.gpsradio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpsradio.core.journal.JournalEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Callbacks of the trip journal. */
data class JournalActions(
    val onRetell: (JournalEntry) -> Unit = {},
    val onShare: (JournalEntry) -> Unit = {},
    /** Export one day (yyyy-MM-dd) as GPX. */
    val onExportDay: (String) -> Unit = {},
)

/**
 * The "Journal" section of the Saved tab: stories heard to the end, grouped by day (newest first),
 * each with "Tell me again" (only while the radio is on) and Share, plus a GPX export per day.
 * Added to an existing list so it scrolls together with the saved places.
 */
fun LazyListScope.journalItems(journal: List<JournalEntry>, canRetell: Boolean, actions: JournalActions) {
    if (journal.isEmpty()) return
    item(key = "journal-header") {
        Text(
            "Journal",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
    }
    journal.sortedByDescending { it.timeMs }.groupBy { it.day }.forEach { (day, entries) ->
        item(key = "journal-day-$day") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    dayLabel(day),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.onExportDay(day) }) { Text("Export GPX") }
            }
        }
        items(entries, key = { "journal-${it.day}-${it.placeId}" }) { e -> JournalRow(e, canRetell, actions) }
    }
}

/** Standalone journal list (used where no other list hosts it, and in tests). */
@Composable
fun JournalList(journal: List<JournalEntry>, canRetell: Boolean, actions: JournalActions, modifier: Modifier = Modifier) {
    if (journal.isEmpty()) {
        Text(
            "Stories you hear will be collected here, day by day.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    LazyColumn(modifier.testTag("journal"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        journalItems(journal, canRetell, actions)
    }
}

@Composable
private fun JournalRow(e: JournalEntry, canRetell: Boolean, actions: JournalActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    timeLabel(e.timeMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (e.firstSentence.isNotBlank()) {
                Text(e.firstSentence, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canRetell) TextButton(onClick = { actions.onRetell(e) }) { Text("Tell me again") }
                IconButton(onClick = { actions.onShare(e) }) { Icon(Icons.Default.Share, "Share ${e.name}") }
            }
        }
    }
}

fun dayLabel(day: String, today: LocalDate = LocalDate.now()): String = when (day) {
    today.toString() -> "Today"
    today.minusDays(1).toString() -> "Yesterday"
    else -> day
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

fun timeLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = clockFormat.format(Instant.ofEpochMilli(ms).atZone(zone))
