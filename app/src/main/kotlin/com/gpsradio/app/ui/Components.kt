package com.gpsradio.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.RadioUiState
import com.gpsradio.core.session.StatusLevel

fun stateLabel(s: RadioUiState): String = when (s.radioState) {
    RadioState.IDLE -> "Off air"
    RadioState.RADIO -> if (s.discovering) "Looking around…" else "Scanning for stories"
    RadioState.RESEARCHING -> "Tuning in…"
    RadioState.NARRATING -> "On air"
    RadioState.CONVERSING -> if (s.pendingOffer != null) "Your call" else "Conversation"
    RadioState.PAUSED -> "Paused"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCard(state: RadioUiState, onMode: (TravelMode?) -> Unit, onFixKey: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.radioState == RadioState.NARRATING) OnAirBadge()
                Text(
                    stateLabel(state),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.radioState == RadioState.NARRATING) Equalizer(Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                if (state.discovering || state.radioState == RadioState.RESEARCHING) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            val loc = state.location
            if (loc != null) {
                val mode = (state.modeOverride ?: loc.travelMode).name.lowercase().replaceFirstChar { it.uppercase() }
                val speed = if (loc.speedMps > 0.5) " · ${(loc.speedMps * 3.6).toInt()} km/h" else ""
                val theme = state.theme?.let { " · ${it.key} stories" } ?: ""
                Text("$mode$speed$theme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (state.radioState != RadioState.IDLE) {
                Text("Waiting for GPS…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.radioState != RadioState.IDLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    val detected = loc?.travelMode?.takeIf { it != TravelMode.UNKNOWN }?.name?.lowercase()
                    val modes = listOf(
                        null to (detected?.let { "Auto · $it" } ?: "Auto"),
                        TravelMode.WALKING to "Walk",
                        TravelMode.CYCLING to "Cycle",
                        TravelMode.DRIVING to "Drive",
                        TravelMode.STATIONARY to "Still",
                    )
                    modes.forEach { (mode, label) ->
                        FilterChip(selected = state.modeOverride == mode, onClick = { onMode(mode) }, label = { Text(label) })
                    }
                }
            }
            state.status?.let { st ->
                val color = when (st.level) {
                    StatusLevel.ERROR -> MaterialTheme.colorScheme.error
                    StatusLevel.WORKING -> MaterialTheme.colorScheme.primary
                    StatusLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (st.needsKey) TextButton(onClick = onFixKey) { Text("Open Settings") }
                }
            }
        }
    }
}

/** A small pulsing red "ON AIR" pill, like a studio light. */
@Composable
fun OnAirBadge() {
    val pulse by rememberInfiniteTransition(label = "onair").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFD7263D))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(Color.White))
        Text(" ON AIR", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** Four bouncing bars: shows that audio is playing or that the mic is listening. */
@Composable
fun Equalizer(modifier: Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "eq")
    val bars = listOf(420, 300, 520, 360).map { period ->
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period), RepeatMode.Reverse),
            label = "bar$period",
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        bars.forEach { h ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(h.value)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}
