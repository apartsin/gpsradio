package com.gpsradio.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.gpsradio.core.session.StatusLevel
import kotlinx.coroutines.launch
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
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.gpsradio.app.R

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
        // Granted from the mic switch: open the mic right away.
        if (ok) vm.saveSettings { it.copy(liveVoice = true, alwaysListening = true) }
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
        // The mic switch: open = natural voice + always listening; asks for the mic permission first.
        onToggleListening = {
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                vm.saveSettings { if (it.liveVoice && it.alwaysListening) it.copy(alwaysListening = false) else it.copy(liveVoice = true, alwaysListening = true) }
            }
        },
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

/** Secondary pages reached from the menu (the main screen itself shows only the essentials). */
enum class RadioPage(@StringRes val title: Int) { NEARBY(R.string.page_nearby), SAVED(R.string.page_saved), TRANSCRIPT(R.string.page_transcript) }

/**
 * The main screen, voice-first and deliberately minimal (spec A §36): the photo/map of what's on air, and two big
 * controls, radio on/off and microphone open/closed. Everything else lives behind the menu: travel mode, Nearby,
 * Saved & journal, Transcript, updates and Settings. Pause, skip, "tell me more" and answers to offers are by
 * voice (or the notification, lock screen and headset buttons).
 */
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
    /** Natural voice is available (setting on and the mic permission granted). */
    liveMode: Boolean = false,
    update: UpdateState = UpdateState.Idle,
    onInstallUpdate: (UpdateInfo) -> Unit = {},
    onAllowInstalls: () -> Unit = {},
    /** Always listening is switched on: with [liveMode], the mic is open. */
    alwaysListening: Boolean = false,
    /** Opens this secondary page right away (tests). */
    initialPage: RadioPage? = null,
    /** Starts with the menu open (tests). */
    menuOpen: Boolean = false,
) {
    val running = state.radioState != RadioState.IDLE
    val micOpen = liveMode && alwaysListening
    val starredIds = remember(state.favorites) { state.favorites.map { it.id }.toSet() }
    val drawer = rememberDrawerState(if (menuOpen) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(initialPage) }
    val closeMenu = { scope.launch { drawer.close() } }

    BackHandler(enabled = page != null) { page = null }
    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                RadioMenu(
                    state = state,
                    update = update,
                        onPage = { page = it; closeMenu() },
                    onSettings = { closeMenu(); actions.onOpenSettings() },
                    onInstallUpdate = onInstallUpdate,
                    onAllowInstalls = onAllowInstalls,
                )
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.testTag("menuButton")) {
                                Icon(Icons.Default.Menu, stringResource(R.string.menu))
                            }
                        },
                        title = {
                            Column {
                                Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                                val mode = (state.modeOverride ?: state.location?.travelMode)?.takeIf { running && it != TravelMode.UNKNOWN }
                                val sub = listOfNotNull(
                                    state.area?.city,
                                    Languages.displayName(state.sessionLanguage),
                                    mode?.let { stringResource(modeInfo(it).title) },
                                ).joinToString(" · ")
                                Text(sub, style = MaterialTheme.typography.labelMedium)
                            }
                        },
                    )
                },
            ) { pad ->
                Column(
                    Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (locationDenied) PermissionCard(onOpenAppSettings)
                    if (approximateOnly && running) PreciseLocationCard(onRequestPrecise)
                    // The image/map area: what's on air, or where you are.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (running) {
                            placePanel(state, Modifier.fillMaxSize())
                        } else {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Radio, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    stringResource(R.string.idle_tagline),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                    NowLine(state, micOpen, onFixKey = actions.onOpenSettings)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        RadioOnOffButton(running, onStart = actions.onStart, onStop = actions.onStop)
                        MicSwitch(micOpen, running, state.live, recording, onToggle = actions.onToggleListening)
                    }
                }
            }
        }
        page?.let { p ->
            RadioPageScreen(p, onClose = { page = null }) {
                when (p) {
                    RadioPage.NEARBY -> Column {
                        state.tour?.let { TourBanner(it, state.location, actions.onEndTour) }
                        TourChips(state.tour, actions.onStartTour)
                        Nearby(state, starredIds, actions)
                    }
                    RadioPage.SAVED -> Saved(state.favorites, actions, state.journal, canRetell = running)
                    RadioPage.TRANSCRIPT -> Transcript(state.transcript)
                }
            }
        }
    }
}

/** The place on air (or the radio's state) and one line of status. */
@Composable
private fun NowLine(state: RadioUiState, micOpen: Boolean, onFixKey: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.radioState == RadioState.NARRATING) OnAirBadge()
            Text(
                state.focus?.name?.takeIf { state.radioState != RadioState.IDLE } ?: stateLabel(state),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val status = state.status
        val openSettings = stringResource(R.string.open_settings)
        val line = when {
            status != null -> status.text
            state.radioState == RadioState.IDLE -> null
            state.focus != null -> stateLabel(state) + (state.live?.let { " · " + micHint(false, true, it) } ?: "")
            micOpen && state.live != null -> micHint(false, true, state.live)
            else -> null
        }
        line?.let {
            val color = when (status?.level) {
                StatusLevel.ERROR -> MaterialTheme.colorScheme.error
                StatusLevel.WORKING -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = if (status?.needsKey == true) Modifier.clickable(onClickLabel = status?.actionLabel ?: openSettings, onClick = onFixKey) else Modifier,
            )
            if (status?.needsKey == true) {
                Text(
                    status?.actionLabel ?: openSettings,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onFixKey).padding(4.dp),
                )
            }
        }
    }
}

/** Radio on/off: the one big play/stop button. */
@Composable
private fun RadioOnOffButton(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = if (running) onStop else onStart,
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
        ) {
            Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, stringResource(if (running) R.string.stop_radio else R.string.start_radio), Modifier.size(56.dp))
        }
        Text(stringResource(if (running) R.string.stop else R.string.start), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Microphone open/closed: when open, just talk to the radio at any time (always listening). */
@Composable
private fun MicSwitch(open: Boolean, running: Boolean, live: LiveState?, recording: Boolean, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val active = open && running && (live == LiveState.USER_SPEAKING || live == LiveState.ASSISTANT_SPEAKING) || recording
    val micDescription = stringResource(if (open) R.string.mic_turn_off else R.string.mic_turn_on)
    val micState = stringResource(if (open) R.string.mic_state_open else R.string.mic_state_closed)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val colors = if (open) {
            IconButtonDefaults.filledIconButtonColors()
        } else {
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledIconButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() },
            modifier = Modifier.size(96.dp).testTag("micSwitch").semantics {
                contentDescription = micDescription
                stateDescription = micState
            },
            shape = CircleShape,
            colors = colors,
        ) {
            if (active) {
                Equalizer(Modifier.size(44.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(if (open) Icons.Default.Mic else Icons.Default.MicOff, null, Modifier.size(48.dp))
            }
        }
        Text(stringResource(if (open) R.string.mic_on else R.string.mic_off), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

/** What the live voice is doing, in a few words (shown under the place name). */
@Composable
fun micHint(recording: Boolean, liveMode: Boolean, live: LiveState?): String = stringResource(
    when {
        live == LiveState.CONNECTING -> R.string.hint_connecting
        live == LiveState.USER_SPEAKING -> R.string.hint_listening
        live == LiveState.ASSISTANT_SPEAKING -> R.string.hint_interrupt
        live == LiveState.LISTENING -> R.string.hint_just_talk
        liveMode -> R.string.hint_tap_to_talk
        recording -> R.string.hint_release_to_send
        else -> R.string.hint_hold_to_talk
    },
)

/** The menu: the secondary pages, an available update, and Settings. */
@Composable
private fun RadioMenu(
    state: RadioUiState,
    update: UpdateState,
    onPage: (RadioPage) -> Unit,
    onSettings: () -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onAllowInstalls: () -> Unit,
) {
    ModalDrawerSheet(Modifier.testTag("menu")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            // The travel mode is inferred automatically (speed, activity recognition); it shows in the top bar.
            NavigationDrawerItem(
                label = { Text(stringResource(RadioPage.NEARBY.title)) }, icon = { Icon(Icons.Default.Explore, null) }, selected = false,
                onClick = { onPage(RadioPage.NEARBY) }, modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(RadioPage.SAVED.title)) }, icon = { Icon(Icons.Default.Star, null) }, selected = false,
                badge = { if (state.favorites.isNotEmpty()) Text("${state.favorites.size}") },
                onClick = { onPage(RadioPage.SAVED) }, modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(RadioPage.TRANSCRIPT.title)) }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, selected = false,
                onClick = { onPage(RadioPage.TRANSCRIPT) }, modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Box(Modifier.padding(horizontal = 16.dp)) { UpdateBanner(update, onInstallUpdate, onAllowInstalls) }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings)) }, icon = { Icon(Icons.Default.Settings, null) }, selected = false,
                onClick = onSettings, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** A secondary page over the main screen, with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioPageScreen(page: RadioPage, onClose: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun PermissionCard(onOpenAppSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.location_needed), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.allow_location)) }
        }
    }
}

@Composable
private fun PreciseLocationCard(onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.approximate_location),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRequest) { Text(stringResource(R.string.use_precise_location)) }
        }
    }
}

// ---- lists ---------------------------------------------------------------------------------

@Composable
private fun Transcript(entries: List<TranscriptEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1) }
    if (entries.isEmpty()) {
        Text(stringResource(R.string.transcript_empty), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp).testTag("transcript"),
    ) {
        items(entries) { e ->
            val (who, color) = when (e.speaker) {
                Speaker.USER -> stringResource(R.string.speaker_you) to MaterialTheme.colorScheme.secondary
                Speaker.SYSTEM -> stringResource(R.string.speaker_note) to MaterialTheme.colorScheme.error
                Speaker.RADIO -> stringResource(R.string.speaker_radio) to MaterialTheme.colorScheme.primary
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
        Text(stringResource(R.string.nearby_scanning), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
        if (state.todayEvents.isNotEmpty()) {
            item(key = "events-header") {
                Text(stringResource(R.string.today_nearby), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            items(state.todayEvents, key = { "event:" + it.id }) { e -> EventRow(e) }
            item(key = "places-header") {
                Text(stringResource(R.string.places), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
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
            .clickable(onClickLabel = stringResource(R.string.tell_me_about, c.place.name)) { a.onTellAbout(c.place.id) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (listOfNotNull("${RadioAgent.describeDistance(c.distanceM)} $direction", detour?.label) + c.place.features.map { featureLabel(it) })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (photoSpot) Icon(Icons.Default.PhotoCamera, stringResource(R.string.photo_spot), Modifier.padding(horizontal = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
        if (detour != null) {
            IconButton(onClick = { a.onNavigate(c.place.id) }) { Icon(Icons.Default.Directions, stringResource(R.string.navigate_to, c.place.name)) }
        }
        IconButton(onClick = { a.onToggleStar(c.place.id) }) {
            Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder, stringResource(if (starred) R.string.unsave_place else R.string.save_place, c.place.name))
        }
    }
}

@Composable
private fun Saved(favorites: List<FavoritePlace>, a: RadioActions, journal: List<JournalEntry> = emptyList(), canRetell: Boolean = false) {
    if (favorites.isEmpty() && journal.isEmpty()) {
        Text(stringResource(R.string.saved_empty), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
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
                            IconButton(onClick = { a.onShare(f.id) }) { Icon(Icons.Default.Share, stringResource(R.string.share_item, f.name)) }
                            IconButton(onClick = { a.onNavigate(f.id) }) { Icon(Icons.Default.Directions, stringResource(R.string.navigate_to, f.name)) }
                            IconButton(onClick = { a.onRemoveFavorite(f.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.remove_item, f.name)) }
                        }
                    }
                }
            }
        }
        journalItems(journal, canRetell, a.journal)
    }
}

/** Short Nearby-list tag for what makes a place special (spec A §29). */
@Composable
fun featureLabel(f: PlaceFeature): String = stringResource(
    when (f) {
        PlaceFeature.FILM_LOCATION -> R.string.feature_film
        PlaceFeature.HISTORIC_EVENT -> R.string.feature_historic
        PlaceFeature.EAT_DRINK -> R.string.feature_eat_drink
        PlaceFeature.SHOP -> R.string.feature_shop
        PlaceFeature.JEWISH_HERITAGE -> R.string.feature_jewish
    },
)

/** An event today nearby; tapping opens its source page (tickets, programme). */
@Composable
private fun EventRow(e: LocalEvent) {
    val uri = LocalUriHandler.current
    val now = System.currentTimeMillis()
    val time = if (e.startMs <= now) stringResource(R.string.events_now) else EventScout.clock(e.startMs, java.time.ZoneId.systemDefault())
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.open_item, e.title)) { runCatching { uri.openUri(e.url) } }
            .padding(vertical = 6.dp)
            .testTag("event"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(e.venue.ifBlank { null }, e.distanceKm?.let { stringResource(R.string.distance_km, it) }, e.why.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
