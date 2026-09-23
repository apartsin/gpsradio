package com.gpsradio.app.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.ui.platform.LocalUriHandler
import com.gpsradio.core.events.EventScout
import com.gpsradio.core.events.LocalEvent
import com.gpsradio.core.model.PlaceFeature
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.PhotoCamera
import com.gpsradio.core.session.DetourSuggestion
import com.gpsradio.core.session.OfferKind
import com.gpsradio.app.platform.UpdateState
import com.gpsradio.core.update.UpdateInfo
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.favorites.FavoritePlace
import com.gpsradio.core.journal.JournalEntry
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.TranscriptEntry
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.LiveState
import com.gpsradio.core.session.RadioUiState

/** Every user action on the radio screen; lets the screen be rendered and tested without a ViewModel. */
data class RadioActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onNearby: () -> Unit = {},
    val onMode: (TravelMode?) -> Unit = {},
    val onTellAbout: (String) -> Unit = {},
    val onAsk: (String) -> Unit = {},
    /** Returns true if recording started (false e.g. while the mic permission is being requested). */
    val onTalkStart: () -> Boolean = { false },
    val onTalkEnd: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onToggleStar: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onNavigate: (String) -> Unit = {},
    val onRemoveFavorite: (String) -> Unit = {},
    val onAnswerOffer: (Boolean) -> Unit = {},
    /** Natural-voice mode: tap the mic to open/close a hands-free conversation. */
    val onLiveToggle: () -> Unit = {},
    /** Always listening on/off (the mic switch in the top bar). */
    val onToggleListening: () -> Unit = {},
    /** Walking mini-tour (TourUi.kt). */
    val onStartTour: (Int) -> Unit = {},
    val onEndTour: () -> Unit = {},
    /** Trip journal in the Saved tab (JournalUi.kt). */
    val journal: JournalActions = JournalActions(),
)

@Composable
fun RadioScreen(vm: MainViewModel, onOpenSettings: () -> Unit, autoStart: Boolean = false, onAutoStarted: () -> Unit = {}) {
    val state by vm.radio.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var locationDenied by remember { mutableStateOf(false) }
    // Android 12+ lets users grant only approximate location; stories then can't be in sync with the road.
    var approximateOnly by remember {
        mutableStateOf(granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION))
    }


    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val startPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            locationDenied = false
            approximateOnly = result[Manifest.permission.ACCESS_FINE_LOCATION] != true
            vm.startRadio()
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            locationDenied = true
        }
    }
    var micGranted by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
        // Re-start the running service so it gains the microphone type (hands-free with the screen off).
        if (ok && state.radioState != com.gpsradio.core.model.RadioState.IDLE) vm.startRadio()
    }

    val start = {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            vm.startRadio()
        } else {
            // Activity recognition (API 29+ runtime permission) is asked together with location; it only sharpens mode detection.
            val activity = if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray<String>()
            startPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) + activity)
        }
    }
    LaunchedEffect(autoStart) {
        if (autoStart) {
            onAutoStarted()
            start()
        }
    }

    val actions = RadioActions(
        onStart = start,
        onStop = vm::stopRadio,
        onPause = vm::pause,
        onResume = vm::resume,
        onSkip = vm::skip,
        onRepeat = vm::repeat,
        onNearby = vm::whatsNearby,
        onMode = vm::setMode,
        onTellAbout = vm::tellAbout,
        onAsk = vm::ask,
        onTalkStart = {
            if (granted(Manifest.permission.RECORD_AUDIO)) {
                vm.startTalking()
                true
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                false
            }
        },
        onTalkEnd = vm::stopTalking,
        onOpenSettings = onOpenSettings,
        onToggleStar = vm::toggleFavorite,
        onShare = { id -> vm.shareIntent(id)?.let { context.startActivity(it) } },
        onNavigate = { id -> vm.navigateIntent(id)?.let { runCatching { context.startActivity(it) } } },
        onRemoveFavorite = vm::removeFavorite,
        onAnswerOffer = vm::answerOffer,
        onLiveToggle = {
            if (granted(Manifest.permission.RECORD_AUDIO)) vm.toggleLive() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onToggleListening = { vm.saveSettings { it.copy(alwaysListening = !it.alwaysListening) } },
        onStartTour = vm::startTour,
        onEndTour = vm::endTour,
        journal = JournalActions(
            onRetell = vm::retell,
            onShare = { e -> context.startActivity(vm.journalShareIntent(e)) },
            onExportDay = { day -> vm.journalGpxIntent(day)?.let { context.startActivity(it) } },
        ),
    )
    RadioContent(
        state = state,
        recording = recording,
        actions = actions,
        // Natural voice needs the mic; until it's granted, the mic falls back to hold-to-talk (which asks for it).
        liveMode = settings.liveVoice && micGranted,
        alwaysListening = settings.alwaysListening,
        update = update,
        onInstallUpdate = vm::installUpdate,
        onAllowInstalls = vm::allowInstalls,
        locationDenied = locationDenied,
        approximateOnly = approximateOnly,
        onRequestPrecise = {
            startPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onOpenAppSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioContent(
    state: RadioUiState,
    recording: Boolean,
    actions: RadioActions,
    /** The photo + map panel; tests replace it because MapView needs a real device. */
    placePanel: @Composable (RadioUiState, Modifier) -> Unit = { s, m -> PlacePanel(s, m) },
    locationDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
    approximateOnly: Boolean = false,
    onRequestPrecise: () -> Unit = {},
    /** Natural voice: the mic is tap-to-talk hands-free instead of hold-to-talk. */
    liveMode: Boolean = false,
    update: UpdateState = UpdateState.Idle,
    onInstallUpdate: (UpdateInfo) -> Unit = {},
    onAllowInstalls: () -> Unit = {},
    /** Always listening is switched on (only meaningful with [liveMode]). */
    alwaysListening: Boolean = false,
) {
    val running = state.radioState != RadioState.IDLE
    val driving = (state.modeOverride ?: state.location?.travelMode) == TravelMode.DRIVING
    val starredIds = remember(state.favorites) { state.favorites.map { it.id }.toSet() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GPS Radio", fontWeight = FontWeight.Bold)
                        val sub = listOfNotNull(
                            state.area?.city,
                            Languages.displayName(state.sessionLanguage),
                            "listening".takeIf { state.listening && alwaysListening && liveMode },
                        ).joinToString(" · ")
                        Text(sub, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    // The mic switch: always listening on/off, reachable in one tap (also while driving).
                    if (liveMode && running) {
                        IconButton(onClick = actions.onToggleListening, modifier = Modifier.testTag("micSwitch")) {
                            Icon(
                                if (alwaysListening) Icons.Default.Mic else Icons.Default.MicOff,
                                if (alwaysListening) "Turn microphone off" else "Turn microphone on",
                                tint = if (alwaysListening && state.listening) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
                    if (running) TextButton(onClick = actions.onStop) { Text("Stop") }
                    IconButton(onClick = actions.onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusCard(state, onMode = actions.onMode, onFixKey = actions.onOpenSettings)
            if (locationDenied) PermissionCard(onOpenAppSettings)
            if (approximateOnly && running) PreciseLocationCard(onRequestPrecise)
            // Never distract the driver with an update prompt.
            if (!driving) UpdateBanner(update, onInstallUpdate, onAllowInstalls)
            state.pendingOffer?.let { OfferCard(it, state.pendingOfferKind, actions.onAnswerOffer) }
            if (!running) {
                IdleContent(state, actions, starredIds, Modifier.weight(1f))
            } else if (driving) {
                DrivingContent(state, recording, actions, starredIds, liveMode, Modifier.weight(1f))
            } else {
                ContentTabs(state, starredIds, actions, placePanel, Modifier.weight(1f))
                Controls(state, actions)
                TalkBar(recording, actions, liveMode, state)
            }
        }
    }
}

// ---- idle ----------------------------------------------------------------------------------

@Composable
private fun IdleContent(state: RadioUiState, actions: RadioActions, starredIds: Set<String>, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.favorites.isNotEmpty() || state.journal.isNotEmpty()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Listen") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Saved (${state.favorites.size})") })
            }
        }
        if (tab == 1 && (state.favorites.isNotEmpty() || state.journal.isNotEmpty())) {
            Saved(state.favorites, actions, state.journal)
            return@Column
        }
        Spacer(Modifier.weight(1f))
        FilledIconButton(
            onClick = actions.onStart,
            modifier = Modifier.size(112.dp),
            shape = CircleShape,
        ) { Icon(Icons.Default.PlayArrow, "Start radio", Modifier.size(64.dp)) }
        Spacer(Modifier.size(12.dp))
        Text("Start listening", style = MaterialTheme.typography.titleMedium)
        Text(
            "Stories about the places around you, told as you go.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        if (starredIds.isEmpty()) {
            Text(
                "Tip: tap ☆ on any story to save it for later.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun PermissionCard(onOpenAppSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("GPS Radio needs your location to find stories around you.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenAppSettings) { Text("Allow location") }
        }
    }
}

@Composable
private fun PreciseLocationCard(onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "You shared only your approximate location, so stories can drift out of sync with what you pass. " +
                    "Allow precise location for stories that match the road.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRequest) { Text("Use precise location") }
        }
    }
}

@Composable
private fun OfferCard(placeName: String, kind: OfferKind, onAnswer: (Boolean) -> Unit) {
    val detour = kind == OfferKind.DETOUR
    Card(Modifier.fillMaxWidth().testTag("offerCard")) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (detour) "Take a short detour to $placeName?" else "Want the full story about $placeName?",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("Just say \"yes\" or \"not now\" — or tap.", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { onAnswer(true) }) { Text(if (detour) "Navigate there" else "Yes, tell it") }
                OutlinedButton(onClick = { onAnswer(false) }) { Text("Not now") }
            }
        }
    }
}

// ---- driving -------------------------------------------------------------------------------

/** Big, glanceable, voice-first layout: photo, place name, a large mic, pause and skip. */
@Composable
private fun DrivingContent(
    state: RadioUiState,
    recording: Boolean,
    a: RadioActions,
    starredIds: Set<String>,
    liveMode: Boolean,
    modifier: Modifier,
) {
    val focus = state.focus
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (focus?.imageUrl != null) {
            AsyncImage(
                model = focus.imageUrl,
                contentDescription = "Photo of ${focus.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(MaterialTheme.shapes.large),
            )
        }
        Text(
            focus?.name ?: stateLabel(state),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (focus != null) {
            Row {
                IconButton(onClick = { a.onToggleStar(focus.id) }, modifier = Modifier.size(64.dp)) {
                    Icon(if (focus.id in starredIds) Icons.Default.Star else Icons.Default.StarBorder, "Save place", Modifier.size(36.dp))
                }
                IconButton(onClick = { a.onNavigate(focus.id) }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Directions, "Navigate there", Modifier.size(36.dp))
                }
            }
        }
        if (state.pendingOffer == null && state.detours.isNotEmpty()) DetourCard(state.detours.first(), a)
        Spacer(Modifier.weight(1f))
        MicButton(recording, a, size = 104, liveMode = liveMode, live = state.live)
        Text(micHint(recording, liveMode, state.live), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            PauseOrBack(state, a, size = 72)
            BigButton(Icons.Default.SkipNext, "Skip", a.onSkip, size = 72)
        }
    }
}

// ---- tabs ----------------------------------------------------------------------------------

@Composable
private fun ContentTabs(
    state: RadioUiState,
    starredIds: Set<String>,
    a: RadioActions,
    placePanel: @Composable (RadioUiState, Modifier) -> Unit,
    modifier: Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Now") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Transcript") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Nearby") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Saved") })
        }
        when (tab) {
            0 -> BoxWithConstraints(Modifier.fillMaxSize().padding(top = 8.dp)) {
                // Photo/map height adapts to the screen so the title, actions and story stay visible on small phones.
                val panelHeight = (maxHeight * 0.45f).coerceIn(110.dp, 240.dp)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.tour?.let { TourBanner(it, state.location, a.onEndTour) }
                    FocusActions(state, starredIds, a)
                    // The panel is not inside a scroll container, so the map can be panned freely.
                    placePanel(state, Modifier.height(panelHeight))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { NowPlaying(state) }
                }
            }
            1 -> Transcript(state.transcript)
            2 -> Column {
                TourChips(state.tour, a.onStartTour)
                Nearby(state, starredIds, a)
            }
            else -> Saved(state.favorites, a, state.journal, canRetell = true)
        }
    }
}

@Composable
private fun FocusActions(state: RadioUiState, starredIds: Set<String>, a: RadioActions) {
    val focus = state.focus ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            focus.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val starred = focus.id in starredIds
        IconButton(onClick = { a.onToggleStar(focus.id) }) {
            Icon(
                if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                if (starred) "Remove from saved" else "Save place",
                tint = if (starred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { a.onShare(focus.id) }) { Icon(Icons.Default.Share, "Share place") }
        IconButton(onClick = { a.onNavigate(focus.id) }) { Icon(Icons.Default.Directions, "Navigate there") }
    }
}

/** What is being said right now: the story on air, or the latest answer during a conversation. */
@Composable
private fun NowPlaying(state: RadioUiState) {
    val context = LocalContext.current
    val seg = state.nowPlaying?.takeIf { state.radioState == RadioState.NARRATING }
    val reply = state.transcript.lastOrNull()?.takeIf { state.radioState == RadioState.CONVERSING && it.speaker == Speaker.RADIO }
    val title = seg?.let { "On air" } ?: reply?.let { "Answer" }
    if (title == null) {
        Text(
            when (state.radioState) {
                RadioState.RESEARCHING -> "Tuning in to the next story…"
                RadioState.PAUSED -> "Paused. Press play to continue."
                else -> "Scanning for stories around you. Ask anything with the mic."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp),
        )
        return
    }
    val text = seg?.text ?: reply?.text.orEmpty()
    val sources = seg?.sources ?: reply?.sources.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            // "Why this story?" in one line, e.g. "Close by (200 m) · matches your interest in history".
            if (seg != null) state.nowPlayingReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            // How well-founded the story is: "Documented" / "Includes legend".
            seg?.basis?.let { basis ->
                Text(
                    basis.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            sources.take(3).forEach { src ->
                Text(
                    "Source: ${src.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src.url))) },
                )
            }
        }
    }
}

// ---- controls ------------------------------------------------------------------------------

@Composable
private fun Controls(state: RadioUiState, a: RadioActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        BigButton(Icons.Default.Replay, "Repeat", a.onRepeat)
        PauseOrBack(state, a, size = 64)
        BigButton(Icons.Default.SkipNext, "Skip", a.onSkip)
        BigButton(Icons.Default.Explore, "Nearby?", a.onNearby)
    }
}

/** Primary control: pause/resume, or "Back to radio" while in a conversation. */
@Composable
private fun PauseOrBack(state: RadioUiState, a: RadioActions, size: Int) {
    when (state.radioState) {
        RadioState.PAUSED -> BigButton(Icons.Default.PlayArrow, "Resume", a.onResume, size = size, primary = true)
        RadioState.CONVERSING -> BigButton(Icons.Default.Radio, "Back to radio", a.onResume, size = size, primary = true)
        else -> BigButton(Icons.Default.Pause, "Pause", a.onPause, size = size, primary = true)
    }
}

@Composable
private fun BigButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Int = 56,
    primary: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (primary) {
            FilledIconButton(onClick = onClick, modifier = Modifier.size(size.dp)) { Icon(icon, label, Modifier.size((size / 2).dp)) }
        } else {
            FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(size.dp)) { Icon(icon, label) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

fun micHint(recording: Boolean, liveMode: Boolean, live: LiveState?): String = when {
    live == LiveState.CONNECTING -> "Connecting…"
    live == LiveState.USER_SPEAKING -> "I'm listening…"
    live == LiveState.ASSISTANT_SPEAKING -> "Talk anytime to interrupt"
    live == LiveState.LISTENING -> "Just talk · tap to end"
    liveMode -> "Tap to talk"
    recording -> "Listening… release to send"
    else -> "Hold to talk"
}

@Composable
private fun MicButton(recording: Boolean, a: RadioActions, size: Int, liveMode: Boolean = false, live: LiveState? = null) {
    val haptics = LocalHapticFeedback.current
    val pressStart by rememberUpdatedState(a.onTalkStart)
    val release by rememberUpdatedState(a.onTalkEnd)
    val active = recording || live != null
    val base = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    val gesture = if (liveMode) {
        base
            .semantics {
                contentDescription = if (live != null) "End voice conversation" else "Talk to the radio"
                role = Role.Button
                stateDescription = live?.name?.lowercase()?.replace('_', ' ') ?: "Idle"
            }
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                a.onLiveToggle()
            }
    } else {
        base
            .semantics {
                contentDescription = "Hold to ask a question"
                role = Role.Button
                stateDescription = if (recording) "Recording" else "Idle"
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (pressStart()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        release()
                    }
                })
            }
    }
    Box(contentAlignment = Alignment.Center, modifier = gesture) {
        if (active) Equalizer(Modifier.size((size / 2).dp), color = MaterialTheme.colorScheme.onError)
        else Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
private fun TalkBar(recording: Boolean, a: RadioActions, liveMode: Boolean, state: RadioUiState) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val send = {
        if (text.isNotBlank()) {
            a.onAsk(text)
            text = ""
            keyboard?.hide()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        MicButton(recording, a, size = 64, liveMode = liveMode, live = state.live)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(if (recording || state.live != null) micHint(recording, liveMode, state.live) else "Ask anything…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).testTag("askField"),
            trailingIcon = {
                IconButton(enabled = text.isNotBlank(), onClick = send) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            },
        )
    }
}

// ---- lists ---------------------------------------------------------------------------------

@Composable
private fun Transcript(entries: List<TranscriptEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1) }
    if (entries.isEmpty()) {
        Text("Your stories and questions will show up here as captions.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp).testTag("transcript"),
    ) {
        items(entries) { e ->
            val (who, color) = when (e.speaker) {
                Speaker.USER -> "You" to MaterialTheme.colorScheme.secondary
                Speaker.SYSTEM -> "Note" to MaterialTheme.colorScheme.error
                Speaker.RADIO -> "Radio" to MaterialTheme.colorScheme.primary
            }
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(who, style = MaterialTheme.typography.labelSmall, color = color)
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Nearby(state: RadioUiState, starredIds: Set<String>, a: RadioActions) {
    val loc = state.location
    if (state.nearby.isEmpty() || loc == null) {
        Text("Scanning around you…", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
        if (state.todayEvents.isNotEmpty()) {
            item(key = "events-header") {
                Text("Today nearby", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            items(state.todayEvents, key = { "event:" + it.id }) { e -> EventRow(e) }
            item(key = "places-header") {
                Text("Places", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
        }
        items(state.nearby, key = { it.place.id }) { c ->
            NearbyRow(
                c, RadioAgent.describeDirection(c, loc), c.place.id in starredIds, a,
                photoSpot = c.place.id in state.photoSpotIds,
                detour = state.detours.firstOrNull { it.placeId == c.place.id },
            )
        }
    }
}

@Composable
private fun NearbyRow(
    c: RankedCandidate,
    direction: String,
    starred: Boolean,
    a: RadioActions,
    photoSpot: Boolean = false,
    detour: DetourSuggestion? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Tell me about ${c.place.name}") { a.onTellAbout(c.place.id) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (listOfNotNull("${RadioAgent.describeDistance(c.distanceM)} $direction", detour?.label) + c.place.features.map(::featureLabel))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (photoSpot) Icon(Icons.Default.PhotoCamera, "Photo spot", Modifier.padding(horizontal = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
        if (detour != null) {
            IconButton(onClick = { a.onNavigate(c.place.id) }) { Icon(Icons.Default.Directions, "Navigate to ${c.place.name}") }
        }
        IconButton(onClick = { a.onToggleStar(c.place.id) }) {
            Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder, if (starred) "Remove ${c.place.name} from saved" else "Save ${c.place.name}")
        }
    }
}

/** Driving: the best drive-by detour ahead, with one big button to hand off navigation. */
@Composable
private fun DetourCard(d: DetourSuggestion, a: RadioActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("detourCard"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Detour ahead", style = MaterialTheme.typography.labelLarge)
                Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.label, style = MaterialTheme.typography.bodyMedium)
                d.visit?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Button(onClick = { a.onNavigate(d.placeId) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Default.Directions, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Navigate")
            }
        }
    }
}

@Composable
private fun Saved(favorites: List<FavoritePlace>, a: RadioActions, journal: List<JournalEntry> = emptyList(), canRetell: Boolean = false) {
    if (favorites.isEmpty() && journal.isEmpty()) {
        Text("Tap ☆ on a story or a nearby place to save it here.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(modifier = Modifier.padding(top = 8.dp).testTag("saved"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(favorites, key = { it.id }) { f ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp)) {
                    if (f.imageUrl != null) {
                        AsyncImage(
                            model = f.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        f.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Row {
                            IconButton(onClick = { a.onShare(f.id) }) { Icon(Icons.Default.Share, "Share ${f.name}") }
                            IconButton(onClick = { a.onNavigate(f.id) }) { Icon(Icons.Default.Directions, "Navigate to ${f.name}") }
                            IconButton(onClick = { a.onRemoveFavorite(f.id) }) { Icon(Icons.Default.Delete, "Remove ${f.name}") }
                        }
                    }
                }
            }
        }
        journalItems(journal, canRetell, a.journal)
    }
}

/** Short Nearby-list tag for what makes a place special (spec A §29). */
fun featureLabel(f: PlaceFeature): String = when (f) {
    PlaceFeature.FILM_LOCATION -> "🎬 filmed here"
    PlaceFeature.HISTORIC_EVENT -> "📜 happened here"
    PlaceFeature.EAT_DRINK -> "🍽 eat & drink"
    PlaceFeature.SHOP -> "🛍 shop"
    PlaceFeature.JEWISH_HERITAGE -> "✡ Jewish heritage"
}

/** An event today nearby; tapping opens its source page (tickets, programme). */
@Composable
private fun EventRow(e: LocalEvent) {
    val uri = LocalUriHandler.current
    val now = System.currentTimeMillis()
    val time = if (e.startMs <= now) "Now" else EventScout.clock(e.startMs, java.time.ZoneId.systemDefault())
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Open ${e.title}") { runCatching { uri.openUri(e.url) } }
            .padding(vertical = 6.dp)
            .testTag("event"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(e.venue.ifBlank { null }, e.distanceKm?.let { "%.1f km".format(it) }, e.why.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
