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
import androidx.compose.ui.res.stringResource
import com.gpsradio.app.R
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
        Text(stringResource(R.string.walking_tour), style = MaterialTheme.typography.labelLarge)
        TOUR_MINUTES.forEach { m ->
            AssistChip(onClick = { onStartTour(m) }, label = { Text(stringResource(R.string.minutes_short, m)) })
        }
    }
}

/** Compact progress line at the top of the Now tab: "Stop 2 of 5 · Castle · 250 m" and End tour. */
@Composable
fun TourBanner(tour: TourState, location: LocationContext?, onEndTour: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("tourBanner")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tourProgress(tour, location, tourLabels()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onEndTour) { Text(stringResource(R.string.end_tour)) }
        }
    }
}

/**
 * Format strings for the tour line. The defaults are the English resources (values/strings.xml), so the
 * functions below stay plain and testable; the UI passes the localized ones from [tourLabels].
 */
data class TourLabels(
    val stopOf: String = "Stop %1\$d of %2\$d",
    val complete: String = "Tour complete",
    val meters: String = "%1\$d m",
    val kilometers: String = "%1\$.1f km",
)

@Composable
fun tourLabels(): TourLabels = TourLabels(
    stopOf = stringResource(R.string.tour_stop_of),
    complete = stringResource(R.string.tour_complete),
    meters = stringResource(R.string.distance_m),
    kilometers = stringResource(R.string.distance_km),
)

fun tourProgress(tour: TourState, location: LocationContext?, labels: TourLabels = TourLabels()): String {
    val next = tour.next ?: return labels.complete
    val distance = location?.let { shortDistance(Geo.distanceM(it.point, next.point), labels) }
    val stop = String.format(Locale.getDefault(), labels.stopOf, tour.nextIndex + 1, tour.stops.size)
    return listOfNotNull(stop, next.name, distance).joinToString(" · ")
}

fun shortDistance(m: Double, labels: TourLabels = TourLabels()): String = when {
    m < 1000 -> String.format(Locale.getDefault(), labels.meters, (m / 10).roundToInt() * 10)
    else -> String.format(Locale.getDefault(), labels.kilometers, m / 1000)
}
