package com.gpsradio.core.session

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.favorites.FavoritePlace
import com.gpsradio.core.favorites.Favorites
import com.gpsradio.core.favorites.FavoritesStore
import com.gpsradio.core.ai.NotInListenerLanguageException
import com.gpsradio.core.ai.OpenAiException
import com.gpsradio.core.ai.QuotaErrors
import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.ConversationTurn
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.CorridorDiscovery
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.editorial.InterestModel
import com.gpsradio.core.editorial.InterestStore
import com.gpsradio.core.editorial.StoryReason
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.memory.MemoryCategory
import com.gpsradio.core.memory.MemoryItem
import com.gpsradio.core.memory.MemoryStore
import com.gpsradio.core.memory.UserMemory
import com.gpsradio.core.location.AreaRefreshPolicy
import com.gpsradio.core.location.LocationProcessor
import com.gpsradio.core.model.ActivityType
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.forInterests
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TranscriptEntry
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.journal.Journal
import com.gpsradio.core.journal.JournalEntry
import com.gpsradio.core.journal.JournalStore
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.tour.TourPlanner
import com.gpsradio.core.tour.TourState
import com.gpsradio.core.tour.TourStop
import com.gpsradio.core.tour.TourText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.concurrent.TimeoutException
import com.gpsradio.core.ai.FillerRequest
import com.gpsradio.core.ai.QuizQuestion
import com.gpsradio.core.discovery.AreaFacet
import com.gpsradio.core.discovery.AreaFacts
import com.gpsradio.core.discovery.AreaInfoSource
import com.gpsradio.core.discovery.OnThisDayClient
import com.gpsradio.core.discovery.OnThisDaySource
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.lang.Notices
import com.gpsradio.core.editorial.PlaceMentions
import com.gpsradio.core.lang.SourceLines
import com.gpsradio.core.lang.Notice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import com.gpsradio.core.visit.Visits
import com.gpsradio.core.visit.VisitSource
import com.gpsradio.core.visit.VisitInfo
import com.gpsradio.core.events.LocalEvent
import com.gpsradio.core.events.EventSource
import com.gpsradio.core.model.RoadTripKind
import com.gpsradio.core.editorial.PhotoSpots
import com.gpsradio.core.editorial.Detours
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The place currently being described; drives the photo + map panel. */
data class FocusPlace(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val imageUrl: String?,
    val url: String?,
    /** More real photos of the place, loaded after it comes into focus. */
    val gallery: List<String> = listOfNotNull(imageUrl),
    /** Author and licence per photo URL (Wikimedia Commons), shown under the photo. */
    val credits: Map<String, String> = emptyMap(),
    /** What a slideshow photo shows (a person, building or view the story names), per photo URL (spec A §55). */
    val captions: Map<String, String> = emptyMap(),
    /** When to show a photo (epoch ms): as its mention is spoken (spec A §62). Photos without a time just rotate. */
    val timeline: Map<String, Long> = emptyMap(),
) {
    companion object {
        fun of(p: PlaceCandidate) = FocusPlace(p.id, p.name, p.point, p.imageUrl, p.url)
    }
}

enum class StatusLevel { INFO, WORKING, ERROR }

/** A user-facing status line. [needsKey] marks errors fixed by entering a valid API key. */
data class Status(
    val text: String,
    val level: StatusLevel,
    val needsKey: Boolean = false,
    /** Label for the key action (default "Open Settings"), e.g. "Add key" in preview mode. */
    val actionLabel: String? = null,
)

/** What the pending offer leads to on "yes": the full story, or directions to the place. */
enum class OfferKind { STORY, DETOUR }

/** A drive-by detour off the road ahead (spec A §28). */
data class DetourSuggestion(
    val placeId: String,
    val name: String,
    val minutes: Int,
    /** e.g. "open 10:00–17:00 · adults €8 · ~45 min visit · easy walk", in the listener's language (spec A §31). */
    val visit: String? = null,
) {
    val label: String get() = Detours.label(minutes)
}

data class RadioUiState(
    val radioState: RadioState = RadioState.IDLE,
    val location: LocationContext? = null,
    val modeOverride: TravelMode? = null,
    val sessionLanguage: String = Languages.FALLBACK,
    val theme: Topic? = null,
    val area: AreaLabel? = null,
    val nowPlaying: Segment? = null,
    /** "Why this story?": e.g. "Close by (200 m) · matches your interest in history · well documented". */
    val nowPlayingReason: String? = null,
    val focus: FocusPlace? = null,
    val memory: List<MemoryItem> = emptyList(),
    val favorites: List<FavoritePlace> = emptyList(),
    /** Name of a story the radio offered ("want to hear it?") and is waiting for an answer about. */
    val pendingOffer: String? = null,
    val pendingOfferKind: OfferKind = OfferKind.STORY,
    /** While driving: worth-a-stop places a few minutes off the road ahead, best first. */
    val detours: List<DetourSuggestion> = emptyList(),
    /** Nearby places that make a good photo (viewpoints, waterfalls, castles…). */
    val photoSpotIds: Set<String> = emptySet(),
    val tripContext: String? = null,
    /** Non-null while a live voice conversation is open. */
    val live: LiveState? = null,
    val nearby: List<RankedCandidate> = emptyList(),
    val transcript: List<TranscriptEntry> = emptyList(),
    val discovering: Boolean = false,
    /** The listener is waiting for the next story (after "next", a steer, the first story): show that it's working. */
    val waiting: Boolean = false,
    val status: Status? = null,
    /** Non-null while a walking mini-tour is active. */
    val tour: TourState? = null,
    /** Stories heard to the end, newest first (trip journal). */
    val journal: List<JournalEntry> = emptyList(),
    /** The OpenAI key is out of credit (billing limit reached): the listener must top up or add a key. */
    val quotaExhausted: Boolean = false,
    /** Public events today nearby, soonest first (spec A §30). */
    val todayEvents: List<LocalEvent> = emptyList(),
    /** The mic is open and listening in the background (always-listening mode, spec A §33). */
    val listening: Boolean = false,
)

/**
 * Session orchestrator (spec B §2, §9): owns session state, triggers area discovery on meaningful
 * movement, runs the editorial scheduler, and routes user events between radio and conversation.
 * All state is confined to a single-threaded dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioSession(
    private val places: PlacesProvider,
    private val narrator: Narrator,
    private val speech: SpeechService,
    audio: AudioOutput,
    private val historyStore: HistoryStore,
    private val config: () -> SessionConfig,
    private val areaLabeler: AreaLabeler? = null,
    private val memoryStore: MemoryStore? = null,
    private val favoritesStore: FavoritesStore? = null,
    /** Persists implicit interest signals (full listens, early skips, follow-ups); null keeps them per session. */
    private val interestStore: InterestStore? = null,
    /** Creates a hands-free Realtime voice conversation; null disables live voice. */
    private val liveFactory: ((LiveHost, CoroutineScope) -> LiveConversation)? = null,
    private val onPersistLanguage: (String) -> Unit = {},
    private val onNavigate: (PlaceCandidate) -> Unit = {},
    private val ranker: EditorialRanker = EditorialRanker(),
    private val processor: LocationProcessor = LocationProcessor(),
    private val refreshPolicy: AreaRefreshPolicy = AreaRefreshPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val tickMs: Long = 3_000,
    private val conversationIdleMs: Long = 45_000,
    private val timeouts: Timeouts = Timeouts(),
    /** How long to wait for a yes/no after offering a story or asking a question. */
    private val offerWindowMs: Long = 25_000,
    private val teaserGapMs: Long = 8 * 60_000L,
    private val teaserMinFactsChars: Int = 900,
    /** On-device notes + voice used without a key or while OpenAI is unreachable (spec B §17). */
    private val fallbackNarrator: Narrator? = null,
    private val fallbackSpeech: SpeechService? = null,
    /** Network state: when offline, stories go straight to the fallback and questions are declined quickly. */
    private val isOnline: () -> Boolean = { true },
    /** Earcons before stories/answers; null plays none. */
    private val stings: StingPlayer? = null,
    private val journalStore: JournalStore? = null,
    private val tourPlanner: TourPlanner = TourPlanner(),
    /** A tour stop counts as reached within this distance. */
    private val arrivalRadiusM: Double = 40.0,
    /** Facts needed for the optional "you're standing in front of it" chapter. */
    private val arrivalChapterMinChars: Int = 400,
    /** Running order for fillers between stories (bumpers, on this day, quizzes, station ID). */
    private val programme: Programme = Programme(),
    /** "On this day" events; null leaves that format out. */
    private val onThisDay: OnThisDaySource? = null,
    /** Articles about the current town/region for area stories; null leaves that format out. */
    private val areaInfo: AreaInfoSource? = null,
    /** Finds events today nearby (web search); null leaves them out. */
    private val eventScout: EventSource? = null,
    /** Checks today's hours, admission and what a visit involves (web search); null uses OSM tags only. */
    private val visitScout: VisitSource? = null,
    /** Researches the 50 location angles (web search) so the non-stop radio never runs dry; null leaves it out. */
    private val angleResearch: com.gpsradio.core.discovery.AngleResearch? = null,
    /** Picks the photos for what's being said (spec A §62); null keeps the simpler per-story pictures. */
    private val pictureFinder: com.gpsradio.core.ai.PictureFinder? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /**
     * Every clip of the radio path plays through one queue: two clips (a story and a "just a moment", a notice and a
     * story) can never overlap; a cancelled clip that was still waiting simply never plays.
     */
    private val audioLock = kotlinx.coroutines.sync.Mutex()
    private val audio: AudioOutput = audio.let { out -> AudioOutput { bytes -> audioLock.withLock { out.play(bytes) } } }

    data class Timeouts(
        val narrationMs: Long = 25_000,
        val speechMs: Long = 25_000,
        val transcriptionMs: Long = 25_000,
        /** Covers a possible second, web-search call. */
        val conversationMs: Long = 45_000,
        val discoveryMs: Long = 30_000,
    )

    /** A story generated ahead of time (text + audio) so it can start without delay. */
    private class Prepared(
        val placeId: String,
        val language: String,
        val segment: Segment,
        val audio: ByteArray,
        val preparedAt: GeoPoint,
        val atMs: Long,
    )

    /** Last line of defence: a stray exception in a session job is reported, never a process crash. */
    private val crashGuard = CoroutineExceptionHandler { _, e -> runCatching { fail("Something went wrong: ${e.message}") } }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + crashGuard)
    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    private val candidates = LinkedHashMap<String, PlaceCandidate>()
    private var ranked: List<RankedCandidate> = emptyList()
    private val heard = HeardHistory()
    private val failedIds = HashSet<String>()
    private val mentionedIds = HashSet<String>()
    private val recentTitles = ArrayList<String>()
    private val history = ArrayList<ConversationTurn>()
    private val topicPenalty = HashMap<Topic, Double>()
    private val memory = UserMemory()
    private val favorites = Favorites()
    private val interestModel = InterestModel()
    private val corridor = CorridorDiscovery(places)
    /** Story whose audio is playing now and when playback started (for early-skip detection). */
    private var storyPlayback: Pair<String, Long>? = null
    private val galleries = HashMap<String, List<String>>()
    private var pendingOffer: PlaceCandidate? = null
    private var offerKind = OfferKind.STORY
    private var lastTeaserMs = 0L
    private var storiesSinceTeaser = 0
    private var tripAsked = false
    private var preferencesAsked = false
    private var tripContext: String? = null
    private var live: LiveConversation? = null
    private val journal = Journal()
    private var tour: TourState? = null
    /** After a tour stop's story: directions to the next stop (or the closing line) are still to be said. */
    private var tourHintDue = false
    private var tourStartedMs = 0L
    /** Since when the listener has been far from the next stop (null while close). */
    private var tourAwaySinceMs: Long? = null
    /** Index of the stop being told; rolled back if the story is interrupted rather than skipped. */
    private var tourStopInFlight: Int? = null

    private var lastRefreshPoint: GeoPoint? = null
    private var lastRefreshMode: TravelMode? = null
    private var lastRefreshMs: Long? = null
    private var lastRefreshLang: String? = null
    private var lastSpeechEndMs: Long? = null
    private var engagedUntilMs = 0L
    private var activeId: String? = null
    /** The story currently being prepared or played (not yet counted as heard). */
    private var pendingId: String? = null
    private var lastAudio: ByteArray? = null
    private var languageOverride: String? = null
    private var nextNarrationAllowedMs = 0L
    private var backoffMs = 0L
    private var prefetched: Prepared? = null
    private var lastNearbyKey: List<Any>? = null
    /** Smoothed time from "pick a story" to "audio ready"; used to project the listener's position. */
    private var prepLatencyMs = 6_000.0
    private val fallbackGate = FallbackGate(clock)

    private var schedulerJob: Job? = null
    private var discoveryJob: Job? = null
    private var speechJob: Job? = null
    private var prefetchJob: Job? = null

    private val sessionLanguage: String get() = languageOverride ?: config().language

    init {
        scope.launch {
            memory.restore(runCatching { memoryStore?.load() }.getOrNull())
            favorites.restore(runCatching { favoritesStore?.load() }.getOrNull())
            interestModel.restore(runCatching { interestStore?.load() }.getOrNull())
            journal.restore(runCatching { journalStore?.load() }.getOrNull())
            _state.update { it.copy(memory = memory.all, favorites = favorites.all, journal = journal.all) }
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------

    fun start() = scope.launch {
        if (schedulerJob?.isActive == true) return@launch
        firstStoryPending = true
        heard.restore(historyStore.load(), clock())
        programme.reset()
        // Remembered from earlier days: area stories told and angles already researched (spec A §40).
        programme.preloadToldFacets(heard.toldFacets(clock()))
        triedAngles.clear()
        triedAngles += heard.triedAngles(clock())
        radiusBoost = 1.0
        // Off for a while: the listener may be far away now, so don't narrate from the old spot.
        val last = _state.value.location ?: processor.current
        if (last != null && clock() - last.timestampMs > STALE_FIX_ON_START_MS) {
            processor.forgetFix()
            ranked = emptyList()
            prefetched = null
            _state.update { it.copy(location = null, nearby = emptyList()) }
        }
        _state.update { it.copy(radioState = RadioState.RADIO, sessionLanguage = sessionLanguage, status = previewNote()) }
        schedulerJob = scope.launch {
            while (isActive) {
                tick()
                // Wait for the next tick, or less when a segment just ended and non-stop wants the next one now.
                kotlinx.coroutines.withTimeoutOrNull(tickMs) { wake.receive() }
            }
        }
    }

    fun stop() = scope.launch {
        steerJob?.cancel()
        endLive()
        clearOffer()
        schedulerJob?.cancel()
        discoveryJob?.cancel()
        speechJob?.cancel()
        prefetchJob?.cancel()
        schedulerJob = null
        prefetched = null
        pendingId = null
        persistHeard()
        languageOverride = null
        pendingOffer = null
        tripAsked = false
        tripContext = null
        clearTour()
        _state.update { it.copy(radioState = RadioState.IDLE, nowPlaying = null, nowPlayingReason = null, discovering = false, pendingOffer = null, tripContext = null) }
    }

    fun pause() = scope.launch { doPause() }

    /** The key or its billing may have changed: probe OpenAI again right away. */
    fun onApiKeyChanged() = scope.launch {
        clearQuota()
        fallbackGate.onPrimarySuccess()
        nextNarrationAllowedMs = 0
        backoffMs = 0
    }

    fun resume() = scope.launch {
        val st = _state.value.radioState
        if (st == RadioState.IDLE || st == RadioState.NARRATING || st == RadioState.RESEARCHING) return@launch
        endConversation()
        tick()
    }

    // ---- inputs ---------------------------------------------------------------------------

    fun onLocation(sample: LocationSample) = scope.launch {
        val ctx = processor.accept(sample, clock()) ?: return@launch
        _state.update { it.copy(location = ctx) }
        if (_state.value.radioState == RadioState.IDLE) return@launch
        maybeRefresh(ctx)
        rerank()
        if (tour != null) tourTick()
    }

    /** Activity-recognition transition (vehicle, bicycle, walking, still): a prior for mode detection. */
    fun onActivity(type: ActivityType) = scope.launch { processor.setActivity(type, clock()) }

    fun setModeOverride(mode: TravelMode?) = scope.launch {
        processor.modeOverride = mode
        _state.update { it.copy(modeOverride = mode, location = processor.current ?: it.location) }
        rerank()
    }

    fun skip() = scope.launch {
        steerJob?.cancel()
        closeLive()
        clearOffer()
        val wasSpeaking = speechJob?.isActive == true
        storyPlayback?.takeIf { wasSpeaking && clock() - it.second < EARLY_SKIP_MS }?.let { (id, _) ->
            candidates[id]?.let { learn(InterestModel.Signal.EARLY_SKIP, it) }
        }
        storyPlayback = null
        // A skipped tour stop stays skipped; any other interruption (pause, hold-to-talk) keeps it for later.
        tourStopInFlight = null
        speechJob?.cancel()
        if (wasSpeaking) {
            // Penalize what was actually on air (or being prepared), not the previous story.
            (pendingId ?: activeId)?.let { id -> candidates[id]?.let { penalize(it) } }
            airingFacetId?.let { interrupted = null to it; dropInterrupted() }
            lastSpeechEndMs = clock()
        } else if (interrupted != null) {
            // The listener's voice already stopped the story: drop that one (it was never finished).
            dropInterrupted()
        }
        pendingId = null
        engagedUntilMs = 0
        if (_state.value.radioState !in setOf(RadioState.IDLE, RadioState.PAUSED)) setRadioState(RadioState.RADIO)
        _state.update { it.copy(nowPlaying = null, nowPlayingReason = null) }
        requestNextNow()
        rerank()
    }

    fun repeat() = scope.launch {
        steerJob?.cancel()
        closeLive()
        val bytes = lastAudio ?: return@launch
        speechJob?.cancel()
        val previous = _state.value.radioState
        speechJob = scope.launch {
            setRadioState(RadioState.NARRATING)
            runCatching { audio.play(bytes) }.onFailure { if (it is CancellationException) throw it }
            lastSpeechEndMs = clock()
            setRadioState(
                // Back to where it was; an exchange ended when "repeat" was asked, so that means the radio.
                if (previous == RadioState.PAUSED) previous else RadioState.RADIO,
            )
        }
    }

    /** User picked a place in the UI: narrate it now, even if heard before. */
    fun tellAbout(placeId: String) = scope.launch {
        steerJob?.cancel()
        val c = ranked.firstOrNull { it.place.id == placeId } ?: return@launch
        closeLive()
        clearOffer()
        speechJob?.cancel()
        if (_state.value.radioState == RadioState.IDLE) return@launch
        speakStory(c)
    }

    fun ask(text: String) = scope.launch {
        if (text.isBlank()) return@launch
        live?.takeIf { it.isOpen }?.let { l ->
            addTranscript(TranscriptEntry(Speaker.USER, text.trim(), clock()))
            // Always listening: a typed question stops the story first, so the host doesn't talk over it.
            if (_state.value.radioState != RadioState.CONVERSING) startExchange(l)
            l.sendText(text.trim())
            return@launch
        }
        beginConversation()
        speechJob = scope.launch { handleUtterance(text.trim()) }
    }

    fun askAudio(audioBytes: ByteArray, fileName: String, mimeType: String) = scope.launch {
        beginConversation()
        val hint = ranked.take(15).joinToString(", ") { it.place.name }
        speechJob = scope.launch {
            if (questionsUnavailable()) { endConversation(); return@launch }
            val text = try {
                setStatus("Transcribing…", StatusLevel.WORKING)
                timed(timeouts.transcriptionMs, "Transcription") { speech.transcribe(audioBytes, fileName, mimeType, hint) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Couldn't transcribe: ${e.message}")
                endConversation()
                return@launch
            }
            if (text.isBlank()) {
                setStatus("Didn't catch that")
                endConversation()
                return@launch
            }
            setStatus(null)
            handleUtterance(text)
        }
    }

    fun whatsNearby() = ask("What else is interesting nearby?")

    /** Whether the natural, hands-free voice is available and enabled. */
    val liveVoiceEnabled: Boolean get() = liveFactory != null && config().liveVoice && !config().previewMode

    /** Always listening is on and possible right now. */
    private val handsFreeActive: Boolean get() = liveVoiceEnabled && config().handsFree

    private var standbyFailures = 0
    private var standbyRetryAtMs = 0L

    /**
     * Always-listening mode: keeps a persistent live connection open while the radio runs, so the listener
     * can just talk. Backs off after failures; closes when switched off or not possible (offline, no credit).
     */
    private fun maybeStandby(now: Long) {
        val l = live
        val canListen = handsFreeActive && isOnline() && !_state.value.quotaExhausted && !config().budgetReached &&
            _state.value.radioState != RadioState.IDLE
        if (!canListen) {
            if (l != null && l.persistent && !l.inConversation) endLive()
            return
        }
        if (l?.isOpen == true || now < standbyRetryAtMs) return
        val factory = liveFactory ?: return
        // Assigned before start(): a failing handshake reports back through liveHost, which must see this
        // connection to back off quietly instead of treating it as a failed conversation.
        val l2 = factory(liveHost, scope)
        live = l2
        l2.start(opening = null, persistent = true)
    }

    /** Opens a hands-free voice conversation (tap the mic), or closes it if already open. */
    fun toggleLive() = scope.launch {
        val current = live
        if (current?.isOpen == true && current.persistent) {
            // Always listening: the mic button means "I want to talk now" / "back to the radio".
            if (_state.value.radioState == RadioState.CONVERSING) endConversation() else startExchange(current)
            return@launch
        }
        if (live?.isOpen == true) {
            closeLive()
            endConversation()
        } else {
            openLive(opening = null)
        }
    }

    /** Answer to "want the full story?" from the on-screen buttons. */
    fun answerOffer(yes: Boolean) = scope.launch {
        steerJob?.cancel()
        val offer = pendingOffer ?: return@launch
        closeLive()
        speechJob?.cancel()
        if (yes) acceptOffer(offer) else declineOffer(offer)
    }

    fun clearHistory() = scope.launch {
        heard.clear()
        recentTitles.clear()
        persistHeard()
        rerank()
    }

    /** Star/un-star a place (from the Now card, the Nearby list, or by voice). */
    fun toggleFavorite(placeId: String) = scope.launch {
        val fav = candidates[placeId]?.let { FavoritePlace.of(it, clock()) }
            ?: favorites.all.firstOrNull { it.id == placeId }
            ?: return@launch
        favorites.toggle(fav)
        persistFavorites()
    }

    fun removeFavorite(placeId: String) = scope.launch {
        favorites.remove(placeId)
        persistFavorites()
    }

    fun forgetMemory(id: String) = scope.launch {
        memory.remove(id)
        persistMemory()
        rerank()
    }

    fun clearMemory() = scope.launch {
        memory.clear()
        persistMemory()
        rerank()
    }

    // ---- scheduler ------------------------------------------------------------------------

    private fun tick() {
        val s = _state.value
        // The live host still talking (or its answer still coming out of the speaker) counts as speaking.
        val speaking = speechJob?.isActive == true || live?.isAudible == true
        val now = clock()
        if (s.radioState == RadioState.CONVERSING && !speaking && now >= engagedUntilMs) {
            // No answer to an offer means "not now": keep the story for later, just less novel.
            clearOffer()
            setRadioState(RadioState.RADIO)
        }
        // Retries and refresh requests must not depend on new fixes: stationary phones get none.
        if (s.radioState != RadioState.IDLE) processor.current?.let { maybeRefresh(it) }
        if (s.radioState != RadioState.IDLE) maybeScoutEvents(now)
        if (s.radioState != RadioState.IDLE && s.radioState != RadioState.PAUSED) maybeResearchAngle(now)
        maybeStandby(now)
        maybeWaitCue(now, speaking)
        // During a walking tour only the tour's stops air (on arrival).
        if (tour != null) return tourTick()

        if (_state.value.radioState != RadioState.RADIO || speaking || discoveryJob?.isActive == true) return
        if (now < engagedUntilMs || now < nextNarrationAllowedMs) return
        // A quiz answer is due as soon as the answer window closes (only junctions hold it back).
        programme.pendingQuiz?.let { quiz ->
            if (!ranker.holdForManeuver(_state.value.location, now)) revealQuiz(quiz)
            return
        }
        // After an explicit "next" only a junction holds the story back, not the pacing gap.
        if (ranker.holdForPacing(_state.value.location, lastSpeechEndMs.takeUnless { nextNow() }, now, config().pacing)) return
        if (maybeAskAboutTrip()) return
        if (maybeAskPreferences()) return
        rerank()
        if (runProgramme(now)) return
        val pick = ranker.pickForAirtime(ranked, config().pacing) ?: return
        speakStory(pick)
    }

    private fun maybeRefresh(ctx: LocationContext) {
        val lang = langBase(sessionLanguage)
        val due = lastRefreshLang != lang ||
            refreshPolicy.shouldRefresh(ctx.copy(timestampMs = clock()), lastRefreshPoint, lastRefreshMode, lastRefreshMs)
        if (due) refresh(ctx, lang)
    }

    private fun refresh(ctx: LocationContext, lang: String) {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            _state.update { it.copy(discovering = true) }
            try {
                areaLabeler?.let { labeler ->
                    runCatching { labeler.label(Geo.quantize(ctx.point, 2)) }.getOrNull()?.let { area ->
                        _state.update { it.copy(area = area) }
                    }
                }
                val radius = (refreshPolicy.searchRadiusM(ctx.travelMode) * radiusBoost).toInt()
                val found = timed(timeouts.discoveryMs, "Discovery") {
                    // Driving: look-ahead corridor cells, fetched (and cached) before arrival.
                    refreshPolicy.corridorCells(ctx)?.let { cells -> corridor.discover(cells, lang, clock()) }
                        ?: places.discover(refreshPolicy.searchCenter(ctx), radius, lang)
                }
                if (lang != lastRefreshLang) candidates.clear()
                // Opt-in topics (§44): what the listener didn't choose is left out.
                val interests = config().interests
                val foundIds = found.mapTo(HashSet()) { it.id }
                candidates.values.removeAll { it.id in foundIds }
                found.mapNotNull { it.forInterests(interests) }.forEach { candidates[it.id] = it }
                // A widened search (non-stop) found plenty: back to the normal radius for the next refresh.
                if (found.size >= RADIUS_RESET_FOUND && radiusBoost > 1.0) radiusBoost = 1.0
                // Old candidates decay: forget anything far outside the current search area.
                candidates.values.removeAll { Geo.distanceM(ctx.point, it.point) > radius * 3.0 }
                lastRefreshPoint = ctx.point
                lastRefreshMode = ctx.travelMode
                lastRefreshMs = clock()
                lastRefreshLang = lang
                if (_state.value.status?.text?.let { it.startsWith("Couldn't load") || it == OFFLINE_NO_PLACES } == true) setStatus(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep whatever is cached; never invent places to fill the gap. Retry in about a minute.
                if (isOnline()) setStatus("Couldn't load nearby places. Retrying shortly…", StatusLevel.ERROR) else setStatus(OFFLINE_NO_PLACES)
                lastRefreshMs = clock() - 14 * 60_000L
                lastRefreshPoint = lastRefreshPoint ?: ctx.point
                lastRefreshMode = lastRefreshMode ?: ctx.travelMode
                lastRefreshLang = lastRefreshLang ?: lang
            } finally {
                _state.update { it.copy(discovering = false) }
            }
            rerank()
            // After this job completes: tick() skips work while discovery is still active.
            scope.launch { tick() }
        }
    }

    private fun rerank() {
        val loc = _state.value.location ?: processor.current ?: return
        val now = clock()
        val interests = config().interests.associateWith { 1.0 }.toMutableMap()
        interests.putAll(memory.topicWeights())
        topicPenalty.forEach { (t, p) -> interests[t] = (interests[t] ?: 0.4) * p }
        ranked = ranker.rank(
            candidates.values.filter { it.id !in failedIds && (it.id !in deviceSkipped || !onDeviceNow()) },
            EditorialRanker.Context(
                location = loc,
                interests = interestModel.adjust(interests, now, explicit = memory.topicWeights().keys),
                heard = heard,
                nowMs = now,
                mentionedIds = mentionedIds,
                lastSpeechEndMs = lastSpeechEndMs.takeUnless { nextNow() },
                userEngaged = now < engagedUntilMs || _state.value.radioState == RadioState.CONVERSING,
                theme = _state.value.theme,
                pacing = config().pacing,
            ),
        )
        // The next story is likely the best candidate: check its hours/fees in the background.
        ranked.firstOrNull()?.let { ensureVisit(it) }
        // Only push a new list to the UI when it visibly changed (order or ~10 m distance steps).
        val top = ranked.take(25)
        val key = top.map { it.place.id to (it.distanceM / 10).toInt() }
        if (key != lastNearbyKey) {
            lastNearbyKey = key
            val detours = detourSuggestions(loc)
            val photos = top.filter { PhotoSpots.isPhotogenic(it.place) }.map { it.place.id }.toSet()
            _state.update { it.copy(nearby = top, detours = detours, photoSpotIds = photos) }
        }
    }

    // ---- narration ------------------------------------------------------------------------

    private fun speakStory(c: RankedCandidate, allowTeaser: Boolean = true) {
        val loc = _state.value.location ?: return
        speechJob = scope.launch {
            pendingId = c.place.id
            val lang = sessionLanguage
            val ready = takePrefetched(c, lang, loc)
            val format = if (ready == null && allowTeaser && shouldTease(c)) SegmentFormat.TEASER else SegmentFormat.STORY
            if (ready == null) setRadioState(RadioState.RESEARCHING)
            try {
                val (segment, bytes) = ready ?: prepare(c, loc, lang, format)
                if (!stillInSync(c.place)) {
                    // Passed it while the story was being prepared: drop it rather than play it out of sync.
                    heard.markHeard(c.place.id, c.place.name, clock())
                    pendingId = null
                    setRadioState(RadioState.RADIO)
                    rerank()
                    return@launch
                }
                activeId = c.place.id
                lastAudio = bytes
                addTranscript(TranscriptEntry(Speaker.RADIO, segment.text, clock(), c.place.id, segment.sources))
                lastStory = segment
                _state.update { it.copy(radioState = RadioState.NARRATING, nowPlaying = segment, nowPlayingReason = reasonFor(c), focus = FocusPlace.of(c.place)) }
                // Always listening: the host knows which story is on, so "tell me more about that" works.
                live?.takeIf { it.persistent && !it.inConversation }?.updateInstructions()
                loadGallery(c.place)
                illustrateOrSlides(c.place.id, segment, STING_LEAD_MS)
                sting(Sting.STATION)
                // A teaser that failed over to on-device notes is told as a plain story instead.
                if (format == SegmentFormat.TEASER && !fallbackGate.degraded) {
                    audio.play(bytes)
                    // Wait for "yes/no"; the story stays unheard until it is actually told.
                    pendingOffer = c.place
                    pendingId = null
                    mentionedIds += c.place.id
                    lastTeaserMs = clock()
                    storiesSinceTeaser = 0
                    lastSpeechEndMs = clock()
                    engagedUntilMs = clock() + offerWindowMs
                    _state.update { it.copy(radioState = RadioState.CONVERSING, pendingOffer = c.place.name) }
                    // Hands-free: listen for the answer with the live voice when it's enabled.
                    if (liveVoiceEnabled) openLive(opening = null)
                    return@launch
                }
                storiesSinceTeaser++
                // While this plays, prepare what comes next (a place story, or a researched area story) so the
                // radio flows on without dead air.
                prefetchNext(excludeId = c.place.id, leadMs = spokenMs(segment.text))
                if (prefetchJob?.isActive != true && prefetched == null) prefetchArea()
                storyPlayback = c.place.id to clock()
                audio.play(bytes)
                storyPlayback = null
                learn(InterestModel.Signal.COMPLETED, c.place)
                // Only a story that was actually heard to the end counts as heard.
                heard.markHeard(c.place.id, c.place.name, clock())
                persistHeard()
                recordJournal(c.place, segment)
                recentTitles += c.place.name
                programme.onStoryAired()
                pendingId = null
                backoffMs = 0
                lastSpeechEndMs = clock()
                setRadioState(RadioState.RADIO)
                rerank()
                // The story ended with "want me to navigate there?": take a yes as directions.
                if (c.roadTrip == RoadTripKind.WORTH_A_STOP && config().canReply && segment.text.trimEnd().endsWith("?")) offerDetour(c.place)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotInListenerLanguageException) {
                // Offline and the notes aren't in the listener's language: skip it until OpenAI can translate.
                pendingId = null
                deviceSkipped += c.place.id
                setRadioState(RadioState.RADIO)
                rerank()
            } catch (e: Exception) {
                pendingId = null
                lastSpeechEndMs = clock()
                handleFailure(e, c.place.id, "Narration failed")
                setRadioState(RadioState.RADIO)
            }
        }
    }

    private suspend fun prepare(
        c: RankedCandidate,
        loc: LocationContext,
        lang: String,
        format: SegmentFormat = SegmentFormat.STORY,
        /** Extra time before this airs (prepared while another segment plays). */
        extraLeadMs: Long = 0,
    ): Pair<Segment, ByteArray> {
        val cfg = config()
        val started = clock()
        // Describe distance/direction from where the listener will be when the audio starts, not from now.
        val (atPlayback, rel) = projectForPlayback(c, loc, prepLatencyMs.toLong() + extraLeadMs)
        val base = NarrationRequest(
            rel, atPlayback, lang, cfg.interests, recentTitles.toList(), memory.promptLines(),
            style = cfg.style, format = format, tripContext = tripContext, canReply = cfg.canReply,
        )
        if (onDeviceNow()) return prepareOnDevice(base)
        val request = if (format == SegmentFormat.STORY) {
            base.copy(
                visit = visitFor(c),
                detourMinutes = c.takeIf { it.roadTrip == RoadTripKind.WORTH_A_STOP }?.let { Detours.minutes(atPlayback, it.place.point) },
            )
        } else base
        var segment: Segment? = null
        return try {
            val s = timed(timeouts.narrationMs, "Narration") { narrator.narrate(request) }
            segment = s
            val bytes = timed(timeouts.speechMs, "Speech") { speech.synthesize(s.text, lang, cfg.style) }
            fallbackGate.onPrimarySuccess()
            if (keyRejected) {
                keyRejected = false
                clearNotes(KEY_REJECTED)
            }
            degradedAnnounced = false
            deviceSkipped.clear()
            clearQuota()
            prepLatencyMs = prepLatencyMs * 0.7 + (clock() - started) * 0.3
            clearNotes(DEGRADED_NOTE, OFFLINE_NOTE)
            s to bytes
        } catch (e: Exception) {
            if (isQuota(e)) noteQuota()
            if (e is OpenAiException && (e.status == 401 || e.status == 403) && !isQuota(e)) {
                // A rejected key isn't an outage: say so (with the fix) instead of "OpenAI is unreachable".
                keyRejected = true
                _state.update { it.copy(status = Status(KEY_REJECTED, StatusLevel.ERROR, needsKey = true)) }
            }
            // OpenAI unavailable: read the source notes on the device instead of going silent.
            if (!hasFallback || !FallbackGate.isOutage(e)) throw e
            fallbackGate.onPrimaryFailure()
            prepareOnDevice(request, spoken = segment)
        }
    }

    /**
     * Where the listener will be after [aheadMs] (dead reckoning along the heading while moving) and the
     * candidate's distance/bearing from there.
     */
    private fun projectForPlayback(c: RankedCandidate, loc: LocationContext, aheadMs: Long): Pair<LocationContext, RankedCandidate> {
        val heading = loc.headingDeg
        if (heading == null || loc.speedMps < 2.0) return loc to c
        val moved = loc.speedMps * aheadMs / 1000.0
        val p = Geo.destination(loc.point, heading, moved)
        val projected = loc.copy(point = p, timestampMs = loc.timestampMs + aheadMs)
        return projected to c.copy(distanceM = Geo.distanceM(p, c.place.point), bearingDeg = Geo.bearingDeg(p, c.place.point))
    }

    /**
     * Is the story still in sync with where the listener is right now? A moving listener who has already
     * passed the place (it's well behind them) would hear "just ahead on your left" for something gone.
     */
    private fun stillInSync(place: PlaceCandidate): Boolean {
        val loc = processor.current ?: _state.value.location ?: return true
        val heading = loc.headingDeg ?: return true
        if (loc.speedMps < 2.0) return true
        val d = Geo.distanceM(loc.point, place.point)
        val behind = Geo.angleDiff(Geo.bearingDeg(loc.point, place.point), heading) > 110
        return !(behind && d > passedToleranceM(loc))
    }

    private fun passedToleranceM(loc: LocationContext): Double = if (loc.travelMode == TravelMode.DRIVING) 250.0 else 60.0

    private val hasFallback: Boolean get() = fallbackNarrator != null && fallbackSpeech != null

    /** Keyless preview, offline, or OpenAI resting after an outage: narrate on the device. */
    private fun onDeviceNow(): Boolean =
        hasFallback && (config().previewMode || config().budgetReached || !isOnline() || fallbackGate.primaryResting())

    /** On-device notes and voice; [spoken] is model text that was generated but could not be voiced. */
    private suspend fun prepareOnDevice(req: NarrationRequest, spoken: Segment? = null): Pair<Segment, ByteArray> {
        if (config().budgetReached) {
            setStatus(BUDGET_NOTE)
        } else if (!config().previewMode && !_state.value.quotaExhausted && !keyRejected) {
            setStatus(if (isOnline()) DEGRADED_NOTE else OFFLINE_NOTE)
        }
        var segment = spoken?.takeIf { req.format == SegmentFormat.STORY }
            ?: fallbackNarrator!!.narrate(req.copy(format = SegmentFormat.STORY))
        // Say once, out loud, why the stories got shorter: the listener may not be looking at the screen.
        if (config().budgetReached) {
            if (!budgetAnnounced && (segment.language ?: req.language).let { langBase(it) == langBase(req.language) }) {
                budgetAnnounced = true
                segment = segment.copy(text = Notices.text(Notice.BUDGET_REACHED, req.language) + " " + segment.text)
            }
        } else if (!_state.value.quotaExhausted && !config().previewMode && !degradedAnnounced &&
            (segment.language ?: req.language).let { langBase(it) == langBase(req.language) }
        ) {
            degradedAnnounced = true
            segment = segment.copy(text = Notices.text(if (isOnline()) Notice.DEGRADED_NOTES else Notice.OFFLINE_NOTES, req.language) + " " + segment.text)
        }
        if (_state.value.quotaExhausted && !quotaAnnounced && (segment.language ?: req.language).let { langBase(it) == langBase(req.language) }) {
            quotaAnnounced = true
            segment = segment.copy(text = quotaSpoken(req.language) + " " + segment.text)
        }
        val bytes = timed(timeouts.speechMs, "Speech") {
            fallbackSpeech!!.synthesize(segment.text, segment.language ?: req.language, req.style)
        }
        return segment to bytes
    }

    private fun clearNotes(vararg notes: String) {
        if (_state.value.status?.text in notes) setStatus(null)
    }

    /** "Why this story?" from the score breakdown, using stated and learned interests. */
    private fun reasonFor(c: RankedCandidate): String? {
        val learned = memory.topicWeights()
        val likes = (config().interests + learned.filterValues { it >= 0.7 }.keys) - learned.filterValues { it < 0.3 }.keys
        return StoryReason.of(c, likes, _state.value.theme)
    }

    /** While a story plays, prepare the next likely one so it can start without "Preparing…" dead air. */
    /** Roughly how long a text takes to say (about 2.6 words a second). */
    private fun spokenMs(text: String): Long = (text.split(Regex("\\s+")).size / 2.6 * 1000).toLong()

    private fun prefetchNext(excludeId: String, leadMs: Long = 0) {
        if (prefetchJob?.isActive == true) return
        val loc = _state.value.location ?: return
        val moving = loc.travelMode == TravelMode.DRIVING || loc.travelMode == TravelMode.CYCLING
        // Moving fast: only non-stop prepares ahead (otherwise the gap is long and the story would go stale), and only
        // a place that will still be ahead when this one ends.
        if (moving && config().pacing != Pacing.NONSTOP) return
        val travelled = loc.speedMps * (leadMs + prepLatencyMs) / 1000.0
        val next = ranked.firstOrNull { r ->
            r.place.id != excludeId &&
                // A rich story may be offered as a teaser first; don't pre-generate its full version.
                !teaserEligible(r) &&
                (!moving || (r.distanceM > travelled * 0.6 && Geo.angleDiff(r.bearingDeg, loc.headingDeg ?: r.bearingDeg) < 80)) &&
                // Ignore the temporary conversation-cost penalty: it will have decayed by the time this airs.
                r.score + ranker.weights.conversationCost * r.breakdown.conversationCost >= prefetchThreshold() &&
                r.breakdown.novelty > 0.0 && !(r.place.extract ?: r.place.description).isNullOrBlank()
        } ?: return
        if (prefetched?.placeId == next.place.id) return
        val lang = sessionLanguage
        prefetchJob = scope.launch {
            val result = runCatching { prepare(next, loc, lang, extraLeadMs = leadMs) }.getOrNull() ?: return@launch
            // Where the listener should be when it airs (checked again when it's taken).
            val expected = if (moving) projectForPlayback(next, loc, leadMs + prepLatencyMs.toLong()).first.point else loc.point
            prefetched = Prepared(next.place.id, lang, result.first, result.second, expected, clock())
        }
    }

    /** Non-stop also airs weaker ("relaxed") places, so it prepares those ahead too. */
    private fun prefetchThreshold(): Double {
        val pacing = config().pacing
        val t = ranker.thresholdFor(pacing)
        return if (pacing == Pacing.NONSTOP) t * programme.config.relaxedFactor else t
    }

    // ---- researched/area stories prepared ahead --------------------------------------------

    /** The area story on air now (so the one prepared next isn't the same). */
    private var airingFacetId: String? = null

    private var preparedArea: Triple<String, String, Pair<Segment, ByteArray>>? = null
    private var areaPrefetchJob: Job? = null

    /** The next untold area/angle story, narrated and voiced ahead, so it can follow the current segment at once. */
    private fun prefetchArea(excludeId: String? = null) {
        if (config().pacing != Pacing.NONSTOP || areaPrefetchJob?.isActive == true || onDeviceNow()) return
        val told = programme.toldFacets.toSet()
        val facet = (areaFacets + currentResearched()).firstOrNull { it.id !in told && it.id != excludeId } ?: return
        if (preparedArea?.first == facet.id) return
        val loc = _state.value.location ?: return
        val lang = sessionLanguage
        val cfg = config()
        areaPrefetchJob = scope.launch {
            val prepared = runCatching {
                val seg = timed(timeouts.narrationMs, "Narration") {
                    narrator.narrateFiller(
                        FillerRequest(
                            SegmentFormat.AREA, lang, loc, _state.value.area, cfg.style, areaFacet = facet,
                            // Include the one on air now: this follows it.
                            areaToldFacets = programme.toldFacets + listOfNotNull(excludeId),
                            profile = memory.promptLines(), tripContext = tripContext,
                        ),
                    )
                }
                seg to timed(timeouts.speechMs, "Speech") { speech.synthesize(seg.text, lang, cfg.style) }
            }.getOrNull() ?: return@launch
            preparedArea = Triple(facet.id, lang, prepared)
        }
    }

    private fun takePreparedArea(facetId: String?, lang: String): Pair<Segment, ByteArray>? {
        val p = preparedArea ?: return null
        if (p.first != facetId || p.second != lang) return null
        preparedArea = null
        return p.third
    }

    private fun takePrefetched(c: RankedCandidate, lang: String, loc: LocationContext): Pair<Segment, ByteArray>? {
        val p = prefetched ?: return null
        if (p.placeId != c.place.id) return null
        prefetched = null
        // Distance/direction in the text must still be roughly right.
        val maxMove = when (loc.travelMode) {
            TravelMode.DRIVING -> 700.0
            TravelMode.CYCLING -> 350.0
            TravelMode.WALKING, TravelMode.STATIONARY, TravelMode.UNKNOWN -> 200.0
        }
        val fresh = clock() - p.atMs < 10 * 60_000L && Geo.distanceM(p.preparedAt, loc.point) < maxMove && stillInSync(c.place)
        return if (p.language == lang && fresh) p.segment to p.audio else null
    }

    // ---- conversation ---------------------------------------------------------------------

    private fun beginConversation() {
        speechJob?.cancel()
        pendingId = null
        engagedUntilMs = clock() + conversationIdleMs
        setRadioState(RadioState.CONVERSING)
    }

    /** Leaves conversation mode: closes any live voice session and drops an unanswered offer. */
    private fun endConversation() {
        closeLive()
        clearOffer()
        engagedUntilMs = 0
        afterExchange = RadioState.RADIO
        setRadioState(RadioState.RADIO)
    }

    private suspend fun handleUtterance(text: String) {
        addTranscript(TranscriptEntry(Speaker.USER, text, clock()))
        pendingOffer?.let { offer ->
            when (offerAnswer(text)) {
                true -> { acceptOffer(offer); return }
                false -> { declineOffer(offer); return }
                null -> Unit
            }
        }
        // "Where's that from?": answered on the device, instantly and for free (spec A §45).
        if (isSourcesQuestion(text)) {
            val line = SourceLines.spoken(sessionLanguage, lastStory)
            addTranscript(TranscriptEntry(Speaker.RADIO, line, clock(), lastStory?.entityId, lastStory?.sources.orEmpty()))
            speakNotice(line)
            lastSpeechEndMs = clock()
            endConversation()
            return
        }
        when (localCommand(text)) {
            ConversationAction.SKIP -> {
                // Saying "skip" already stopped the story (the utterance interrupts it), so skip() alone would see
                // nothing on air and the same story would come back: drop the interrupted one explicitly.
                if (interrupted == null) (storyPlayback?.first ?: activeId)?.let { id -> candidates[id]?.let { penalize(it) } }
                dropInterrupted()
                skip()
                return
            }
            ConversationAction.RESUME_RADIO -> { endConversation(); return }
            ConversationAction.PAUSE -> { doPause(); return }
            else -> Unit
        }
        if (questionsUnavailable()) { endConversation(); return }
        val active = activeId?.let { id -> ranked.firstOrNull { it.place.id == id } }
        val reply: ConversationReply = try {
            timed(timeouts.conversationMs, "Answer") {
                narrator.converse(
                    ConversationRequest(
                        utterance = text,
                        language = sessionLanguage,
                        location = _state.value.location,
                        area = _state.value.area,
                        active = active,
                        nearby = ranked.filter { it.place.id != activeId }.take(10),
                        recentTitles = recentTitles.toList(),
                        history = history.toList(),
                        theme = _state.value.theme,
                        profile = memory.promptLines(),
                        style = config().style,
                        tripContext = tripContext,
                        pendingOffer = pendingOffer?.let { if (offerKind == OfferKind.DETOUR) "directions to ${it.name} (a short detour)" else it.name },
                        tour = tourSummary(),
                        quiz = programme.pendingQuiz,
                    ),
                    onSearching = { setStatus("Checking online…", StatusLevel.WORKING) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Couldn't answer: ${e.message}")
            if (isQuota(e)) announceText(quotaSpoken(sessionLanguage)) else announce(Notice.ANSWER_FAILED)
            endConversation()
            return
        } finally {
            if (_state.value.status?.text == "Checking online…") setStatus(null)
        }
        history += ConversationTurn(true, text)
        history += ConversationTurn(false, reply.reply)
        // A follow-up question about the story just told is a strong interest signal.
        active?.takeIf { reply.action == ConversationAction.NONE && (reply.entityId == null || reply.entityId == it.place.id) }
            ?.let { learn(InterestModel.Signal.FOLLOW_UP, it.place) }
        while (history.size > 24) history.removeAt(0)
        programme.onQuizResolved() // the reply had the quiz in context and answered it
        reply.entityId?.let { id -> candidates[id] }?.let { place ->
            activeId = place.id
            mentionedIds += place.id
            _state.update { it.copy(focus = FocusPlace.of(place)) }
            loadGallery(place)
        } ?: focusOnMention(reply.reply)
        if (reply.reply.isNotBlank()) illustrateAnswer(reply.reply)
        reply.tripContext?.let { trip ->
            tripContext = trip
            _state.update { it.copy(tripContext = trip) }
        }
        pendingOffer?.let { offer ->
            when (reply.action) {
                ConversationAction.ACCEPT_OFFER -> { acceptOffer(offer); return }
                ConversationAction.DECLINE_OFFER -> declineOffer(offer, speak = false)
                else -> Unit
            }
        }
        if (reply.forget.isNotEmpty() || reply.remember.isNotEmpty()) {
            reply.forget.forEach { memory.forget(it) }
            reply.remember.forEach { memory.remember(it.category, it.text, it.topic, clock()) }
            persistMemory()
            rerank()
        }
        applyActionBeforeSpeaking(reply)
        addTranscript(TranscriptEntry(Speaker.RADIO, reply.reply, clock(), reply.entityId, reply.sources))

        if (reply.reply.isNotBlank()) {
            val bytes = try {
                timed(timeouts.speechMs, "Speech") { speech.synthesize(reply.reply, sessionLanguage, config().style) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Speech failed: ${e.message}")
                null
            }
            if (bytes != null) {
                lastAudio = bytes
                sting(Sting.ANSWER)
                try {
                    audio.play(bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // e.g. audio focus refused during a phone call: the answer stays in the transcript.
                    fail("Couldn't play the answer: ${e.message}")
                }
            }
        }
        lastSpeechEndMs = clock()
        when (reply.action) {
            ConversationAction.RESUME_RADIO, ConversationAction.SKIP, ConversationAction.DECLINE_OFFER -> endConversation()
            ConversationAction.PAUSE -> setRadioState(RadioState.PAUSED)
            ConversationAction.START_TOUR -> { endConversation(); startTour(reply.tourMinutes ?: 30) }
            ConversationAction.END_TOUR -> { clearTour(); endConversation() }
            else -> engagedUntilMs = clock() + conversationIdleMs
        }
    }

    private fun applyActionBeforeSpeaking(reply: ConversationReply) {
        when (reply.action) {
            ConversationAction.CHANGE_LANGUAGE -> reply.language?.trim()?.takeIf { it.isNotEmpty() }?.let { requested ->
                val tag = Languages.find(requested)?.tag ?: requested
                languageOverride = tag
                if (reply.persistLanguage) onPersistLanguage(tag)
                _state.update { it.copy(sessionLanguage = tag) }
                prefetchJob?.cancel()
                prefetched = null
                lastRefreshPoint = null // re-discover in the new language edition
            }
            ConversationAction.SET_THEME -> {
                _state.update { it.copy(theme = reply.theme) }
                rerank()
            }
            ConversationAction.CLEAR_THEME -> {
                _state.update { it.copy(theme = null) }
                rerank()
            }
            ConversationAction.SKIP -> activeId?.let { id -> candidates[id]?.let { penalize(it) } }
            ConversationAction.NAVIGATE -> {
                val id = reply.entityId ?: activeId
                id?.let { candidates[it] }?.let(onNavigate)
            }
            ConversationAction.REFRESH_NEARBY -> lastRefreshPoint = null
            ConversationAction.STAR_PLACE -> (reply.entityId ?: activeId)?.let { id -> candidates[id] }?.let { place ->
                if (!favorites.contains(place.id)) {
                    favorites.add(FavoritePlace.of(place, clock()))
                    persistFavorites()
                }
            }
            else -> Unit
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun teaserEligible(c: RankedCandidate): Boolean =
        (c.place.extract?.length ?: 0) >= teaserMinFactsChars && clock() - lastTeaserMs >= teaserGapMs

    private fun openLive(opening: String?) {
        val factory = liveFactory ?: return
        val current = live
        if (current?.isOpen == true) {
            // Always listening: already connected; just start an exchange (and ask, if there's a question).
            if (current.persistent) {
                engagedUntilMs = Long.MAX_VALUE
                afterExchange = RadioState.RADIO
                setRadioState(RadioState.CONVERSING)
                if (opening != null) current.prompt(opening) else current.beginExchange()
            }
            return
        }
        if (questionsUnavailable()) return
        speechJob?.cancel()
        pendingId = null
        engagedUntilMs = Long.MAX_VALUE
        setRadioState(RadioState.CONVERSING)
        // converse: the listener is expected to talk now; if they don't, the idle timeout returns to the radio.
        val l = factory(liveHost, scope)
        live = l
        l.start(opening, persistent = handsFreeActive, converse = true)
    }

    /**
     * What the listener talked over (a place story or an area story), captured before it is stopped: "next" in the
     * exchange that follows must drop it, or it would simply air again (it was never finished, so never marked told).
     */
    private var interrupted: Pair<String?, String?>? = null

    private fun noteInterrupted() {
        interrupted = null
        if (speechJob?.isActive != true) return
        val place = storyPlayback?.first ?: pendingId
        val facet = airingFacetId
        if (place != null || facet != null) interrupted = place to facet
    }

    /** "Next" after talking over a story: that story is done with (spec A §52). */
    private fun dropInterrupted() {
        val (place, facet) = interrupted ?: (activeId to null)
        interrupted = null
        place?.let { id -> candidates[id]?.let { penalize(it) } }
        facet?.let { id ->
            programme.onFillerAired(SegmentFormat.AREA, null, clock(), dayKey(today()), id)
            heard.markFacetTold(id, clock())
            persistHeard()
        }
    }

    /** A spoken command already handled on the device (so the model's own tool call for it is ignored). */
    private var localCommandDone: Pair<ConversationAction, Long>? = null

    private fun handledLocally(action: ConversationAction): Boolean =
        localCommandDone?.let { (a, at) -> a == action && clock() - at < LOCAL_COMMAND_DEDUP_MS } == true

    /**
     * Radio controls said in so many words during a live exchange run at once on the device (spec A §52): the
     * model's reply is cut, "next" skips immediately with a sting, and the model's own tool call is then ignored.
     */
    private fun liveLocalCommand(text: String): Boolean {
        val action = localCommand(text) ?: return false
        if (action !in setOf(ConversationAction.SKIP, ConversationAction.RESUME_RADIO, ConversationAction.PAUSE)) return false
        localCommandDone = action to clock()
        steerJob?.cancel()
        live?.quiet()
        when (action) {
            ConversationAction.SKIP -> {
                dropInterrupted()
                clearOffer()
                endConversation()
                requestNextNow()
            }
            ConversationAction.RESUME_RADIO -> { interrupted = null; endConversation() }
            else -> doPause()
        }
        return true
    }

    /** Where an always-listening exchange returns when it goes quiet. */
    private var afterExchange = RadioState.RADIO

    /** The listener starts talking (or taps the mic) during the radio: stop the story and listen. */
    private fun startExchange(l: LiveConversation) {
        // Talking while paused (e.g. to a passenger) must not un-pause the radio when the exchange ends.
        afterExchange = if (_state.value.radioState == RadioState.PAUSED) RadioState.PAUSED else RadioState.RADIO
        noteInterrupted()
        speechJob?.cancel()
        pendingId = null
        storyPlayback = null
        engagedUntilMs = Long.MAX_VALUE
        setRadioState(RadioState.CONVERSING)
        l.beginExchange()
    }

    /**
     * Stops the live host talking. In always-listening mode the mic stays open (the connection just goes
     * quiet); otherwise the conversation ends.
     */
    private fun closeLive() {
        val l = live ?: return
        if (l.persistent && l.isOpen && handsFreeActive) {
            l.quiet()
            // The exchange is over (the caller plays something or moves on): drop its hold, or the radio would
            // wait for an exchange end that never comes (the host went quiet, so no idle callback follows).
            if (engagedUntilMs == Long.MAX_VALUE) engagedUntilMs = 0
            afterExchange = RadioState.RADIO
            return
        }
        live = null
        l.end()
    }

    /** Ends the live connection completely (stop, mic switched off). */
    private fun endLive() {
        val l = live ?: return
        live = null
        l.end()
    }

    private fun conversationRequest(utterance: String) = ConversationRequest(
        utterance = utterance,
        language = sessionLanguage,
        location = _state.value.location,
        area = _state.value.area,
        active = activeId?.let { id -> ranked.firstOrNull { it.place.id == id } },
        nearby = ranked.filter { it.place.id != activeId }.take(10),
        recentTitles = recentTitles.toList(),
        history = history.toList(),
        theme = _state.value.theme,
        profile = memory.promptLines(),
        style = config().style,
        tripContext = tripContext,
        pendingOffer = pendingOffer?.let { if (offerKind == OfferKind.DETOUR) "directions to ${it.name} (a short detour)" else it.name },
        tour = tourSummary(),
        quiz = programme.pendingQuiz,
    )

    private val liveHost = object : LiveHost {
        override fun liveInstructions() = RadioAgent.liveInstructions(conversationRequest(""))
        // The same voice as the stories (spec A §48), when the live model has it; otherwise the live default.
        override fun liveVoice() = config().let { c ->
            com.gpsradio.core.ai.RealtimeProtocol.liveVoice(c.voice.takeIf { it.lowercase() in com.gpsradio.core.ai.RealtimeProtocol.voices } ?: c.realtimeVoice)
        }
        override fun liveModel() = config().liveModel
        override fun transcriptionModel() = config().transcriptionModel
        override fun liveLanguage() = sessionLanguage

        override fun onUserSaid(text: String) {
            addTranscript(TranscriptEntry(Speaker.USER, text, clock()))
            if (liveLocalCommand(text)) return
            history += ConversationTurn(true, text)
            while (history.size > 24) history.removeAt(0)
            programme.onQuizResolved() // the live host has the quiz in context and answers it
        }

        override fun onAssistantSaid(text: String) {
            // The photo follows the place the host is talking about (spec A §51), and shows what it names (§62).
            focusOnMention(text)
            illustrateAnswer(text)
            addTranscript(TranscriptEntry(Speaker.RADIO, text, clock(), activeId))
            history += ConversationTurn(false, text)
            lastSpeechEndMs = clock()
        }

        override suspend fun callTool(name: String, arguments: JsonObject): String = liveTool(name, arguments)

        override fun onLiveState(state: LiveState?) {
            val previous = _state.value.live
            val standby = live?.let { it.persistent && !it.inConversation } == true
            _state.update { it.copy(live = state, listening = state != null && live?.persistent == true) }
            // In always-listening mode the connection opens silently; the sting marks a real conversation.
            if (state == LiveState.LISTENING && previous == LiveState.CONNECTING && !standby) scope.launch { sting(Sting.LISTENING) }
            if (state == LiveState.LISTENING && previous == LiveState.CONNECTING) standbyFailures = 0
            // Barge-in over the radio: the listener just started talking, so the story stops and the host listens.
            if (state == LiveState.USER_SPEAKING && _state.value.radioState != RadioState.CONVERSING) live?.let { startExchange(it) }
            // Already waiting for an answer with a deadline (a non-stop quiz): the listener is answering, so hold
            // until the exchange ends instead of starting the radio over the host after the deadline.
            else if (state == LiveState.USER_SPEAKING && engagedUntilMs != Long.MAX_VALUE) {
                engagedUntilMs = Long.MAX_VALUE
                live?.beginExchange()
            }
            if (state == null) {
                live = null
                // A steered story is still being researched: keep holding for it (it plays when ready).
                if (steerJob?.isActive == true) return
                if (_state.value.radioState == RadioState.CONVERSING) {
                    clearOffer()
                    endConversation()
                }
            }
        }

        override fun onLiveIdle() {
            // A steered story is being researched: keep holding the radio for it, or the next prepared story would
            // start now and then be cut off when the steered one is ready.
            if (steerJob?.isActive == true) return
            // The exchange is over: back to the radio, the mic stays open.
            if (_state.value.radioState == RadioState.CONVERSING) {
                clearOffer()
                engagedUntilMs = 0
                setRadioState(afterExchange)
            }
            afterExchange = RadioState.RADIO
        }

        override fun onLiveError(message: String) {
            val l = live
            // Out of credit: say so (the credit notice), and stop background reconnects until it's fixed.
            if (QuotaErrors.matches(message)) noteQuota()
            if (l != null && l.persistent && !l.inConversation) {
                // Background listening failed: retry later, quietly (1, 2, 4… up to 10 minutes).
                standbyFailures++
                standbyRetryAtMs = clock() + (60_000L shl (standbyFailures - 1).coerceAtMost(4)).coerceAtMost(600_000L)
                if (standbyFailures == 3) setStatus("Always listening is paused: the voice connection keeps failing. Tap the mic to talk.")
                return
            }
            fail("Voice conversation unavailable: $message. You can type your question instead.")
        }
    }

    private suspend fun liveTool(name: String, args: JsonObject): String {
        fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        return when (name) {
            "web_search" -> {
                val q = arg("query") ?: return "missing query"
                setStatus("Checking online…", StatusLevel.WORKING)
                try {
                    timed(timeouts.conversationMs, "Search") { narrator.webAnswer(q, sessionLanguage, _state.value.area) }
                } finally {
                    if (_state.value.status?.text == "Checking online…") setStatus(null)
                }
            }
            "radio_control" -> {
                when (arg("action")?.lowercase()) {
                    "steer" -> return steer(arg("request") ?: arg("theme") ?: return "missing request")
                    "tell_about" -> {
                        val id = arg("entity_id") ?: return "missing entity_id"
                        if (candidates[id] == null) return "unknown place: use steer with a request instead"
                        closeLive()
                        endConversation()
                        tellAbout(id)
                        return "done"
                    }
                }
                val action = ConversationAction.parse(arg("action"))
                // Already done on the device from the transcript: don't skip twice.
                if (handledLocally(action)) return "done"
                when (action) {
                    ConversationAction.RESUME_RADIO -> { steerJob?.cancel(); interrupted = null; closeLive(); endConversation() }
                    ConversationAction.PAUSE -> { closeLive(); doPause() }
                    ConversationAction.SKIP -> {
                        steerJob?.cancel()
                        requestNextNow()
                        dropInterrupted()
                        closeLive()
                        endConversation()
                    }
                    ConversationAction.ACCEPT_OFFER -> {
                        val offer = pendingOffer ?: return "there is no pending offer"
                        closeLive()
                        acceptOffer(offer)
                    }
                    ConversationAction.DECLINE_OFFER -> {
                        val offer = pendingOffer ?: return "there is no pending offer"
                        declineOffer(offer, speak = false)
                    }
                    ConversationAction.START_TOUR -> {
                        closeLive()
                        endConversation()
                        startTour(arg("minutes")?.toDoubleOrNull()?.toInt() ?: 30)
                    }
                    ConversationAction.END_TOUR -> clearTour()
                    else -> applyActionBeforeSpeaking(
                        ConversationReply(
                            reply = "",
                            action = action,
                            language = arg("language"),
                            theme = arg("theme")?.let { Topic.fromKey(it) },
                            entityId = arg("entity_id"),
                        ),
                    )
                }
                "done"
            }
            "remember" -> {
                arg("forget_text")?.let { memory.forget(it) }
                val cat = MemoryCategory.parse(arg("category"))
                val text = arg("text")
                if (cat != null && text != null) memory.remember(cat, text, arg("topic")?.let { Topic.fromKey(it) }, clock())
                persistMemory()
                rerank()
                "remembered"
            }
            "set_trip" -> {
                val trip = arg("summary") ?: return "missing summary"
                tripContext = trip
                _state.update { it.copy(tripContext = trip) }
                "noted"
            }
            else -> "unknown tool $name"
        }
    }

    private fun shouldTease(c: RankedCandidate): Boolean =
        config().canReply && teaserEligible(c) && storiesSinceTeaser >= 2 && !onDeviceNow() &&
            config().pacing != Pacing.NONSTOP // no answer window in non-stop

    private fun clearOffer() {
        pendingOffer = null
        offerKind = OfferKind.STORY
        _state.update { it.copy(pendingOffer = null, pendingOfferKind = OfferKind.STORY) }
    }

    /** After a worth-a-stop story (which ends with "want me to navigate there?"), wait for the answer. */
    private fun offerDetour(place: PlaceCandidate) {
        pendingOffer = place
        offerKind = OfferKind.DETOUR
        engagedUntilMs = clock() + if (config().pacing == Pacing.NONSTOP) NONSTOP_QUIZ_PAUSE_MS * 2 else offerWindowMs
        _state.update { it.copy(radioState = RadioState.CONVERSING, pendingOffer = place.name, pendingOfferKind = OfferKind.DETOUR) }
        if (liveVoiceEnabled) openLive(opening = null)
    }

    /** Hands navigation to the listener's maps app (detour card, Nearby list). */
    fun navigateTo(placeId: String) = scope.launch {
        val place = candidates[placeId] ?: return@launch
        if (pendingOffer?.id == placeId) clearOffer()
        addTranscript(TranscriptEntry(Speaker.SYSTEM, "Directions to ${place.name} opened in your maps app.", clock(), place.id))
        onNavigate(place)
    }

    private fun acceptOffer(offer: PlaceCandidate) {
        val kind = offerKind
        clearOffer()
        engagedUntilMs = 0
        if (kind == OfferKind.DETOUR) {
            addTranscript(TranscriptEntry(Speaker.SYSTEM, "Directions to ${offer.name} opened in your maps app.", clock(), offer.id))
            onNavigate(offer)
            return endConversation()
        }
        val c = ranked.firstOrNull { it.place.id == offer.id } ?: return endConversation()
        speakStory(c, allowTeaser = false)
    }

    private suspend fun declineOffer(offer: PlaceCandidate, speak: Boolean = true) {
        clearOffer()
        // "No" means not this one: don't offer it again this trip, but no topic penalty.
        heard.markHeard(offer.id, offer.name, clock())
        persistHeard()
        if (speak) endConversation()
    }

    /** Asks a driver once per session where they're heading, to shape stories along the route. */
    private fun maybeAskAboutTrip(): Boolean {
        val loc = _state.value.location ?: return false
        if (tripAsked || tripContext != null || loc.travelMode != TravelMode.DRIVING || !config().askAboutTrip || !config().canReply) return false
        // The question needs a model to understand the answer.
        if (config().previewMode || !isOnline()) return false
        tripAsked = true
        return askHost(HostLine.TRIP_QUESTION)
    }

    /**
     * Once per session, after a few stories and while little is known about the listener's taste, ask
     * what they'd like more of. The answer becomes memory through the conversation. Never a knowledge quiz.
     */
    private fun maybeAskPreferences(): Boolean {
        val cfg = config()
        if (preferencesAsked || !cfg.askPreferences || !cfg.canReply || cfg.pacing == Pacing.NONSTOP || cfg.previewMode || !isOnline()) return false
        if (recentTitles.size < 4 || memory.promptLines().size >= 3) return false
        preferencesAsked = true
        return askHost(HostLine.PREFERENCE_QUESTION)
    }

    private fun askHost(kind: HostLine): Boolean {
        if (liveVoiceEnabled) {
            // The live host asks in its natural voice and hears the answer hands-free.
            openLive(opening = kind.instruction)
            return true
        }
        speechJob = scope.launch {
            try {
                val cfg = config()
                val line = timed(timeouts.narrationMs, "Host line") { narrator.hostLine(kind, sessionLanguage, cfg.style) }
                // No line in the listener's language (model unavailable): skip the question rather than ask in English.
                if (line.isBlank()) {
                    setRadioState(RadioState.RADIO)
                    return@launch
                }
                val bytes = timed(timeouts.speechMs, "Speech") { speech.synthesize(line, sessionLanguage, cfg.style) }
                addTranscript(TranscriptEntry(Speaker.RADIO, line, clock()))
                setRadioState(RadioState.CONVERSING)
                audio.play(bytes)
                lastSpeechEndMs = clock()
                engagedUntilMs = clock() + offerWindowMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setRadioState(RadioState.RADIO)
            }
        }
        return true
    }

    private fun loadGallery(place: PlaceCandidate) {
        galleries[place.id]?.let { updateFocusGallery(place.id); return }
        scope.launch {
            val article = runCatching { places.gallery(place) }.getOrDefault(emptyList())
            // Few article photos: add photos taken right around the place (Commons), for a fuller slideshow.
            val near = if (article.size < 5) runCatching { places.photosNear(place.point, 150) }.getOrDefault(emptyList()) else emptyList()
            val g = (article + near).distinctBy { it.substringAfterLast('/').substringAfter("px-") }.take(8)
            if (g.isEmpty()) return@launch
            galleries[place.id] = g
            updateFocusGallery(place.id)
            val c = runCatching { places.photoCredits(g) }.getOrDefault(emptyMap())
            if (c.isEmpty()) return@launch
            photoCredits[place.id] = photoCredits[place.id].orEmpty() + c
            updateFocusGallery(place.id)
        }
    }

    /** Shows the known place [text] talks about, if it names one (spec A §51). */
    private fun focusOnMention(text: String) {
        val place = PlaceMentions.find(text, ranked.map { it.place } + candidates.values.filter { c -> ranked.none { it.place.id == c.id } })
            ?: return
        if (_state.value.focus?.id == place.id) return
        mentionedIds += place.id
        _state.update { it.copy(focus = FocusPlace.of(place)) }
        loadGallery(place)
    }

    /**
     * The photo for an area story (spec A §51): a nearby place it names, else the Wikipedia photo of its subject,
     * else of the town; else the map (never the previous story's photo). Resolved while the story plays.
     */
    private fun focusOnSubject(segment: Segment, facet: com.gpsradio.core.discovery.AreaFacet, loc: LocationContext) {
        val mentioned = PlaceMentions.find(listOfNotNull(facet.subject, facet.title, segment.text).joinToString(" "), candidates.values.toList())
        if (mentioned != null) {
            _state.update { it.copy(focus = FocusPlace.of(mentioned)) }
            loadGallery(mentioned)
            addSlides(mentioned.id, facet.related)
            illustrate(mentioned.id, segment.text, 0)
            return
        }
        // Until a photo is found: the map of where the listener is, titled with the story.
        val id = "area:${facet.id}"
        _state.update { it.copy(focus = FocusPlace(id, facet.title ?: facet.area, loc.point, null, facet.url)) }
        illustrate(id, segment.text, 0)
        addSceneryIfSparse(id)
        scope.launch {
            val lang = langBase(sessionLanguage)
            val wikiTitle = facet.url?.takeIf { "wikipedia.org/wiki/" in it }?.substringAfter("/wiki/")?.replace('_', ' ')
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val tries = listOfNotNull(
                facet.subject?.let { "en" to it },
                wikiTitle?.let { (facet.url!!.substringAfter("//").substringBefore('.')) to it },
                facet.area.let { lang to it },
                facet.area.let { "en" to it },
            ).distinct()
            addSlides(id, facet.related)
            for ((l, title) in tries) {
                val photo = runCatching { places.articlePhoto(l, title) }.getOrNull() ?: continue
                _state.update { s ->
                    if (s.focus?.id != id) s else s.copy(focus = s.focus.copy(imageUrl = photo.second, url = s.focus.url ?: photo.third))
                }
                // A slideshow of the subject: its lead photo, then more views from its article.
                val more = runCatching { places.articleGallery(l, photo.first) }.getOrDefault(emptyList())
                // And Commons photos of the subject, when the article has few.
                val commons = if (more.size < 4) runCatching { places.photosOf(photo.first) }.getOrDefault(emptyList()) else emptyList()
                val g = (listOf(photo.second) + more + commons).distinctBy { it.substringAfterLast('/').substringAfter("px-") }.take(8)
                galleries[id] = g
                updateFocusGallery(id)
                val credit = runCatching { places.photoCredits(g) }.getOrDefault(emptyMap())
                if (credit.isNotEmpty()) {
                    photoCredits[id] = photoCredits[id].orEmpty() + credit
                    updateFocusGallery(id)
                }
                return@launch
            }
        }
    }

    /** Photo credits per place id (spec A §45). */
    private val photoCredits = HashMap<String, Map<String, String>>()

    /** When each slide is due (epoch ms), per focus id (spec A §62). */
    private val timelines = HashMap<String, LinkedHashMap<String, Long>>()

    /** Pictures for a segment: the synced finder when there is one, else the story's own "pictures" list. */
    private fun illustrateOrSlides(id: String, segment: Segment, leadMs: Long) {
        if (pictureFinder != null) illustrate(id, segment.text, leadMs) else addSlides(id, segment.pictures)
    }

    /** Pictures for an answer (live or typed): on the current focus, or a new one where the listener is. */
    private fun illustrateAnswer(text: String) {
        if (pictureFinder == null || text.length < 40) return
        val id = _state.value.focus?.id ?: run {
            val point = _state.value.location?.point ?: return
            val fid = "answer:${clock()}"
            _state.update { it.copy(focus = FocusPlace(fid, _state.value.area?.city ?: "", point, null, null)) }
            fid
        }
        illustrate(id, text, 0)
        addSceneryIfSparse(id)
    }

    /**
     * Finds photos of the people, buildings, objects, views and scenes [text] names (spec A §62), each with a caption,
     * and schedules each for when its mention is spoken ([leadMs] from now, then by where it is in the text).
     */
    private fun illustrate(id: String, text: String, leadMs: Long) {
        val finder = pictureFinder ?: return
        val startMs = clock() + leadMs
        scope.launch {
            val refs = runCatching { finder.find(text, sessionLanguage, _state.value.area) }.getOrDefault(emptyList())
            for (ref in refs) {
                // Wikipedia's photo of it; else a search near the listener, then anywhere, then openly licensed photos.
                var credit: String? = null
                val url = ref.wikipedia?.let { t -> runCatching { places.articlePhoto("en", t) }.getOrNull()?.second }
                    ?: ref.search.takeIf { it.isNotBlank() }?.let { q ->
                        runCatching { places.findPhoto(q, _state.value.location?.point) }.getOrNull()?.also { credit = it.second }?.first
                    }
                    ?: continue
                credit?.let { c -> photoCredits[id] = photoCredits[id].orEmpty() + (url to c) }
                slides.getOrPut(id) { LinkedHashMap() }[url] = ref.caption
                val at = com.gpsradio.core.ai.PictureScout.position(text, ref.quote)
                if (at != null) timelines.getOrPut(id) { LinkedHashMap() }[url] = startMs + at * 1000L / SPOKEN_CHARS_PER_SEC - 300
                updateFocusGallery(id)
                if (credit == null) {
                    val commons = runCatching { places.photoCredits(listOf(url)) }.getOrDefault(emptyMap())
                    if (commons.isNotEmpty()) {
                        photoCredits[id] = photoCredits[id].orEmpty() + commons
                        updateFocusGallery(id)
                    }
                }
            }
        }
    }

    /**
     * Too few pictures for what's being said: add photos taken around the listener (spec A §63), captioned with
     * what the file says or the town's name.
     */
    private fun addSceneryIfSparse(id: String) {
        val point = _state.value.location?.point ?: return
        scope.launch {
            kotlinx.coroutines.delay(SCENERY_AFTER_MS) // after the story's own pictures had their chance
            val have = (galleries[id].orEmpty() + slides[id]?.keys.orEmpty()).distinct().size
            if (have >= 3) return@launch
            val around = runCatching { places.photosNear(point, 1_500) }.getOrDefault(emptyList()).take(4 - have)
            if (around.isEmpty()) return@launch
            val town = _state.value.area?.city.orEmpty()
            around.forEach { url -> slides.getOrPut(id) { LinkedHashMap() }[url] = PhotoCaptions.fromUrl(url) ?: town }
            updateFocusGallery(id)
            val c = runCatching { places.photoCredits(around) }.getOrDefault(emptyMap())
            if (c.isNotEmpty()) { photoCredits[id] = photoCredits[id].orEmpty() + c; updateFocusGallery(id) }
        }
    }

    /** Slideshow photos per focus id: people, buildings and views the story names (URL to caption), spec A §55. */
    private val slides = HashMap<String, LinkedHashMap<String, String>>()

    /** Adds the Wikipedia lead photos of [titles] to the slideshow of the focus [id], as they arrive. */
    private fun addSlides(id: String, titles: List<String>) {
        if (titles.isEmpty()) return
        scope.launch {
            for (title in titles) {
                val photo = runCatching { places.articlePhoto("en", title) }.getOrNull() ?: continue
                slides.getOrPut(id) { LinkedHashMap() }[photo.second] = photo.first
                updateFocusGallery(id)
                val credit = runCatching { places.photoCredits(listOf(photo.second)) }.getOrDefault(emptyMap())
                if (credit.isNotEmpty()) {
                    photoCredits[id] = photoCredits[id].orEmpty() + credit
                    updateFocusGallery(id)
                }
            }
        }
    }

    /** Publishes the focus's photos: its own gallery, then the story's slides, with credits and captions. */
    private fun updateFocusGallery(id: String) = _state.update { s ->
        if (s.focus?.id != id) return@update s
        val own = galleries[id] ?: listOfNotNull(s.focus.imageUrl)
        val extra = slides[id].orEmpty()
        val gallery = (own + extra.keys).distinct()
        // Every photo has a caption: the story's own, else what the file says it shows, else the place's name.
        val captions = gallery.associateWith { url -> extra[url] ?: PhotoCaptions.fromUrl(url) ?: s.focus.name }.filterValues { it.isNotBlank() }
        s.copy(focus = s.focus.copy(gallery = gallery, credits = photoCredits[id].orEmpty(), captions = captions, timeline = timelines[id].orEmpty().toMap()))
    }

    private fun persistFavorites() {
        runCatching { favoritesStore?.save(favorites.serialize()) }
        _state.update { it.copy(favorites = favorites.all) }
    }

    private fun doPause() {
        steerJob?.cancel()
        closeLive()
        if (speechJob?.isActive == true) lastSpeechEndMs = clock()
        speechJob?.cancel()
        // An interrupted story is not marked heard, so it stays a candidate and can air again.
        pendingId = null
        if (_state.value.radioState != RadioState.IDLE) setRadioState(RadioState.PAUSED)
    }

    /** Implicit personalization: feed a listening signal into the learned interests. */
    private fun learn(signal: InterestModel.Signal, place: PlaceCandidate) {
        if (!interestModel.record(signal, place.topics, clock(), key = place.id)) return
        runCatching { interestStore?.save(interestModel.serialize(clock())) }
    }

    private fun penalize(place: PlaceCandidate) {
        heard.markHeard(place.id, place.name, clock())
        persistHeard()
        place.topics.forEach { t -> topicPenalty[t] = ((topicPenalty[t] ?: 1.0) * 0.6).coerceAtLeast(0.1) }
    }

    /**
     * Transient failures (bad key, rate limit, server error, offline, timeout) back off
     * exponentially instead of blacklisting candidates one by one.
     */
    private fun handleFailure(e: Exception, placeId: String?, what: String) {
        val transient = (e is OpenAiException && e.isTransient) || e is IOException || e is TimeoutException
        if (transient) {
            backoffMs = (backoffMs * 2).coerceIn(15_000L, 300_000L)
            nextNarrationAllowedMs = clock() + backoffMs
        } else {
            placeId?.let { failedIds += it }
        }
        fail("$what: ${e.message}")
    }

    private suspend fun <T : Any> timed(ms: Long, what: String, block: suspend () -> T): T =
        withTimeoutOrNull(ms) { block() } ?: throw TimeoutException("$what timed out after ${ms / 1000} s")

    private fun persistMemory() {
        runCatching { memoryStore?.save(memory.serialize()) }
        _state.update { it.copy(memory = memory.all) }
    }

    private fun persistHeard() {
        runCatching { historyStore.save(heard.serialize(clock())) }
    }

    /** Until then, the listener asked for the next story: no pacing gap before it (spec A §52). */
    private var nextNowUntilMs = 0L

    private fun nextNow() = clock() < nextNowUntilMs

    /** An explicit "next": the next segment airs as soon as it's ready, not after the usual gap. */
    private fun requestNextNow() {
        nextNowUntilMs = clock() + NEXT_NOW_WINDOW_MS
        // Seen at once, whatever asked for it (voice, notification, headset); heard as the next story's station sting.
        // (Never over a note that asks the listener to act, like "Add key".)
        if (_state.value.radioState != RadioState.IDLE && _state.value.radioState != RadioState.PAUSED && _state.value.status?.needsKey != true) {
            setStatus(NEXT_STATUS, StatusLevel.WORKING)
        }
        wake.trySend(Unit)
    }

    private fun setRadioState(s: RadioState) {
        if (s == RadioState.NARRATING) {
            cueJob?.cancel()
            firstStoryPending = false
            nextNowUntilMs = 0
            if (_state.value.status?.text == NEXT_STATUS) setStatus(null)
        }
        val before = _state.value.radioState
        _state.update { it.copy(radioState = s) }
        // Non-stop: when a segment ends, look for the next one right after the gap, not at the next 3 s tick.
        if (s == RadioState.RADIO && before == RadioState.NARRATING && config().pacing == Pacing.NONSTOP) wakeAfterGap()
    }

    /** Wakes the scheduler early (non-stop continuity). */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var wakeJob: Job? = null

    private fun wakeAfterGap() {
        val loc = _state.value.location ?: return
        val pacing = config().pacing
        val minGap = ranker.minGapMs(loc.travelMode, pacing)
        val gap = maxOf(minGap, programme.storyGapMs(loc.travelMode, minGap, pacing))
        wakeJob?.cancel()
        wakeJob = scope.launch {
            delay(gap + 50)
            wake.trySend(Unit)
        }
    }

    /** Clearing the status falls back to the preview-mode note while there is no key. */
    private fun setStatus(msg: String?, level: StatusLevel = StatusLevel.INFO) =
        _state.update { it.copy(status = msg?.let { m -> Status(m, level) } ?: previewNote()) }

    private fun previewNote(): Status? = when {
        _state.value.quotaExhausted -> quotaStatus()
        config().previewMode -> Status(PREVIEW_NOTE, StatusLevel.INFO, needsKey = true, actionLabel = "Add key")
        else -> null
    }

    // ---- out of OpenAI credit -------------------------------------------------------------------

    private var quotaAnnounced = false
    /** OpenAI answered 401/403: the key is wrong (not an outage). */
    private var keyRejected = false
    /** Places skipped while on-device because their notes aren't in the listener's language (told once online). */
    private val deviceSkipped = HashSet<String>()
    /** The "offline / OpenAI unreachable, short notes for now" notice was spoken this episode. */
    private var degradedAnnounced = false
    /** The daily-cap notice was said (once per session). */
    private var budgetAnnounced = false

    private fun isQuota(e: Throwable): Boolean =
        (e is OpenAiException && e.isQuotaExhausted) || QuotaErrors.matches(e.message)

    private fun quotaStatus(): Status =
        Status(if (config().usingBuiltInKey) QUOTA_BUILT_IN else QUOTA_OWN_KEY, StatusLevel.ERROR, needsKey = true, actionLabel = "Add key")

    /**
     * Tells the listener: a persistent status with an "Add key" action, a transcript line (not repeated
     * back to back), and [RadioUiState.quotaExhausted] for the app's notification.
     */
    private fun noteQuota() {
        val status = quotaStatus()
        _state.update { it.copy(quotaExhausted = true, status = status) }
        if (_state.value.transcript.lastOrNull()?.text != status.text) addTranscript(TranscriptEntry(Speaker.SYSTEM, status.text, clock()))
    }

    private fun clearQuota() {
        if (!_state.value.quotaExhausted) return
        quotaAnnounced = false
        _state.update { it.copy(quotaExhausted = false) }
        setStatus(null)
    }

    /** Questions need OpenAI: in preview mode or offline, say so (once per question) and return true. */
    private fun questionsUnavailable(): Boolean {
        val preview = config().previewMode
        val msg = when {
            preview -> PREVIEW_QUESTIONS
            !isOnline() -> OFFLINE_QUESTIONS
            config().budgetReached -> BUDGET_NOTE
            else -> return false
        }
        _state.update { it.copy(status = Status(msg, StatusLevel.INFO, needsKey = preview, actionLabel = if (preview) "Add key" else null)) }
        addTranscript(TranscriptEntry(Speaker.SYSTEM, msg, clock()))
        // They may have asked by voice without looking at the screen: say it too.
        announce(if (preview) Notice.QUESTIONS_NEED_KEY else Notice.QUESTIONS_OFFLINE)
        return true
    }

    // ---- spoken notices: voice is the main channel (spec A §32) ------------------------------------

    /** Speaks [notice] after whatever is playing now; skipped while stopped or paused. */
    /** The last story aired, for "where's that from?" (spec A §45). */
    private var lastStory: Segment? = null

    private fun announce(notice: Notice) = announceText(Notices.text(notice, sessionLanguage))

    private fun announceText(text: String) {
        val previous = speechJob
        // Stored as the speech job: the scheduler waits for it, so a story never starts over a notice.
        val job = scope.launch {
            previous?.join()
            if (_state.value.radioState == RadioState.IDLE || _state.value.radioState == RadioState.PAUSED) return@launch
            speakNotice(text)
        }
        // Cancelling the speech job (skip, pause, the listener talking) must also stop what the notice waits behind,
        // or that story would play on under whatever comes next.
        job.invokeOnCompletion { cause -> if (cause is CancellationException) previous?.cancel() }
        speechJob = job
    }

    /**
     * The host's voice when OpenAI is usable, otherwise the phone's own voice (works offline and out of
     * credit). Never fails the caller: a notice that can't be voiced stays on screen.
     */
    private suspend fun speakNotice(text: String) {
        val lang = sessionLanguage
        val style = config().style
        val primaryOk = !onDeviceNow() && isOnline() && !config().previewMode && !_state.value.quotaExhausted
        val bytes = try {
            if (primaryOk) timed(timeouts.speechMs, "Speech") { speech.synthesize(text, lang, style) }
            else fallbackSpeech?.let { f -> timed(timeouts.speechMs, "Speech") { f.synthesize(text, lang, style) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { fallbackSpeech?.synthesize(text, lang, style) }.getOrNull()
        } ?: return
        try {
            audio.play(bytes)
            lastSpeechEndMs = clock()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Audio busy (e.g. a call): the notice is still on screen.
        }
    }

    private fun fail(msg: String) {
        if (QuotaErrors.matches(msg)) {
            noteQuota()
            return
        }
        val keyProblem = "API key" in msg || Regex("\\b(401|403)\\b").containsMatchIn(msg) && "OpenAI" in msg
        val friendly = if (keyProblem) "OpenAI didn't accept the API key. Check it in Settings." else msg
        _state.update { it.copy(status = Status(friendly, StatusLevel.ERROR, needsKey = keyProblem)) }
        addTranscript(TranscriptEntry(Speaker.SYSTEM, friendly, clock()))
    }

    private fun addTranscript(e: TranscriptEntry) =
        _state.update { it.copy(transcript = (it.transcript + e).takeLast(100)) }

    // ---- stings, journal, walking tour ------------------------------------------------------

    /** Plays a sound effect when enabled; never fails the caller. */
    private suspend fun sting(kind: Sting) {
        val player = stings ?: return
        if (!config().soundEffects) return
        try {
            player.play(kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing earcon is not worth an error.
        }
    }

    private fun recordJournal(place: PlaceCandidate, segment: Segment) {
        journal.record(place.id, place.name, place.point, segment.text, place.url, clock())
        runCatching { journalStore?.save(journal.serialize()) }
        _state.update { it.copy(journal = journal.all) }
    }

    /** "Tell me again" from the journal: re-narrate a place still around, otherwise ask the host about it. */
    fun retell(placeId: String, name: String) = scope.launch {
        steerJob?.cancel()
        if (_state.value.radioState == RadioState.IDLE) return@launch
        val place = candidates[placeId]
        val loc = _state.value.location
        if (place != null && loc != null) {
            closeLive()
            clearOffer()
            speechJob?.cancel()
            speakStory(rankedFor(place, loc), allowTeaser = false)
        } else {
            ask("Tell me again about $name, please.")
        }
    }

    /** Plans and starts a walking mini-tour of about [minutes] ("give me 30 minutes"). */
    fun startTour(minutes: Int) = scope.launch { beginTour(minutes.coerceIn(10, 120)) }

    fun endTour() = scope.launch { clearTour() }

    private fun beginTour(minutes: Int) {
        steerJob?.cancel()
        if (_state.value.radioState == RadioState.IDLE) return
        val loc = _state.value.location ?: processor.current
        if (loc == null) {
            setStatus("Waiting for GPS to plan a tour…")
            announce(Notice.TOUR_NO_GPS)
            return
        }
        rerank()
        val plan = tourPlanner.plan(ranked, loc.point, minutes)
        if (plan == null) {
            setStatus("Not enough sights nearby for a $minutes-minute tour yet.")
            announce(Notice.TOUR_TOO_FEW_SIGHTS)
            return
        }
        closeLive()
        speechJob?.cancel()
        pendingId = null
        prefetchJob?.cancel()
        prefetched = null
        clearOffer()
        engagedUntilMs = 0
        // A quiz answer would otherwise wait until the tour ends, out of context.
        programme.onQuizResolved()
        val t = TourState.of(plan)
        tour = t
        tourHintDue = false
        tourStartedMs = clock()
        tourAwaySinceMs = null
        _state.update { it.copy(tour = t, radioState = RadioState.RADIO, status = null) }
        speakTourLine(HostLine.TOUR_INTRO, TourText.intro(t, loc.point, heading(loc)))
    }

    private fun clearTour() {
        tour = null
        tourHintDue = false
        tourAwaySinceMs = null
        tourStopInFlight = null
        _state.update { it.copy(tour = null) }
    }

    /**
     * The listener has moved on: they started driving or cycling, stayed over [TOUR_AWAY_M] from the
     * next stop for [TOUR_AWAY_MS], or the tour ran past twice its planned length.
     */
    private fun tourAbandoned(t: TourState, loc: LocationContext, next: TourStop?): Boolean {
        val now = clock()
        if (loc.travelMode == TravelMode.DRIVING || loc.travelMode == TravelMode.CYCLING) return true
        if (now - tourStartedMs > t.minutes * 2 * 60_000L) return true
        if (next != null && Geo.distanceM(loc.point, next.point) > TOUR_AWAY_M) {
            val since = tourAwaySinceMs ?: now.also { tourAwaySinceMs = it }
            return now - since > TOUR_AWAY_MS
        }
        tourAwaySinceMs = null
        return false
    }

    private fun heading(loc: LocationContext): Double? = loc.headingDeg?.takeIf { loc.travelMode != TravelMode.STATIONARY }

    /** Scheduler step during a tour: directions after a stop, then the next stop's story on arrival. */
    private fun tourTick() {
        val t = tour ?: return
        if (_state.value.radioState != RadioState.RADIO || speechJob?.isActive == true || clock() < engagedUntilMs) return
        val loc = _state.value.location ?: return
        val next = t.next
        if (tourAbandoned(t, loc, next)) {
            clearTour()
            setStatus(TOUR_ABANDONED)
            announce(Notice.TOUR_ABANDONED)
            addTranscript(TranscriptEntry(Speaker.SYSTEM, TOUR_ABANDONED, clock()))
            return
        }
        if (tourHintDue) {
            if (next == null) {
                val draft = TourText.finish(t, loc.point, heading(loc))
                clearTour()
                speakTourLine(HostLine.TOUR_END, draft)
                return
            }
            tourHintDue = false
            if (Geo.distanceM(loc.point, next.point) > arrivalRadiusM) {
                speakTourLine(HostLine.TOUR_NEXT, TourText.next(next, loc.point, heading(loc)))
                return
            }
        }
        // Arrival at the next stop, or at a later one if the listener walked there directly.
        val arrived = (t.nextIndex until t.stops.size).firstOrNull { Geo.distanceM(loc.point, t.stops[it].point) <= arrivalRadiusM }
        if (arrived != null) speakTourStop(arrived)
    }

    private fun speakTourLine(kind: HostLine, draft: String) {
        speechJob = scope.launch {
            val cfg = config()
            val lang = sessionLanguage
            val english = RadioAgent.isEnglish(lang)
            val line = try {
                timed(timeouts.narrationMs, "Host line") { narrator.hostLine(kind, draft, lang, cfg.style) }.ifBlank { if (english) draft else "" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (english) draft else ""
            }
            // The draft is English: without a translation, say nothing rather than switch language.
            if (line.isBlank()) {
                lastSpeechEndMs = clock()
                scope.launch { tourTick() }
                return@launch
            }
            addTranscript(TranscriptEntry(Speaker.RADIO, line, clock()))
            _state.update { it.copy(radioState = RadioState.NARRATING, nowPlaying = Segment(line, null, "Walking tour", emptyList())) }
            try {
                val bytes = timed(timeouts.speechMs, "Speech") { speech.synthesize(line, lang, cfg.style) }
                audio.play(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Speech failed: ${e.message}")
            }
            lastSpeechEndMs = clock()
            setRadioState(RadioState.RADIO)
            scope.launch { tourTick() }
        }
    }

    private fun speakTourStop(index: Int) {
        val t = tour ?: return
        val loc = _state.value.location ?: return
        val stop = t.stops[index]
        val advanced = t.copy(nextIndex = index + 1)
        tour = advanced
        tourHintDue = true
        tourStopInFlight = index
        _state.update { it.copy(tour = advanced) }
        val c = rankedFor(stop.place, loc)
        speechJob = scope.launch {
            try {
                tellTourStop(c, loc)
            } finally {
                // Interrupted (pause, hold-to-talk) before the story was heard: tell this stop again later.
                val cur = tour
                if (tourStopInFlight == index && cur != null && cur.nextIndex == index + 1) {
                    val back = cur.copy(nextIndex = index)
                    tour = back
                    tourHintDue = false
                    _state.update { it.copy(tour = back) }
                }
                tourStopInFlight = null
            }
        }
    }

    private suspend fun tellTourStop(c: RankedCandidate, loc: LocationContext) {
        run {
            pendingId = c.place.id
            val lang = sessionLanguage
            setRadioState(RadioState.RESEARCHING)
            try {
                coroutineScope {
                    // "You're standing in front of it": a short second chapter, prepared while the story plays.
                    val chapter = if ((c.place.extract?.length ?: 0) >= arrivalChapterMinChars) {
                        async { prepareOrNull(c, loc, lang, SegmentFormat.ARRIVAL) }
                    } else null
                    val (segment, bytes) = prepare(c, loc, lang)
                    activeId = c.place.id
                    lastAudio = bytes
                    addTranscript(TranscriptEntry(Speaker.RADIO, segment.text, clock(), c.place.id, segment.sources))
                    lastStory = segment
                    _state.update { it.copy(radioState = RadioState.NARRATING, nowPlaying = segment, focus = FocusPlace.of(c.place)) }
                    loadGallery(c.place)
                    illustrateOrSlides(c.place.id, segment, STING_LEAD_MS)
                    sting(Sting.STATION)
                    audio.play(bytes)
                    tourStopInFlight = null
                    heard.markHeard(c.place.id, c.place.name, clock())
                    persistHeard()
                    recordJournal(c.place, segment)
                    recentTitles += c.place.name
                    pendingId = null
                    chapter?.await()?.let { (more, moreBytes) ->
                        addTranscript(TranscriptEntry(Speaker.RADIO, more.text, clock(), c.place.id, more.sources))
                        _state.update { it.copy(nowPlaying = more) }
                        lastAudio = moreBytes
                        audio.play(moreBytes)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failure moves on to the next stop (no endless retries of a broken one).
                tourStopInFlight = null
                fail("Couldn't tell the story of ${c.place.name}: ${e.message}")
            }
            pendingId = null
            lastSpeechEndMs = clock()
            setRadioState(RadioState.RADIO)
            scope.launch { tourTick() }
        }
    }

    private suspend fun prepareOrNull(c: RankedCandidate, loc: LocationContext, lang: String, format: SegmentFormat): Pair<Segment, ByteArray>? =
        try {
            prepare(c, loc, lang, format)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** A ranked view of [place] for narration, even when it is no longer in (or filtered out of) the ranking. */
    private fun rankedFor(place: PlaceCandidate, loc: LocationContext): RankedCandidate {
        val known = ranked.firstOrNull { it.place.id == place.id }
        return RankedCandidate(
            place = place,
            distanceM = Geo.distanceM(loc.point, place.point),
            bearingDeg = Geo.bearingDeg(loc.point, place.point),
            score = known?.score ?: 0.0,
            breakdown = known?.breakdown ?: ScoreBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
    }

    /** One line for the model about the active tour, e.g. "stop 2 of 5, next: Castle, about 250 metres ahead". */
    private fun tourSummary(): String? {
        val t = tour ?: return null
        val next = t.next ?: return "all ${t.stops.size} stops done, closing the tour"
        val loc = _state.value.location
        val way = loc?.let { ", " + TourText.way(it.point, heading(it), next.point) } ?: ""
        return "stop ${t.nextIndex + 1} of ${t.stops.size}, next: ${next.name}$way; stops: " + t.stops.joinToString(", ") { it.name }
    }

    // ---- programme: fillers between stories (see Programme) --------------------------------

    /** Non-stop "widen the search" multiplier for the discovery radius. */
    private var radiusBoost = 1.0
    private var areaFacets: List<AreaFacet> = emptyList()
    private var areaKey: String? = null
    private var areaJob: Job? = null

    /** Consults the running order; true when it takes this tick (a filler, a quiz reveal, or a pacing wait). */
    private fun runProgramme(now: Long): Boolean {
        val loc = _state.value.location ?: return false
        val cfg = config()
        loadAreaFacts()
        val situation = Programme.Situation(
            nowMs = now,
            mode = loc.travelMode,
            pacing = cfg.pacing,
            minGapMs = ranker.minGapMs(loc.travelMode, cfg.pacing),
            lastSpeechEndMs = lastSpeechEndMs.takeUnless { nextNow() },
            storyReady = ranker.storyReady(ranked, cfg.pacing),
            ranked = ranked,
            recentTitles = recentTitles.toList(),
            onThisDayAvailable = onThisDay != null,
            dayKey = dayKey(today()),
            themeActive = _state.value.theme != null,
            speakThreshold = ranker.thresholdFor(cfg.pacing),
            areaFacets = areaFacets + currentResearched(),
            photoSpot = ranked.firstOrNull { it.breakdown.novelty > 0.0 && it.place.id !in mentionedIds && PhotoSpots.suitable(it, loc) },
            // Events have no on-device version: while OpenAI is out they'd be skipped every tick and block stories.
            eventsDue = !onDeviceNow() && eventsToAnnounce(now).isNotEmpty(),
        )
        return when (val plan = programme.next(situation)) {
            Programme.Plan.None -> false
            Programme.Plan.Wait -> true
            is Programme.Plan.RevealQuiz -> { revealQuiz(plan.quiz); true }
            is Programme.Plan.Filler -> { speakFiller(plan, loc); true }
            is Programme.Plan.RelaxedStory -> { speakStory(plan.candidate); true }
            Programme.Plan.WidenSearch -> {
                programme.onWidened()
                radiusBoost = (radiusBoost * 2).coerceAtMost(8.0)
                lastRefreshPoint = null
                maybeRefresh(loc) // re-discover now with the wider radius; its end schedules the next tick
                true
            }
        }
    }

    /** Fetches the town's (and region's) article once per area and language, in the background. */
    private fun loadAreaFacts() {
        val source = areaInfo ?: return
        val area = _state.value.area ?: return
        val lang = langBase(sessionLanguage)
        val key = "$lang|${area.city}|${area.region}"
        if (key == areaKey || areaJob?.isActive == true) return
        areaKey = key
        areaJob = scope.launch {
            val facets = listOfNotNull(area.city, area.region).distinct().flatMap { name ->
                val article = runCatching { source.article(lang, name) }.getOrNull()
                    ?: runCatching { if (lang != "en") source.article("en", name) else null }.getOrNull()
                article?.let { AreaFacts.facets(name, it) }.orEmpty()
            }
            areaFacets = facets
        }
    }

    private fun speakFiller(plan: Programme.Plan.Filler, loc: LocationContext) {
        val c = plan.candidate
        val day = today()
        // Fillers have no on-device version: while OpenAI is out (offline, resting, preview) skip them
        // instead of waiting for timeouts on air.
        if (onDeviceNow()) {
            programme.onFillerFailed(plan.format, c?.place?.id, clock(), dayKey(day), plan.areaFacet?.id)
            return
        }
        speechJob = scope.launch {
            val cfg = config()
            val lang = sessionLanguage
            try {
                val ready = if (plan.format == SegmentFormat.AREA) takePreparedArea(plan.areaFacet?.id, lang) else null
                if (ready == null) setRadioState(RadioState.RESEARCHING)
                val segment = ready?.first ?: timed(timeouts.narrationMs, "Narration") {
                    when (plan.format) {
                        SegmentFormat.ON_THIS_DAY -> {
                            val events = onThisDay?.events(langBase(lang), day.monthValue, day.dayOfMonth).orEmpty()
                            val event = OnThisDayClient.pick(events, _state.value.area, loc.point, langBase(lang))
                                ?: throw IllegalStateException("no on-this-day event")
                            val date = day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + day.dayOfMonth
                            narrator.narrateFiller(
                                FillerRequest(
                                    plan.format, lang, loc, _state.value.area, cfg.style, event = event, dateLabel = date,
                                    profile = memory.promptLines(), tripContext = tripContext,
                                ),
                            )
                        }
                        SegmentFormat.AREA -> narrator.narrateFiller(
                            FillerRequest(
                                plan.format, lang, loc, _state.value.area, cfg.style, areaFacet = plan.areaFacet,
                                areaToldFacets = programme.toldFacets, profile = memory.promptLines(), tripContext = tripContext,
                            ),
                        )
                        SegmentFormat.EVENTS -> {
                            // The segment names at most three; the rest stay due for a later one.
                            val due = eventsToAnnounce(clock()).take(3)
                            // Announced once, even if this segment fails (no retry loop on a flaky model).
                            due.forEach { announcedEvents += it.id }
                            if (due.isEmpty()) throw IllegalStateException("no events to announce")
                            narrator.narrateFiller(
                                FillerRequest(plan.format, lang, loc, _state.value.area, cfg.style, events = due, nowMs = clock(), zone = zone()),
                            )
                        }
                        SegmentFormat.STATION_ID -> narrator.narrateFiller(
                            FillerRequest(
                                plan.format, lang, loc, _state.value.area, cfg.style, recap = recentTitles.toList(),
                                profile = memory.promptLines(), tripContext = tripContext,
                            ),
                        )
                        else -> narrator.narrate(
                            NarrationRequest(
                                c ?: throw IllegalStateException("${plan.format} needs a place"), loc, lang, cfg.interests,
                                recentTitles.toList(), memory.promptLines(), style = cfg.style, format = plan.format,
                                tripContext = tripContext,
                            ),
                        )
                    }
                }
                // A quiz question whose answer couldn't be parsed would never be resolved: drop it.
                if (plan.format == SegmentFormat.QUIZ && segment.quizAnswer.isNullOrBlank()) throw IllegalStateException("quiz without an answer")
                val bytes = ready?.second ?: timed(timeouts.speechMs, "Speech") { speech.synthesize(segment.text, lang, cfg.style) }
                lastAudio = bytes
                addTranscript(TranscriptEntry(Speaker.RADIO, segment.text, clock(), segment.entityId, segment.sources))
                lastStory = segment
                _state.update { s ->
                    s.copy(radioState = RadioState.NARRATING, nowPlaying = segment, focus = c?.let { FocusPlace.of(it.place) } ?: s.focus)
                }
                c?.let { loadGallery(it.place); illustrateOrSlides(it.place.id, segment, 0) }
                // An area story shows what it is about, never the previous place's photo (spec A §51).
                if (c == null) plan.areaFacet?.let { facet -> focusOnSubject(segment, facet, loc) }
                // Any other segment without a place (events, on this day…): pictures for what it says.
                if (c == null && plan.areaFacet == null) illustrateAnswer(segment.text)
                // Non-stop: prepare the next story while this one plays (a place if there is one, else the next area story).
                if (cfg.pacing == Pacing.NONSTOP) {
                    prefetchNext(excludeId = c?.place?.id ?: "", leadMs = spokenMs(segment.text))
                    if (prefetchJob?.isActive != true && prefetched == null) prefetchArea(excludeId = plan.areaFacet?.id)
                }
                airingFacetId = plan.areaFacet?.id
                try {
                    audio.play(bytes)
                } finally {
                    airingFacetId = null
                }
                programme.onFillerAired(plan.format, c?.place?.id, clock(), dayKey(day), plan.areaFacet?.id)
                plan.areaFacet?.let { f ->
                    heard.markFacetTold(f.id, clock())
                    // Its subject counts as told too (spec A §53): no second story about the same lake from another angle.
                    f.subject?.let { heard.markHeard("subject:" + com.gpsradio.core.editorial.HeardHistory.normalizeName(it), it, clock()) }
                    persistHeard()
                }
                // A bumper or quiz touched the place: it stays a candidate, but less novel.
                c?.let { mentionedIds += it.place.id }
                lastSpeechEndMs = clock()
                val answer = segment.quizAnswer
                if (plan.format == SegmentFormat.QUIZ && answer != null) {
                    // Wait for a guess like after a teaser; the answer is revealed when the window closes.
                    programme.onQuizAsked(QuizQuestion(segment.text, answer, c?.place?.id))
                    // Non-stop keeps the thinking pause short so the radio never goes quiet for long.
                    engagedUntilMs = clock() + if (cfg.pacing == Pacing.NONSTOP) NONSTOP_QUIZ_PAUSE_MS else offerWindowMs
                    setRadioState(RadioState.CONVERSING)
                    if (liveVoiceEnabled && cfg.pacing != Pacing.NONSTOP) openLive(opening = null)
                    return@launch
                }
                setRadioState(RadioState.RADIO)
            } catch (e: CancellationException) {
                programme.onFillerFailed(plan.format, c?.place?.id, clock(), dayKey(day), plan.areaFacet?.id)
                throw e
            } catch (e: Exception) {
                // Fillers are optional: no error on air, just don't retry this one straight away.
                programme.onFillerFailed(plan.format, c?.place?.id, clock(), dayKey(day), plan.areaFacet?.id)
                setRadioState(RadioState.RADIO)
            }
        }
    }

    private fun revealQuiz(quiz: QuizQuestion) {
        programme.onQuizResolved()
        speechJob = scope.launch {
            try {
                val bytes = timed(timeouts.speechMs, "Speech") { speech.synthesize(quiz.answer, sessionLanguage, config().style) }
                lastAudio = bytes
                addTranscript(TranscriptEntry(Speaker.RADIO, quiz.answer, clock(), quiz.placeId))
                setRadioState(RadioState.NARRATING)
                audio.play(bytes)
                lastSpeechEndMs = clock()
                setRadioState(RadioState.RADIO)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setRadioState(RadioState.RADIO)
            }
        }
    }

    // ---- visit info: hours, admission, what a visit involves (spec A §31) -------------------------

    private val visitCache = HashMap<String, VisitInfo>()
    private val visitJobs = HashMap<String, Deferred<VisitInfo?>>()
    private val visitLookupTimes = ArrayDeque<Long>()

    private fun sameDay(a: Long, b: Long) =
        Instant.ofEpochMilli(a).atZone(zone()).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone()).toLocalDate()

    /** Starts (or reuses) today's lookup for [c]; null when the place isn't worth checking. */
    private fun ensureVisit(c: RankedCandidate): Deferred<VisitInfo?>? {
        val place = c.place
        if (!Visits.worthChecking(place, c.roadTrip)) return null
        visitCache[place.id]?.takeIf { sameDay(it.checkedMs, clock()) }?.let { return CompletableDeferred(it.takeIf { v -> v.source != "none" }) }
        visitJobs[place.id]?.takeIf { it.isActive }?.let { return it }
        val now = clock()
        val osm = Visits.fromOsm(place, now, zone())
        val cfg = config()
        while (visitLookupTimes.isNotEmpty() && now - visitLookupTimes.first() > 3_600_000L) visitLookupTimes.removeFirst()
        val scout = visitScout
        if (scout == null || cfg.previewMode || !isOnline() || onDeviceNow() || visitLookupTimes.size >= VISIT_LOOKUPS_PER_HOUR) {
            // Not cached: a check skipped now (offline, budget) must still happen later today.
            return CompletableDeferred(osm)
        }
        visitLookupTimes.addLast(now)
        val job = scope.async {
            val web = try {
                withTimeoutOrNull(VISIT_TIMEOUT_MS) { scout.lookup(place, _state.value.area, clock(), zone(), sessionLanguage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isQuota(e)) noteQuota()
                null
            }
            // The web check wins; OSM fills what it couldn't confirm.
            val filledFromOsm = web != null && osm != null &&
                ((web.hoursToday == null && osm.hoursToday != null) || (web.admission == null && osm.admission != null))
            val merged = web?.copy(
                openToday = web.openToday ?: osm?.openToday,
                hoursToday = web.hoursToday ?: osm?.hoursToday,
                admission = web.admission ?: osm?.admission,
                // Provenance: the host must not say "according to their website" for OSM hours.
                source = if (filledFromOsm) "mixed" else web.source,
            ) ?: osm
            visitCache[place.id] = merged ?: VisitInfo(source = "none", checkedMs = clock())
            refreshDetours()
            merged
        }
        visitJobs[place.id] = job
        return job
    }

    /** Waits briefly for the visit check (usually prefetched); never blocks a story for long. */
    private suspend fun visitFor(c: RankedCandidate): VisitInfo? =
        ensureVisit(c)?.let { withTimeoutOrNull(VISIT_WAIT_MS) { it.await() } }

    private fun detourSuggestions(loc: LocationContext): List<DetourSuggestion> =
        Detours.ahead(ranked, loc).map { (r, min) ->
            // Prefetch so the detour card (and the story's offer) can say hours, fee and effort.
            ensureVisit(r)
            DetourSuggestion(r.place.id, r.place.name, min, visitCache[r.place.id]?.takeIf { it.source != "none" }?.summary(sessionLanguage)?.ifBlank { null })
        }

    private fun refreshDetours() {
        val loc = _state.value.location ?: return
        _state.update { it.copy(detours = detourSuggestions(loc)) }
    }

    // ---- local events today (spec A §30) --------------------------------------------------------

    private var eventsCheckedMs = Long.MIN_VALUE / 2
    private var eventsKey: String? = null
    private var eventsJob: Job? = null
    private val announcedEvents = HashSet<String>()

    /** Events still worth mentioning: running now, or starting within the next 3 h, not yet announced. */
    private fun eventsToAnnounce(now: Long): List<LocalEvent> = _state.value.todayEvents.filter { e ->
        e.id !in announcedEvents && e.startMs <= now + EVENTS_ANNOUNCE_AHEAD_MS && (e.endMs ?: (e.startMs + 3 * 3_600_000L)) > now
    }

    /** Searches at most every 3 h per area, and at least 45 min apart even when driving through towns. */
    // ---- the endless loop: researched angles (spec A §37) ---------------------------------------

    private val researchedFacets = mutableListOf<com.gpsradio.core.discovery.AreaFacet>()
    private val triedAngles = mutableSetOf<String>()
    private var angleJob: Job? = null
    private val angleLookupTimes = ArrayDeque<Long>()
    private var angleBackoffUntilMs = 0L

    /** Researched facets for where the listener is now (another town's facets are dropped). */
    private fun currentResearched(): List<com.gpsradio.core.discovery.AreaFacet> {
        val names = com.gpsradio.core.discovery.AnglePlanner.scopes(_state.value.area).map { it.second }.toSet()
        return researchedFacets.filter { it.area in names }
    }

    /**
     * Non-stop radio: when the nearby places are running out, research the next untold angle for the town, then the
     * region, then the country, one ahead of time, so there is always something to tell. Rate-limited.
     */
    private fun maybeResearchAngle(now: Long) {
        val research = angleResearch ?: return
        val cfg = config()
        if (cfg.pacing != Pacing.NONSTOP || cfg.previewMode || !isOnline() || onDeviceNow() || _state.value.quotaExhausted) return
        if (angleJob?.isActive == true || now < angleBackoffUntilMs || tour != null) return
        val area = _state.value.area ?: return
        // Enough to tell already? Unheard places with facts, or an untold facet.
        val unheard = ranked.count { it.breakdown.novelty > 0.0 && !(it.place.extract ?: it.place.description).isNullOrBlank() }
        val told = programme.toldFacets.toSet()
        val readyFacets = (areaFacets + currentResearched()).count { it.id !in told }
        // Untold: the one on air, the one prepared next, and one more in reserve.
        if (unheard >= 3 || readyFacets >= 3) return
        while (angleLookupTimes.isNotEmpty() && now - angleLookupTimes.first() > 3_600_000L) angleLookupTimes.removeFirst()
        if (angleLookupTimes.size >= ANGLE_LOOKUPS_PER_HOUR) return
        val avoid = memory.topicWeights().filterValues { it < 0.5 }.keys
        val target = com.gpsradio.core.discovery.AnglePlanner.next(area, cfg.interests, _state.value.theme, triedAngles, avoid) ?: return
        triedAngles += target.key
        heard.markAngleTried(target.key, now)
        persistHeard()
        angleLookupTimes.addLast(now)
        val point = _state.value.location?.point
        angleJob = scope.launch {
            val facet = try {
                kotlinx.coroutines.withTimeoutOrNull(ANGLE_TIMEOUT_MS) {
                    research.research(
                        target, area, point,
                        recentTitles.toList() + researchedFacets.mapNotNull { it.title } + researchedFacets.mapNotNull { it.subject },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isQuota(e)) noteQuota()
                angleBackoffUntilMs = clock() + 60_000L
                null
            }
            // The same subject under another angle ("water", then "records": both the lake's depth) is a repeat.
            if (facet != null && isRepeatSubject(facet)) return@launch
            if (facet != null) {
                researchedFacets += facet
                // Found while something plays: prepare it now so it can follow without a pause.
                if (_state.value.radioState == RadioState.NARRATING && prefetchJob?.isActive != true && prefetched == null) {
                    prefetchArea(excludeId = airingFacetId)
                }
            }
        }
    }

    /**
     * The listener steers ("tell me something about the lake's fish"): research exactly that and tell it next.
     * Returns what the live host should say while it looks (it keeps talking; the story follows).
     */
    private fun steer(request: String): String {
        val research = angleResearch ?: return "not available: answer from what you know or search the web"
        if (config().previewMode || !isOnline()) return "not available offline: answer from what you know"
        val area = _state.value.area
        val (scopeKind, scopeName) = com.gpsradio.core.discovery.AnglePlanner.scopes(area).firstOrNull()
            ?: return "not available: the location isn't known yet"
        val target = com.gpsradio.core.discovery.AngleTarget(scopeKind, scopeName, null, custom = request)
        val point = _state.value.location?.point
        steerJob?.cancel()
        steerJob = scope.launch {
            val facet = try {
                kotlinx.coroutines.withTimeoutOrNull(ANGLE_TIMEOUT_MS) { research.research(target, area, point, recentTitles.toList()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // One voice at a time: let the host finish its sentence ("let me dig into that…") first.
            var waited = 0L
            while (live?.isAudible == true && waited < STEER_MAX_WAIT_MS) { delay(200); waited += 200 }
            // Only while the radio is still holding for it: anything else the listener did since wins.
            if (_state.value.radioState != RadioState.CONVERSING) return@launch
            if (facet == null) {
                // Still holding the radio for the steer: the host says so, and the idle exchange then hands back.
                live?.takeIf { it.isOpen }?.prompt(
                    "Tell the listener in one short sentence that you couldn't find anything reliable about \"$request\" here, and that the radio carries on.",
                ) ?: endConversation()
                return@launch
            }
            val loc = _state.value.location ?: return@launch endConversation()
            closeLive()
            clearOffer()
            engagedUntilMs = 0
            speechJob?.cancel()
            setRadioState(RadioState.RADIO)
            speakFiller(Programme.Plan.Filler(SegmentFormat.AREA, areaFacet = facet), loc)
        }
        return "researching now; say in a few words that you're looking into it (e.g. 'Ooh, let me dig something up about that'), then stop talking: the story follows in a few seconds"
    }

    private var steerJob: Job? = null

    /** A researched item about something already told (as a place story or another angle's story). */
    private fun isRepeatSubject(facet: com.gpsradio.core.discovery.AreaFacet): Boolean {
        val subject = facet.subject ?: return false
        val key = com.gpsradio.core.editorial.HeardHistory.normalizeName(subject)
        return heard.wasHeard("subject:$key", subject, clock()) ||
            researchedFacets.any { it.subject?.let(com.gpsradio.core.editorial.HeardHistory::normalizeName) == key }
    }

    /** Since when the listener has been waiting for content (null when not waiting). */
    private var waitingSinceMs: Long? = null
    private var waitCues = 0
    private var lastWaitCueMs = 0L

    /**
     * Waiting feedback (spec A §59): while the listener waits for the next story (after "next", during a steer, before
     * the first story), the UI shows it's searching, and after a few seconds a short "just a moment" is spoken in the
     * session language, varied, at most three times per wait.
     */
    private fun maybeWaitCue(now: Long, speaking: Boolean) {
        if (cueJob?.isActive == true) return
        val s = _state.value
        // Only when the listener is actually waiting on something: a "next", a steer, or the very first story.
        // (Preparing a story counts: that's the longest wait, while the story job is already running.)
        val preparing = s.radioState == RadioState.RESEARCHING || (s.radioState == RadioState.RADIO && !speaking)
        val quiet = live?.isAudible != true && s.live != LiveState.USER_SPEAKING
        val waiting = quiet && (
            (preparing && nextNow()) ||
                (!speaking && s.radioState == RadioState.CONVERSING && steerJob?.isActive == true) ||
                (firstStoryPending && (s.discovering || s.radioState == RadioState.RESEARCHING))
            )
        // The animated "searching" also shows while the radio prepares its own next story (nobody waits on that,
        // so no spoken cue).
        val searching = waiting || s.radioState == RadioState.RESEARCHING
        if (s.waiting != searching) _state.update { it.copy(waiting = searching) }
        if (!waiting) {
            waitingSinceMs = null
            waitCues = 0
            return
        }
        val since = waitingSinceMs ?: now.also { waitingSinceMs = it }
        if (onDeviceNow() && !config().previewMode) return
        if (waitCues >= 3 || now - since < WAIT_CUE_FIRST_MS || now - lastWaitCueMs < WAIT_CUE_EVERY_MS) return
        val notice = listOf(Notice.WAIT_1, Notice.WAIT_2, Notice.WAIT_3)[waitCues]
        waitCues++
        lastWaitCueMs = now
        // Its own short job (the story being prepared keeps running); the audio queue keeps them apart, and a story
        // that becomes ready cancels a cue that hasn't finished.
        cueJob = scope.launch { speakNotice(Notices.text(notice, sessionLanguage)) }
    }

    private var cueJob: Job? = null

    /** From Start until the first story airs: the listener is waiting for it. */
    private var firstStoryPending = false

    /** Scenery around the listener is added this long after a sparse story starts. */
    private val SCENERY_AFTER_MS = 6_000L

    /** About how fast the host speaks (characters per second), to time the photos to the words. */
    private val SPOKEN_CHARS_PER_SEC = 14L

    /** The station sting plays before a story's words start. */
    private val STING_LEAD_MS = 700L

    /** Shown the moment "next" is heard, until the next story starts. */
    private val NEXT_STATUS = "Next story…"

    /** The first "just a moment" after this long waiting, then one every [WAIT_CUE_EVERY_MS]. */
    private val WAIT_CUE_FIRST_MS = 4_000L
    private val WAIT_CUE_EVERY_MS = 9_000L

    /** How long an explicit "next" waives the pacing gap (the next segment may still be being written). */
    private val NEXT_NOW_WINDOW_MS = 60_000L

    /** The model's own tool call for a command already handled on the device is ignored within this window. */
    private val LOCAL_COMMAND_DEDUP_MS = 8_000L

    /** A steered story waits at most this long for the host to finish talking. */
    private val STEER_MAX_WAIT_MS = 8_000L

    private fun maybeScoutEvents(now: Long) {
        // Drop events that are over.
        val live = _state.value.todayEvents.filter { (it.endMs ?: (it.startMs + 3 * 3_600_000L)) > now }
        if (live.size != _state.value.todayEvents.size) _state.update { it.copy(todayEvents = live) }
        val scout = eventScout ?: return
        val cfg = config()
        if (!cfg.localEvents || cfg.previewMode || !isOnline() || onDeviceNow() || eventsJob?.isActive == true) return
        val area = _state.value.area ?: return
        val loc = _state.value.location ?: return
        val key = listOfNotNull(area.city, area.region, area.countryCode).joinToString("|") + "|" + langBase(sessionLanguage)
        val since = now - eventsCheckedMs
        if (since < EVENTS_MIN_GAP_MS || (key == eventsKey && since < EVENTS_REFRESH_MS)) return
        eventsCheckedMs = now
        // Moved to another town: its events replace the old ones (don't announce a concert 100 km back).
        if (eventsKey != null && eventsKey != key) _state.update { it.copy(todayEvents = emptyList()) }
        eventsKey = key
        eventsJob = scope.launch {
            val found = try {
                timed(timeouts.narrationMs * 3, "Events") { scout.find(area, loc.point, clock(), zone(), sessionLanguage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isQuota(e)) noteQuota()
                return@launch
            }
            val merged = (_state.value.todayEvents + found).distinctBy { it.id }.sortedBy { it.startMs }.take(8)
            _state.update { it.copy(todayEvents = merged) }
        }
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()

    /** "On this day" runs at most once per day and area: a new town may have its own local anniversary. */
    private fun dayKey(d: LocalDate) =
        "%02d-%02d".format(d.monthValue, d.dayOfMonth) + "|" + (_state.value.area?.let { it.city ?: it.region }.orEmpty())

    companion object {
        const val NONSTOP_QUIZ_PAUSE_MS = 5_000L

        fun langBase(tag: String): String = tag.substringBefore('-').lowercase()

        /** A skip this soon after a story starts playing counts as "not interested". */
        const val EARLY_SKIP_MS = 8_000L

        const val PREVIEW_NOTE = "Preview mode — add an OpenAI key for full stories and questions"
        const val PREVIEW_QUESTIONS = "Questions need an OpenAI key. Add one to ask about anything you pass."
        const val OFFLINE_QUESTIONS = "You're offline, so I can't answer questions right now."
        const val QUOTA_BUILT_IN = "The app's built-in OpenAI credit has run out. Add your own OpenAI key in Settings " +
            "for full stories and questions. Until then I'll read short notes with the on-device voice."
        const val QUOTA_OWN_KEY = "Your OpenAI key is out of credit (billing limit reached). Top up at platform.openai.com " +
            "or add another key in Settings. Until then I'll read short notes with the on-device voice."

        /** The one-time spoken notice, in the session language where available. */
        fun quotaSpoken(language: String): String = when (langBase(language)) {
            "ru" -> "Небольшое объявление: закончился кредит OpenAI. Добавьте ключ в настройках, а пока я читаю короткие заметки."
            "de" -> "Kurze Durchsage: Das OpenAI-Guthaben ist aufgebraucht. Fügen Sie in den Einstellungen einen Schlüssel hinzu; bis dahin lese ich kurze Notizen."
            "es" -> "Un aviso: se acabó el crédito de OpenAI. Añade una clave en Ajustes; mientras tanto leeré notas breves."
            "fr" -> "Petite annonce : le crédit OpenAI est épuisé. Ajoutez une clé dans les réglages ; en attendant, je lis de courtes notes."
            else -> "Quick note: the OpenAI credit has run out. Add a key in Settings; until then I'll read short notes."
        }

        /** Web checks of hours/fees: at most this many per hour, each at most this long; stories wait at most VISIT_WAIT_MS. */
        const val VISIT_LOOKUPS_PER_HOUR = 20
        /** Researched angles (web search) per hour; ~one every 2–3 minutes when the places have run out. */
        const val ANGLE_LOOKUPS_PER_HOUR = 40
        const val ANGLE_TIMEOUT_MS = 30_000L
        const val VISIT_TIMEOUT_MS = 20_000L
        const val VISIT_WAIT_MS = 8_000L

        /** A refresh that finds at least this many places resets a widened (non-stop) search radius. */
        const val RADIUS_RESET_FOUND = 8

        const val EVENTS_REFRESH_MS = 3 * 3_600_000L
        const val EVENTS_MIN_GAP_MS = 45 * 60_000L
        /** Events are announced when they start within this time (or are running). */
        const val EVENTS_ANNOUNCE_AHEAD_MS = 3 * 3_600_000L

        /** On start, a fix older than this is dropped rather than narrated from. */
        const val STALE_FIX_ON_START_MS = 2 * 60_000L

        const val TOUR_ABANDONED = "Walking tour ended: looks like you've moved on. Back to the regular radio."
        const val TOUR_AWAY_M = 1_000.0
        const val TOUR_AWAY_MS = 5 * 60_000L

        const val KEY_REJECTED = "OpenAI didn't accept the API key. Check it in Settings. Until then I'll read short notes with the phone's voice."

        const val DEGRADED_NOTE = "OpenAI is unreachable, so I'm reading quick notes with the on-device voice."
        const val BUDGET_NOTE = "Today's spending limit is reached: quick notes with the on-device voice until tomorrow (Settings → limit)."
        const val OFFLINE_NOTE = "You're offline, so I'm reading quick notes with the on-device voice."
        const val OFFLINE_NO_PLACES = "You're offline and no places around here are saved yet. Stories resume when you're back online."

        private val yes = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "go on", "go ahead", "tell me", "please", "yes please", "да", "давай", "конечно", "ja", "oui", "sí", "si", "כן")
        private val no = setOf("no", "nope", "not now", "no thanks", "skip", "нет", "не надо", "nein", "non", "לא")

        /** Fast yes/no for an offered story; null when the answer needs the model. */
        fun offerAnswer(text: String): Boolean? {
            val t = text.lowercase().trim().trimEnd('.', '!', '?', ',')
            return when {
                t in yes -> true
                t in no -> false
                else -> null
            }
        }

        /**
         * Radio controls said in so many words, handled on the device: instant, and they work offline. Anything
         * longer or less direct goes to the model, which calls the same actions.
         */
        /** "Sources?", "where's that from?" said in so many words; "is that true?" goes to the model, which can verify. */
        fun isSourcesQuestion(text: String): Boolean =
            text.lowercase().replace('ё', 'е').replace(Regex("[,.!?¡¿]+"), " ").replace(Regex("\\s+"), " ").trim() in SOURCES_WORDS

        private val SOURCES_WORDS = setOf(
            "sources", "source", "what's the source", "what is the source", "where's that from", "where is that from",
            "where did you get that", "where did that come from",
            "источник", "источники", "какой источник", "откуда это", "откуда ты это знаешь", "откуда информация",
            "откуда ты это взял", "откуда",
            "מקור", "מקורות", "מאיפה זה",
            "quelle", "quellen", "woher ist das", "woher weißt du das",
            "fuente", "fuentes", "de dónde es eso", "de donde es eso",
            "d'où ça vient", "d'où vient ça",
        )

        fun localCommand(text: String): ConversationAction? {
            var t = text.lowercase().replace('ё', 'е').replace(Regex("[,.!?¡¿]+"), " ").replace(Regex("\\s+"), " ").trim()
            // Politeness and fillers around the command: "ok, skip please", "ну, дальше", "стоп, пожалуйста".
            repeat(2) {
                t = t.removeSuffix(" please").removeSuffix(" пожалуйста").removeSuffix(" bitte").removeSuffix(" por favor")
                    .removeSuffix(" s'il te plaît").removeSuffix(" s'il vous plaît").removeSuffix(" בבקשה")
                    .removePrefix("ok ").removePrefix("okay ").removePrefix("окей ").removePrefix("ок ").removePrefix("ну ")
                    .removePrefix("так ").removePrefix("ладно ").removePrefix("please ").removePrefix("пожалуйста ")
                    .trim()
            }
            return when (t) {
                in SKIP_WORDS -> ConversationAction.SKIP
                in RESUME_WORDS -> ConversationAction.RESUME_RADIO
                in PAUSE_WORDS -> ConversationAction.PAUSE
                else -> null
            }
        }

        private val SKIP_WORDS = setOf(
            "skip", "next", "skip it", "skip this", "skip this one", "next one", "next story", "something else",
            "the next story", "next please", "another story", "tell me another story", "go to the next one",
            "следующая история", "следующую историю", "давай следующую", "давай следующую историю", "другую историю",
            "расскажи другую историю", "расскажи следующую", "дальше давай", "переключи", "следующий рассказ",
            "другая история", "ещё историю", "еще историю", "ещё одну историю", "еще одну историю", "давай другую",
            "расскажи что-нибудь другое", "что-нибудь другое", "расскажи что-нибудь ещё", "расскажи что-нибудь еще",
            "расскажи что-нибудь интересное", "tell me something else", "tell me something interesting", "surprise me",
            "another one", "one more story",
            "дальше", "давай дальше", "пропусти", "пропустить", "пропусти это", "следующий", "следующая", "следующее",
            "следующую", "другое", "давай другое", "неинтересно", "не интересно",
            "הבא", "דלג", "תדלג", "הלאה",
            "weiter", "nächste", "nächstes", "überspringen",
            "siguiente", "saltar", "salta", "otra",
            "suivant", "passe", "passer", "au suivant",
        )
        private val RESUME_WORDS = setOf(
            "continue", "resume", "go on", "carry on", "play", "back to the radio", "back to radio", "go back to the radio",
            "продолжай", "продолжи", "продолжить", "вернись к радио", "назад к радио", "обратно к радио", "вернись",
            "возвращайся к радио", "играй", "включи радио", "радио",
            "המשך", "תמשיך", "חזור לרדיו", "תחזור לרדיו",
            "fortsetzen", "weitermachen", "zurück zum radio", "mach weiter",
            "continúa", "continua", "continuar", "sigue", "volver a la radio", "vuelve a la radio",
            "continuer", "reprends", "reprendre", "retour à la radio",
        )
        private val PAUSE_WORDS = setOf(
            "pause", "stop", "be quiet", "quiet", "hold on", "silence", "shush", "shut up",
            "пауза", "стоп", "хватит", "замолчи", "помолчи", "тише", "тихо", "подожди", "остановись", "стой",
            "עצור", "תעצור", "הפסק", "תפסיק", "שקט", "רגע",
            "stopp", "ruhe", "halt", "warte",
            "pausa", "para", "detente", "espera",
            "arrête", "arrete", "attends", "tais-toi",
        )
    }
}
