package com.gpsradio.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.tour.TourState
import java.util.Locale
import kotlin.math.roundToInt

/** Tour lengths offered in the Nearby tab. */
val TOUR_MINUTES = listOf(15, 30, 60)

/** "Tour: 15 · 30 · 60 min" chips; hidden while a tour is running. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourChips(tour: TourState?, onStartTour: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (tour != null) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .horizontalScroll(rememberScrollState())
            .testTag("tourChips"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Walking tour:", style = MaterialTheme.typography.labelLarge)
        TOUR_MINUTES.forEach { m ->
            AssistChip(onClick = { onStartTour(m) }, label = { Text("$m min") })
        }
    }
}

/** Compact progress line at the top of the Now tab: "Stop 2 of 5 · Castle · 250 m" and End tour. */
@Composable
fun TourBanner(tour: TourState, location: LocationContext?, onEndTour: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("tourBanner")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tourProgress(tour, location),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onEndTour) { Text("End tour") }
        }
    }
}

fun tourProgress(tour: TourState, location: LocationContext?): String {
    val next = tour.next ?: return "Tour complete"
    val distance = location?.let { shortDistance(Geo.distanceM(it.point, next.point)) }
    return listOfNotNull("Stop ${tour.nextIndex + 1} of ${tour.stops.size}", next.name, distance).joinToString(" · ")
}

fun shortDistance(m: Double): String = when {
    m < 1000 -> "${((m / 10).roundToInt() * 10)} m"
    else -> String.format(Locale.getDefault(), "%.1f km", m / 1000)
}
