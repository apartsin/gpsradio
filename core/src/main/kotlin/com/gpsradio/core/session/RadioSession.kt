package com.gpsradio.core.session

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.ConversationTurn
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.location.AreaRefreshPolicy
import com.gpsradio.core.location.LocationProcessor
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

data class RadioUiState(
    val radioState: RadioState = RadioState.IDLE,
    val location: LocationContext? = null,
    val modeOverride: TravelMode? = null,
    val sessionLanguage: String = Languages.FALLBACK,
    val theme: Topic? = null,
    val area: AreaLabel? = null,
    val nowPlaying: Segment? = null,
    val nearby: List<RankedCandidate> = emptyList(),
    val transcript: List<TranscriptEntry> = emptyList(),
    val discovering: Boolean = false,
    val status: String? = null,
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
    private val onPersistLanguage: (String) -> Unit = {},
    private val onNavigate: (PlaceCandidate) -> Unit = {},
    private val ranker: EditorialRanker = EditorialRanker(),
    private val processor: LocationProcessor = LocationProcessor(),
    private val refreshPolicy: AreaRefreshPolicy = AreaRefreshPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val tickMs: Long = 3_000,
    private val conversationIdleMs: Long = 45_000,
) {
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

    private var lastRefreshPoint: GeoPoint? = null
    private var lastRefreshMode: TravelMode? = null
    private var lastRefreshMs: Long? = null
    private var lastRefreshLang: String? = null
    private var lastSpeechEndMs: Long? = null
    private var engagedUntilMs = 0L
    private var activeId: String? = null
    private var lastAudio: ByteArray? = null
    private var languageOverride: String? = null

    private var schedulerJob: Job? = null
    private var discoveryJob: Job? = null
    private var speechJob: Job? = null

    private val sessionLanguage: String get() = languageOverride ?: config().language

    // ---- lifecycle ------------------------------------------------------------------------

    fun start() = scope.launch {
        if (schedulerJob?.isActive == true) return@launch
        heard.restore(historyStore.load(), clock())
        _state.update { it.copy(radioState = RadioState.RADIO, sessionLanguage = sessionLanguage, status = null) }
        schedulerJob = scope.launch {
            while (isActive) {
                tick()
                delay(tickMs)
            }
        }
    }

    fun stop() = scope.launch {
        schedulerJob?.cancel()
        discoveryJob?.cancel()
        speechJob?.cancel()
        schedulerJob = null
        persistHeard()
        languageOverride = null
        _state.update { it.copy(radioState = RadioState.IDLE, nowPlaying = null, discovering = false) }
    }

    fun pause() = scope.launch { doPause() }

    fun resume() = scope.launch {
        if (_state.value.radioState == RadioState.IDLE) return@launch
        engagedUntilMs = 0
        setRadioState(RadioState.RADIO)
        tick()
    }

    // ---- inputs ---------------------------------------------------------------------------

    fun onLocation(sample: LocationSample) = scope.launch {
        val ctx = processor.accept(sample, clock()) ?: return@launch
        _state.update { it.copy(location = ctx) }
        if (_state.value.radioState == RadioState.IDLE) return@launch
        if (lastRefreshLang != Languages.find(sessionLanguage)?.base ||
            refreshPolicy.shouldRefresh(ctx, lastRefreshPoint, lastRefreshMode, lastRefreshMs)
        ) refresh(ctx)
        rerank()
    }

    fun setModeOverride(mode: TravelMode?) = scope.launch {
        processor.modeOverride = mode
        _state.update { it.copy(modeOverride = mode, location = processor.current ?: it.location) }
        rerank()
    }

    fun skip() = scope.launch {
        val wasSpeaking = speechJob?.isActive == true
        speechJob?.cancel()
        activeId?.let { id -> candidates[id]?.let { penalize(it) } }
        if (wasSpeaking) lastSpeechEndMs = clock()
        engagedUntilMs = 0
        if (_state.value.radioState !in setOf(RadioState.IDLE, RadioState.PAUSED)) setRadioState(RadioState.RADIO)
        _state.update { it.copy(nowPlaying = null) }
        rerank()
    }

    fun repeat() = scope.launch {
        val bytes = lastAudio ?: return@launch
        speechJob?.cancel()
        val previous = _state.value.radioState
        speechJob = scope.launch {
            setRadioState(RadioState.NARRATING)
            runCatching { audio.play(bytes) }.onFailure { if (it is CancellationException) throw it }
            lastSpeechEndMs = clock()
            setRadioState(if (previous == RadioState.CONVERSING) RadioState.CONVERSING else RadioState.RADIO)
        }
    }

    /** User picked a place in the UI: narrate it now, even if heard before. */
    fun tellAbout(placeId: String) = scope.launch {
        val c = ranked.firstOrNull { it.place.id == placeId } ?: return@launch
        speechJob?.cancel()
        if (_state.value.radioState == RadioState.IDLE) return@launch
        speakStory(c)
    }

    fun ask(text: String) = scope.launch {
        if (text.isBlank()) return@launch
        beginConversation()
        speechJob = scope.launch { handleUtterance(text.trim()) }
    }

    fun askAudio(audioBytes: ByteArray, fileName: String, mimeType: String) = scope.launch {
        beginConversation()
        speechJob = scope.launch {
            val text = try {
                setStatus("Listening…")
                speech.transcribe(audioBytes, fileName, mimeType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Couldn't transcribe: ${e.message}")
                return@launch
            }
            if (text.isBlank()) {
                setStatus("Didn't catch that")
                return@launch
            }
            setStatus(null)
            handleUtterance(text)
        }
    }

    fun whatsNearby() = ask("What else is interesting nearby?")

    fun clearHistory() = scope.launch {
        heard.clear()
        recentTitles.clear()
        persistHeard()
        rerank()
    }

    // ---- scheduler ------------------------------------------------------------------------

    private fun tick() {
        val s = _state.value
        val speaking = speechJob?.isActive == true
        if (s.radioState == RadioState.CONVERSING && !speaking && clock() >= engagedUntilMs) {
            setRadioState(RadioState.RADIO)
        }
        if (_state.value.radioState != RadioState.RADIO || speaking || discoveryJob?.isActive == true) return
        if (clock() < engagedUntilMs) return
        rerank()
        val pick = ranker.pickForAirtime(ranked) ?: return
        speakStory(pick)
    }

    private fun refresh(ctx: LocationContext) {
        if (discoveryJob?.isActive == true) return
        val lang = Languages.find(sessionLanguage)?.base ?: "en"
        discoveryJob = scope.launch {
            _state.update { it.copy(discovering = true) }
            try {
                areaLabeler?.let { labeler ->
                    runCatching { labeler.label(Geo.quantize(ctx.point, 2)) }.getOrNull()?.let { area ->
                        _state.update { it.copy(area = area) }
                    }
                }
                val radius = refreshPolicy.searchRadiusM(ctx.travelMode)
                val found = places.discover(refreshPolicy.searchCenter(ctx), radius, lang)
                if (lang != lastRefreshLang) candidates.clear()
                found.forEach { candidates[it.id] = it }
                // Old candidates decay: forget anything far outside the current search area.
                candidates.values.removeAll { Geo.distanceM(ctx.point, it.point) > radius * 3.0 }
                lastRefreshPoint = ctx.point
                lastRefreshMode = ctx.travelMode
                lastRefreshMs = ctx.timestampMs
                lastRefreshLang = lang
                if (_state.value.status?.startsWith("Couldn't load") == true) setStatus(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep whatever is cached; never invent places to fill the gap.
                setStatus("Couldn't load nearby places: ${e.message}")
                lastRefreshMs = ctx.timestampMs - 14 * 60_000L // retry in about a minute
                lastRefreshPoint = lastRefreshPoint ?: ctx.point
                lastRefreshMode = lastRefreshMode ?: ctx.travelMode
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
        topicPenalty.forEach { (t, p) -> interests[t] = (interests[t] ?: 0.4) * p }
        ranked = ranker.rank(
            candidates.values.filter { it.id !in failedIds },
            EditorialRanker.Context(
                location = loc,
                interests = interests,
                heard = heard,
                nowMs = now,
                mentionedIds = mentionedIds,
                lastSpeechEndMs = lastSpeechEndMs,
                userEngaged = now < engagedUntilMs || _state.value.radioState == RadioState.CONVERSING,
                theme = _state.value.theme,
            ),
        )
        _state.update { it.copy(nearby = ranked.take(25)) }
    }

    // ---- narration ------------------------------------------------------------------------

    private fun speakStory(c: RankedCandidate) {
        val loc = _state.value.location ?: return
        speechJob = scope.launch {
            setRadioState(RadioState.RESEARCHING)
            try {
                val lang = sessionLanguage
                val segment = narrator.narrate(
                    NarrationRequest(c, loc, lang, config().interests, recentTitles.toList()),
                )
                val bytes = speech.synthesize(segment.text, lang)
                activeId = c.place.id
                heard.markHeard(c.place.id, c.place.name, clock())
                persistHeard()
                recentTitles += c.place.name
                lastAudio = bytes
                addTranscript(TranscriptEntry(Speaker.RADIO, segment.text, clock(), c.place.id, segment.sources))
                _state.update { it.copy(radioState = RadioState.NARRATING, nowPlaying = segment) }
                audio.play(bytes)
                lastSpeechEndMs = clock()
                setRadioState(RadioState.RADIO)
                rerank()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedIds += c.place.id
                lastSpeechEndMs = clock()
                fail("Narration failed: ${e.message}")
                setRadioState(RadioState.RADIO)
            }
        }
    }

    // ---- conversation ---------------------------------------------------------------------

    private fun beginConversation() {
        speechJob?.cancel()
        engagedUntilMs = clock() + conversationIdleMs
        setRadioState(RadioState.CONVERSING)
    }

    private suspend fun handleUtterance(text: String) {
        addTranscript(TranscriptEntry(Speaker.USER, text, clock()))
        when (localCommand(text)) {
            ConversationAction.SKIP -> { skip(); return }
            ConversationAction.RESUME_RADIO -> { engagedUntilMs = 0; setRadioState(RadioState.RADIO); return }
            ConversationAction.PAUSE -> { doPause(); return }
            else -> Unit
        }
        val active = activeId?.let { id -> ranked.firstOrNull { it.place.id == id } }
        val reply: ConversationReply = try {
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
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Couldn't answer: ${e.message}")
            return
        }
        history += ConversationTurn(true, text)
        history += ConversationTurn(false, reply.reply)
        while (history.size > 24) history.removeAt(0)
        reply.entityId?.takeIf { id -> candidates.containsKey(id) }?.let { id ->
            activeId = id
            mentionedIds += id
        }
        applyActionBeforeSpeaking(reply)
        addTranscript(TranscriptEntry(Speaker.RADIO, reply.reply, clock(), reply.entityId, reply.sources))

        if (reply.reply.isNotBlank()) {
            val bytes = try {
                speech.synthesize(reply.reply, sessionLanguage)
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
            ConversationAction.RESUME_RADIO, ConversationAction.SKIP -> {
                engagedUntilMs = 0
                setRadioState(RadioState.RADIO)
            }
            ConversationAction.PAUSE -> setRadioState(RadioState.PAUSED)
            else -> engagedUntilMs = clock() + conversationIdleMs
        }
    }

    private fun applyActionBeforeSpeaking(reply: ConversationReply) {
        when (reply.action) {
            ConversationAction.CHANGE_LANGUAGE -> reply.language?.let { requested ->
                val tag = Languages.find(requested)?.tag ?: requested
                languageOverride = tag
                if (reply.persistLanguage) onPersistLanguage(tag)
                _state.update { it.copy(sessionLanguage = tag) }
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
            else -> Unit
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun doPause() {
        speechJob?.cancel()
        if (_state.value.radioState != RadioState.IDLE) setRadioState(RadioState.PAUSED)
    }

    private fun penalize(place: PlaceCandidate) {
        heard.markHeard(place.id, place.name, clock())
        persistHeard()
        place.topics.forEach { t -> topicPenalty[t] = (topicPenalty[t] ?: 1.0) * 0.6 }
    }

    private fun persistHeard() {
        runCatching { historyStore.save(heard.serialize(clock())) }
    }

    private fun setRadioState(s: RadioState) = _state.update { it.copy(radioState = s) }

    private fun setStatus(msg: String?) = _state.update { it.copy(status = msg) }

    private fun fail(msg: String) {
        setStatus(msg)
        addTranscript(TranscriptEntry(Speaker.SYSTEM, msg, clock()))
    }

    private fun addTranscript(e: TranscriptEntry) =
        _state.update { it.copy(transcript = (it.transcript + e).takeLast(100)) }

    companion object {
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
