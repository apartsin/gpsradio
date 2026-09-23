package com.gpsradio.core.session

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.favorites.FavoritePlace
import com.gpsradio.core.favorites.Favorites
import com.gpsradio.core.favorites.FavoritesStore
import com.gpsradio.core.ai.OpenAiException
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
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TranscriptEntry
import com.gpsradio.core.model.TravelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

/** The place currently being described; drives the photo + map panel. */
data class FocusPlace(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val imageUrl: String?,
    val url: String?,
    /** More real photos of the place, loaded after it comes into focus. */
    val gallery: List<String> = listOfNotNull(imageUrl),
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
    val tripContext: String? = null,
    /** Non-null while a live voice conversation is open. */
    val live: LiveState? = null,
    val nearby: List<RankedCandidate> = emptyList(),
    val transcript: List<TranscriptEntry> = emptyList(),
    val discovering: Boolean = false,
    val status: Status? = null,
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
    private val audio: AudioOutput,
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
) {
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

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
    private var lastTeaserMs = 0L
    private var storiesSinceTeaser = 0
    private var tripAsked = false
    private var tripContext: String? = null
    private var live: LiveConversation? = null

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
            _state.update { it.copy(memory = memory.all, favorites = favorites.all) }
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------

    fun start() = scope.launch {
        if (schedulerJob?.isActive == true) return@launch
        heard.restore(historyStore.load(), clock())
        _state.update { it.copy(radioState = RadioState.RADIO, sessionLanguage = sessionLanguage, status = previewNote()) }
        schedulerJob = scope.launch {
            while (isActive) {
                tick()
                delay(tickMs)
            }
        }
    }

    fun stop() = scope.launch {
        closeLive()
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
        _state.update { it.copy(radioState = RadioState.IDLE, nowPlaying = null, nowPlayingReason = null, discovering = false, pendingOffer = null, tripContext = null) }
    }

    fun pause() = scope.launch { doPause() }

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
    }

    /** Activity-recognition transition (vehicle, bicycle, walking, still): a prior for mode detection. */
    fun onActivity(type: ActivityType) = scope.launch { processor.setActivity(type, clock()) }

    fun setModeOverride(mode: TravelMode?) = scope.launch {
        processor.modeOverride = mode
        _state.update { it.copy(modeOverride = mode, location = processor.current ?: it.location) }
        rerank()
    }

    fun skip() = scope.launch {
        closeLive()
        clearOffer()
        val wasSpeaking = speechJob?.isActive == true
        storyPlayback?.takeIf { wasSpeaking && clock() - it.second < EARLY_SKIP_MS }?.let { (id, _) ->
            candidates[id]?.let { learn(InterestModel.Signal.EARLY_SKIP, it) }
        }
        storyPlayback = null
        speechJob?.cancel()
        if (wasSpeaking) {
            // Penalize what was actually on air (or being prepared), not the previous story.
            (pendingId ?: activeId)?.let { id -> candidates[id]?.let { penalize(it) } }
            lastSpeechEndMs = clock()
        }
        pendingId = null
        engagedUntilMs = 0
        if (_state.value.radioState !in setOf(RadioState.IDLE, RadioState.PAUSED)) setRadioState(RadioState.RADIO)
        _state.update { it.copy(nowPlaying = null, nowPlayingReason = null) }
        rerank()
    }

    fun repeat() = scope.launch {
        closeLive()
        val bytes = lastAudio ?: return@launch
        speechJob?.cancel()
        val previous = _state.value.radioState
        speechJob = scope.launch {
            setRadioState(RadioState.NARRATING)
            runCatching { audio.play(bytes) }.onFailure { if (it is CancellationException) throw it }
            lastSpeechEndMs = clock()
            setRadioState(
                when (previous) {
                    RadioState.CONVERSING, RadioState.PAUSED -> previous
                    else -> RadioState.RADIO
                },
            )
        }
    }

    /** User picked a place in the UI: narrate it now, even if heard before. */
    fun tellAbout(placeId: String) = scope.launch {
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

    /** Opens a hands-free voice conversation (tap the mic), or closes it if already open. */
    fun toggleLive() = scope.launch {
        if (live?.isOpen == true) {
            closeLive()
            endConversation()
        } else {
            openLive(opening = null)
        }
    }

    /** Answer to "want the full story?" from the on-screen buttons. */
    fun answerOffer(yes: Boolean) = scope.launch {
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
        val speaking = speechJob?.isActive == true
        val now = clock()
        if (s.radioState == RadioState.CONVERSING && !speaking && now >= engagedUntilMs) {
            // No answer to an offer means "not now": keep the story for later, just less novel.
            clearOffer()
            setRadioState(RadioState.RADIO)
        }
        // Retries and refresh requests must not depend on new fixes: stationary phones get none.
        if (s.radioState != RadioState.IDLE) processor.current?.let { maybeRefresh(it) }

        if (_state.value.radioState != RadioState.RADIO || speaking || discoveryJob?.isActive == true) return
        if (now < engagedUntilMs || now < nextNarrationAllowedMs) return
        if (ranker.holdForPacing(_state.value.location, lastSpeechEndMs, now)) return
        if (maybeAskAboutTrip()) return
        rerank()
        val pick = ranker.pickForAirtime(ranked) ?: return
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
                val radius = refreshPolicy.searchRadiusM(ctx.travelMode)
                val found = timed(timeouts.discoveryMs, "Discovery") {
                    // Driving: look-ahead corridor cells, fetched (and cached) before arrival.
                    refreshPolicy.corridorCells(ctx)?.let { cells -> corridor.discover(cells, lang, clock()) }
                        ?: places.discover(refreshPolicy.searchCenter(ctx), radius, lang)
                }
                if (lang != lastRefreshLang) candidates.clear()
                found.forEach { candidates[it.id] = it }
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
            tick()
        }
    }

    private fun rerank() {
        val loc = _state.value.location ?: processor.current ?: return
        val now = clock()
        val interests = config().interests.associateWith { 1.0 }.toMutableMap()
        interests.putAll(memory.topicWeights())
        topicPenalty.forEach { (t, p) -> interests[t] = (interests[t] ?: 0.4) * p }
        ranked = ranker.rank(
            candidates.values.filter { it.id !in failedIds },
            EditorialRanker.Context(
                location = loc,
                interests = interestModel.adjust(interests, now, explicit = memory.topicWeights().keys),
                heard = heard,
                nowMs = now,
                mentionedIds = mentionedIds,
                lastSpeechEndMs = lastSpeechEndMs,
                userEngaged = now < engagedUntilMs || _state.value.radioState == RadioState.CONVERSING,
                theme = _state.value.theme,
            ),
        )
        // Only push a new list to the UI when it visibly changed (order or ~10 m distance steps).
        val top = ranked.take(25)
        val key = top.map { it.place.id to (it.distanceM / 10).toInt() }
        if (key != lastNearbyKey) {
            lastNearbyKey = key
            _state.update { it.copy(nearby = top) }
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
                _state.update { it.copy(radioState = RadioState.NARRATING, nowPlaying = segment, nowPlayingReason = reasonFor(c), focus = FocusPlace.of(c.place)) }
                loadGallery(c.place)
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
                prefetchNext(excludeId = c.place.id)
                storyPlayback = c.place.id to clock()
                audio.play(bytes)
                storyPlayback = null
                learn(InterestModel.Signal.COMPLETED, c.place)
                // Only a story that was actually heard to the end counts as heard.
                heard.markHeard(c.place.id, c.place.name, clock())
                persistHeard()
                recentTitles += c.place.name
                pendingId = null
                backoffMs = 0
                lastSpeechEndMs = clock()
                setRadioState(RadioState.RADIO)
                rerank()
            } catch (e: CancellationException) {
                throw e
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
    ): Pair<Segment, ByteArray> {
        val cfg = config()
        val started = clock()
        // Describe distance/direction from where the listener will be when the audio starts, not from now.
        val (atPlayback, rel) = projectForPlayback(c, loc, prepLatencyMs.toLong())
        val request = NarrationRequest(
            rel, atPlayback, lang, cfg.interests, recentTitles.toList(), memory.promptLines(),
            style = cfg.style, format = format, tripContext = tripContext,
        )
        if (onDeviceNow()) return prepareOnDevice(request)
        var segment: Segment? = null
        return try {
            val s = timed(timeouts.narrationMs, "Narration") { narrator.narrate(request) }
            segment = s
            val bytes = timed(timeouts.speechMs, "Speech") { speech.synthesize(s.text, lang, cfg.style) }
            fallbackGate.onPrimarySuccess()
            prepLatencyMs = prepLatencyMs * 0.7 + (clock() - started) * 0.3
            clearNotes(DEGRADED_NOTE, OFFLINE_NOTE)
            s to bytes
        } catch (e: Exception) {
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
        hasFallback && (config().previewMode || !isOnline() || fallbackGate.primaryResting())

    /** On-device notes and voice; [spoken] is model text that was generated but could not be voiced. */
    private suspend fun prepareOnDevice(req: NarrationRequest, spoken: Segment? = null): Pair<Segment, ByteArray> {
        if (!config().previewMode) setStatus(if (isOnline()) DEGRADED_NOTE else OFFLINE_NOTE)
        val segment = spoken?.takeIf { req.format == SegmentFormat.STORY }
            ?: fallbackNarrator!!.narrate(req.copy(format = SegmentFormat.STORY))
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
    private fun prefetchNext(excludeId: String) {
        if (prefetchJob?.isActive == true) return
        val loc = _state.value.location ?: return
        val next = ranked.firstOrNull { r ->
            r.place.id != excludeId &&
                // A rich story may be offered as a teaser first; don't pre-generate its full version.
                !teaserEligible(r) &&
                // Ignore the temporary conversation-cost penalty: it will have decayed by the time this airs.
                r.score + ranker.weights.conversationCost * r.breakdown.conversationCost >= ranker.speakThreshold
        } ?: return
        if (prefetched?.placeId == next.place.id) return
        val lang = sessionLanguage
        prefetchJob = scope.launch {
            val result = runCatching { prepare(next, loc, lang) }.getOrNull() ?: return@launch
            prefetched = Prepared(next.place.id, lang, result.first, result.second, loc.point, clock())
        }
    }

    private fun takePrefetched(c: RankedCandidate, lang: String, loc: LocationContext): Pair<Segment, ByteArray>? {
        val p = prefetched ?: return null
        if (p.placeId != c.place.id) return null
        prefetched = null
        // Distance/direction in the text must still be roughly right.
        val maxMove = when (loc.travelMode) {
            TravelMode.DRIVING -> 500.0
            TravelMode.CYCLING -> 300.0
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
        when (localCommand(text)) {
            ConversationAction.SKIP -> { skip(); return }
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
                        pendingOffer = pendingOffer?.name,
                    ),
                    onSearching = { setStatus("Checking online…", StatusLevel.WORKING) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Couldn't answer: ${e.message}")
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
        reply.entityId?.let { id -> candidates[id] }?.let { place ->
            activeId = place.id
            mentionedIds += place.id
            _state.update { it.copy(focus = FocusPlace.of(place)) }
        }
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
                audio.play(bytes)
            }
        }
        lastSpeechEndMs = clock()
        when (reply.action) {
            ConversationAction.RESUME_RADIO, ConversationAction.SKIP, ConversationAction.DECLINE_OFFER -> endConversation()
            ConversationAction.PAUSE -> setRadioState(RadioState.PAUSED)
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
        if (live?.isOpen == true) return
        if (questionsUnavailable()) return
        speechJob?.cancel()
        pendingId = null
        engagedUntilMs = Long.MAX_VALUE
        setRadioState(RadioState.CONVERSING)
        live = factory(liveHost, scope).also { it.start(opening) }
    }

    private fun closeLive() {
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
        pendingOffer = pendingOffer?.name,
    )

    private val liveHost = object : LiveHost {
        override fun liveInstructions() = RadioAgent.liveInstructions(conversationRequest(""))
        override fun liveVoice() = com.gpsradio.core.ai.RealtimeProtocol.liveVoice(config().voice)
        override fun liveModel() = config().liveModel
        override fun transcriptionModel() = config().transcriptionModel

        override fun onUserSaid(text: String) {
            addTranscript(TranscriptEntry(Speaker.USER, text, clock()))
            history += ConversationTurn(true, text)
            while (history.size > 24) history.removeAt(0)
        }

        override fun onAssistantSaid(text: String) {
            addTranscript(TranscriptEntry(Speaker.RADIO, text, clock(), activeId))
            history += ConversationTurn(false, text)
            lastSpeechEndMs = clock()
        }

        override suspend fun callTool(name: String, arguments: JsonObject): String = liveTool(name, arguments)

        override fun onLiveState(state: LiveState?) {
            _state.update { it.copy(live = state) }
            if (state == null) {
                live = null
                if (_state.value.radioState == RadioState.CONVERSING) {
                    clearOffer()
                    endConversation()
                }
            }
        }

        override fun onLiveError(message: String) {
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
                val action = ConversationAction.parse(arg("action"))
                when (action) {
                    ConversationAction.RESUME_RADIO -> { closeLive(); endConversation() }
                    ConversationAction.PAUSE -> { closeLive(); doPause() }
                    ConversationAction.SKIP -> {
                        activeId?.let { id -> candidates[id]?.let { penalize(it) } }
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

    private fun shouldTease(c: RankedCandidate): Boolean = teaserEligible(c) && storiesSinceTeaser >= 2 && !onDeviceNow()

    private fun clearOffer() {
        pendingOffer = null
        _state.update { it.copy(pendingOffer = null) }
    }

    private fun acceptOffer(offer: PlaceCandidate) {
        clearOffer()
        engagedUntilMs = 0
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
        if (tripAsked || tripContext != null || loc.travelMode != TravelMode.DRIVING || !config().askAboutTrip) return false
        // The question needs a model to understand the answer.
        if (config().previewMode || !isOnline()) return false
        tripAsked = true
        if (liveVoiceEnabled) {
            // The live host asks in its natural voice and hears the answer hands-free.
            openLive(opening = HostLine.TRIP_QUESTION.instruction)
            return true
        }
        speechJob = scope.launch {
            try {
                val cfg = config()
                val line = timed(timeouts.narrationMs, "Host line") { narrator.hostLine(HostLine.TRIP_QUESTION, sessionLanguage, cfg.style) }
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
        galleries[place.id]?.let { g -> updateFocusGallery(place.id, g); return }
        scope.launch {
            val g = runCatching { places.gallery(place) }.getOrDefault(emptyList())
            if (g.isEmpty()) return@launch
            galleries[place.id] = g
            updateFocusGallery(place.id, g)
        }
    }

    private fun updateFocusGallery(id: String, gallery: List<String>) =
        _state.update { s -> if (s.focus?.id == id) s.copy(focus = s.focus.copy(gallery = gallery)) else s }

    private fun persistFavorites() {
        runCatching { favoritesStore?.save(favorites.serialize()) }
        _state.update { it.copy(favorites = favorites.all) }
    }

    private fun doPause() {
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

    private fun setRadioState(s: RadioState) = _state.update { it.copy(radioState = s) }

    /** Clearing the status falls back to the preview-mode note while there is no key. */
    private fun setStatus(msg: String?, level: StatusLevel = StatusLevel.INFO) =
        _state.update { it.copy(status = msg?.let { m -> Status(m, level) } ?: previewNote()) }

    private fun previewNote(): Status? =
        if (config().previewMode) Status(PREVIEW_NOTE, StatusLevel.INFO, needsKey = true, actionLabel = "Add key") else null

    /** Questions need OpenAI: in preview mode or offline, say so (once per question) and return true. */
    private fun questionsUnavailable(): Boolean {
        val preview = config().previewMode
        val msg = when {
            preview -> PREVIEW_QUESTIONS
            !isOnline() -> OFFLINE_QUESTIONS
            else -> return false
        }
        _state.update { it.copy(status = Status(msg, StatusLevel.INFO, needsKey = preview, actionLabel = if (preview) "Add key" else null)) }
        addTranscript(TranscriptEntry(Speaker.SYSTEM, msg, clock()))
        return true
    }

    private fun fail(msg: String) {
        val keyProblem = "API key" in msg || "401" in msg
        val friendly = if (keyProblem) "OpenAI didn't accept the API key. Check it in Settings." else msg
        _state.update { it.copy(status = Status(friendly, StatusLevel.ERROR, needsKey = keyProblem)) }
        addTranscript(TranscriptEntry(Speaker.SYSTEM, friendly, clock()))
    }

    private fun addTranscript(e: TranscriptEntry) =
        _state.update { it.copy(transcript = (it.transcript + e).takeLast(100)) }

    companion object {
        fun langBase(tag: String): String = tag.substringBefore('-').lowercase()

        /** A skip this soon after a story starts playing counts as "not interested". */
        const val EARLY_SKIP_MS = 8_000L

        const val PREVIEW_NOTE = "Preview mode — add an OpenAI key for full stories and questions"
        const val PREVIEW_QUESTIONS = "Questions need an OpenAI key. Add one to ask about anything you pass."
        const val OFFLINE_QUESTIONS = "You're offline, so I can't answer questions right now."
        const val DEGRADED_NOTE = "OpenAI is unreachable, so I'm reading quick notes with the on-device voice."
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

        /** Unambiguous one-word commands handled without a model round trip. */
        fun localCommand(text: String): ConversationAction? {
            val t = text.lowercase().trim().trimEnd('.', '!', '?')
            return when (t) {
                "skip", "next", "skip it", "дальше", "пропустить" -> ConversationAction.SKIP
                "continue", "resume", "go on", "back to the radio", "продолжай", "продолжить" -> ConversationAction.RESUME_RADIO
                "pause", "stop", "be quiet", "quiet", "пауза", "стоп" -> ConversationAction.PAUSE
                else -> null
            }
        }
    }
}
