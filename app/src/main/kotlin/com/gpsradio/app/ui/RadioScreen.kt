package com.gpsradio.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.TranscriptEntry
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.RadioUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val state by vm.radio.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val startPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startRadio()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun startRadio() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            vm.startRadio()
        } else {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            startPermissions.launch(perms.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GPS Radio")
                        val sub = listOfNotNull(state.area?.city, Languages.displayName(state.sessionLanguage)).joinToString(" · ")
                        Text(sub, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state, onMode = vm::setMode)
            NowPlaying(state)
            Controls(
                state = state,
                onStart = ::startRadio,
                onStop = vm::stopRadio,
                onPause = vm::pause,
                onResume = vm::resume,
                onSkip = vm::skip,
                onRepeat = vm::repeat,
                onNearby = vm::whatsNearby,
            )
            if (state.radioState != RadioState.IDLE) {
                TalkBar(
                    recording = recording,
                    onPressStart = {
                        if (granted(Manifest.permission.RECORD_AUDIO)) {
                            vm.startTalking(); true
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO); false
                        }
                    },
                    onRelease = vm::stopTalking,
                    onSend = vm::ask,
                )
            }
            BottomTabs(state, onTellAbout = vm::tellAbout, modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusCard(state: RadioUiState, onMode: (TravelMode?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stateLabel(state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val loc = state.location
            val detail = when {
                loc == null && state.radioState != RadioState.IDLE -> "Waiting for GPS…"
                loc == null -> "Press play to start listening"
                else -> buildString {
                    append(loc.travelMode.name.lowercase().replaceFirstChar { it.uppercase() })
                    append(" · ±${loc.accuracyM.toInt()} m")
                    if (loc.speedMps > 0.5) append(" · ${(loc.speedMps * 3.6).toInt()} km/h")
                    state.theme?.let { append(" · theme: ${it.key}") }
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val modes = listOf(null to "Auto", TravelMode.WALKING to "Walk", TravelMode.DRIVING to "Drive", TravelMode.STATIONARY to "Still")
                modes.forEach { (mode, label) ->
                    FilterChip(selected = state.modeOverride == mode, onClick = { onMode(mode) }, label = { Text(label) })
                }
            }
            if (state.discovering || state.radioState == RadioState.RESEARCHING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun stateLabel(s: RadioUiState): String = when (s.radioState) {
    RadioState.IDLE -> "Radio off"
    RadioState.RADIO -> if (s.discovering) "Looking around…" else "Listening for something worth telling"
    RadioState.RESEARCHING -> "Preparing a story…"
    RadioState.NARRATING -> "On air"
    RadioState.CONVERSING -> "Talking with you"
    RadioState.PAUSED -> "Paused"
}

@Composable
private fun NowPlaying(state: RadioUiState) {
    val seg = state.nowPlaying ?: return
    if (state.radioState != RadioState.NARRATING) return
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(seg.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(seg.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            seg.sources.firstOrNull()?.let { src ->
                Text(
                    "Source: ${src.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src.url))) },
                )
            }
        }
    }
}

@Composable
private fun Controls(
    state: RadioUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onRepeat: () -> Unit,
    onNearby: () -> Unit,
) {
    val running = state.radioState != RadioState.IDLE
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        BigButton(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, if (running) "Stop radio" else "Start radio") {
            if (running) onStop() else onStart()
        }
        if (running) {
            val paused = state.radioState == RadioState.PAUSED
            BigButton(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume" else "Pause") {
                if (paused) onResume() else onPause()
            }
            BigButton(Icons.Default.SkipNext, "Skip", onSkip)
            BigButton(Icons.Default.Replay, "Repeat", onRepeat)
            BigButton(Icons.Default.Explore, "What's nearby?", onNearby)
        }
    }
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(56.dp)) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun TalkBar(recording: Boolean, onPressStart: () -> Boolean, onRelease: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val pressStart by rememberUpdatedState(onPressStart)
    val release by rememberUpdatedState(onRelease)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .semantics { contentDescription = "Hold to ask a question" }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (pressStart()) {
                            tryAwaitRelease()
                            release()
                        }
                    })
                },
        ) {
            Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(if (recording) "Listening… release to send" else "Hold the mic, or type a question") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            trailingIcon = {
                IconButton(enabled = text.isNotBlank(), onClick = { onSend(text); text = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            },
        )
    }
}

@Composable
private fun BottomTabs(state: RadioUiState, onTellAbout: (String) -> Unit, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transcript") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Nearby (${state.nearby.size})") })
        }
        when (tab) {
            0 -> Transcript(state.transcript)
            else -> Nearby(state, onTellAbout)
        }
    }
}

@Composable
private fun Transcript(entries: List<TranscriptEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1) }
    if (entries.isEmpty()) {
        Text("Stories and answers will appear here.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        items(entries) { e ->
            val color = when (e.speaker) {
                Speaker.USER -> MaterialTheme.colorScheme.secondary
                Speaker.SYSTEM -> MaterialTheme.colorScheme.error
                Speaker.RADIO -> MaterialTheme.colorScheme.onSurface
            }
            Column {
                Text(
                    when (e.speaker) { Speaker.USER -> "You"; Speaker.RADIO -> "Radio"; Speaker.SYSTEM -> "Note" },
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Nearby(state: RadioUiState, onTellAbout: (String) -> Unit) {
    val loc = state.location
    if (state.nearby.isEmpty() || loc == null) {
        Text(
            if (state.radioState == RadioState.IDLE) "Start the radio to discover what's around you." else "Nothing found yet.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
        items(state.nearby, key = { it.place.id }) { c ->
            NearbyRow(c, direction = RadioAgent.describeDirection(c, loc), onClick = { onTellAbout(c.place.id) })
        }
    }
}

@Composable
private fun NearbyRow(c: RankedCandidate, direction: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${RadioAgent.describeDistance(c.distanceM)} $direction · ${c.place.category}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("%.1f".format(c.score), style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
}

