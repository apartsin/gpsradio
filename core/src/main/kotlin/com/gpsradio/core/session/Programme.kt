package com.gpsradio.core.session

import com.gpsradio.core.ai.QuizQuestion
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.AreaFacet
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.TravelMode

/**
 * The station's running order between place stories (spec A §18 item 5): "did you know" bumpers,
 * on this day, quizzes, area stories and a station ID with a recap. [next] is a pure decision over
 * the current [Situation] and the programme's own small memory; the session reports what aired.
 *
 * Rules:
 * - never two fillers in a row (a place story must air in between), except in [Pacing.NONSTOP];
 * - never while a good place story is ready (except the station ID, due every ~10 stories);
 * - never while driving through a dense area, where stories come thick and fast anyway;
 * - fillers respect the pacing dial: the full gap since the last segment and a per-pacing filler gap;
 * - a pending quiz answer is revealed before anything else airs.
 *
 * [Pacing.NONSTOP] keeps talking when no story qualifies, in this order: (1) weaker nearby places
 * above a relaxed threshold, (2) area stories about the current town/region, one facet at a time,
 * (3) bumpers, on this day and quizzes, (4) a wider discovery radius. Everything stays grounded
 * and nothing is repeated.
 */
class Programme(val config: Config = Config()) {

    data class Config(
        val stationIdEveryStories: Int = 10,
        /** Titles needed before a recap makes sense. */
        val stationIdMinTitles: Int = 3,
        val quizGapMs: Long = 10 * 60_000L,
        val bumperMinFactsChars: Int = 120,
        val quizMinFactsChars: Int = 250,
        /** A place counts as "nearby" for a bumper or quiz when its proximity score is at least this. */
        val minProximity: Double = 0.2,
        /** Driving with at least this many candidates within [denseRadiusM] is a dense area: no fillers. */
        val denseCount: Int = 8,
        val denseRadiusM: Double = 3_000.0,
        /** Non-stop: weaker places qualify down to this fraction of the speak threshold. */
        val relaxedFactor: Double = 0.5,
        /** Non-stop: how many times the discovery radius may be widened per session. */
        val maxWidenSteps: Int = 3,
        /** At most one photo tip per this interval. */
        val photoGapMs: Long = 15 * 60_000L,
        /** Knowledge quizzes; off by product decision (listeners don't want to be tested). */
        val quizzes: Boolean = false,
    )

    /** What the session knows right now; [minGapMs] is the pacing-scaled gap for the travel mode. */
    data class Situation(
        val nowMs: Long,
        val mode: TravelMode,
        val pacing: Pacing,
        val minGapMs: Long,
        val lastSpeechEndMs: Long?,
        /** A place story would qualify once the post-segment penalty has decayed. */
        val storyReady: Boolean,
        val ranked: List<RankedCandidate>,
        val recentTitles: List<String>,
        val onThisDayAvailable: Boolean,
        /** Calendar day, e.g. "09-23": on this day airs at most once per day. */
        val dayKey: String,
        val themeActive: Boolean = false,
        /** The pacing-scaled speak threshold (for the non-stop relaxed threshold). */
        val speakThreshold: Double = 2.4,
        /** Facets of the current town/region with grounded facts, in telling order. */
        val areaFacets: List<AreaFacet> = emptyList(),
        /** The best photogenic spot for a photo tip right now (see PhotoSpots.suitable), if any. */
        val photoSpot: RankedCandidate? = null,
        /** Events today nearby that haven't been announced yet: time-sensitive, so they go before stories. */
        val eventsDue: Boolean = false,
    )

    sealed interface Plan {
        /** Nothing from the programme: let the editorial ranker decide. */
        data object None : Plan
        /** Too soon after the last segment: stay silent this tick (nothing airs, not even a story). */
        data object Wait : Plan
        data class RevealQuiz(val quiz: QuizQuestion) : Plan
        data class Filler(
            val format: SegmentFormat,
            val candidate: RankedCandidate? = null,
            val areaFacet: AreaFacet? = null,
        ) : Plan
        /** Non-stop: tell a full story about a weaker (but grounded, unheard) nearby place. */
        data class RelaxedStory(val candidate: RankedCandidate) : Plan
        /** Non-stop, nothing left: search a wider area. */
        data object WidenSearch : Plan
    }

    private var lastWasFiller = false
    private var storiesSinceStationId = 0
    private var lastFillerMs: Long? = null
    private val lastAired = HashMap<SegmentFormat, Long>()
    private val usedPlaceIds = HashSet<String>()
    private val usedFacetIds = LinkedHashSet<String>()
    private var onThisDayDoneFor: String? = null
    private var widenSteps = 0

    /** The quiz asked and not yet revealed or answered. */
    var pendingQuiz: QuizQuestion? = null
        private set

    /** Area facets told this session (ids), oldest first. */
    val toldFacets: List<String> get() = usedFacetIds.toList()

    /** The hard minimum silence before any segment: the full gap when driving, a quarter of it otherwise. */
    fun storyGapMs(mode: TravelMode, minGapMs: Long, pacing: Pacing = Pacing.BALANCED): Long =
        if (mode == TravelMode.DRIVING) minGapMs.coerceAtLeast(pacing.drivingMinGapMs) else minGapMs / 4

    fun isDense(s: Situation): Boolean =
        s.mode == TravelMode.DRIVING && s.ranked.count { it.distanceM <= config.denseRadiusM } >= config.denseCount

    fun next(s: Situation): Plan {
        pendingQuiz?.let { return Plan.RevealQuiz(it) }
        val sinceSpeech = s.lastSpeechEndMs?.let { s.nowMs - it } ?: Long.MAX_VALUE
        if (sinceSpeech < storyGapMs(s.mode, s.minGapMs, s.pacing)) return Plan.Wait
        val nonstop = s.pacing == Pacing.NONSTOP
        if ((lastWasFiller && !nonstop) || isDense(s)) return Plan.None
        // Non-stop is a continuous stream of stories: no recap breaks (they can't be prepared ahead either).
        if (!nonstop && storiesSinceStationId >= config.stationIdEveryStories && s.recentTitles.size >= config.stationIdMinTitles) {
            return Plan.Filler(SegmentFormat.STATION_ID)
        }
        // "Tonight at eight…" loses its value if it waits for a quiet moment.
        if (s.eventsDue) return Plan.Filler(SegmentFormat.EVENTS)
        if (s.storyReady) return Plan.None
        if (nonstop) {
            // A photo stop is time-bound (light, being there): when one is due it goes before researched area stories,
            // which would otherwise always be available in the endless loop and crowd it out.
            val plan = photoTip(s)
                ?: relaxed(s)?.let { Plan.RelaxedStory(it) }
                ?: freshFacet(s)?.let { Plan.Filler(SegmentFormat.AREA, areaFacet = it) }
                ?: rotation(s)
            // Widening is silent network work: start it right away, so new places are ready when the gap ends.
            if (plan == null) return if (widenSteps < config.maxWidenSteps) Plan.WidenSearch else Plan.None
            return if (sinceSpeech < s.minGapMs) Plan.None else plan
        }
        if (sinceSpeech < s.minGapMs) return Plan.None
        lastFillerMs?.let { if (s.nowMs - it < s.pacing.fillerGapMs) return Plan.None }
        return rotation(s) ?: Plan.None
    }

    /** Bumper, quiz, on this day, photo tips and area stories, rotated: the format aired longest ago (or never) goes first. */
    private fun rotation(s: Situation): Plan.Filler? {
        val options = ArrayList<Plan.Filler>()
        if (s.onThisDayAvailable && !s.themeActive && onThisDayDoneFor != s.dayKey) options += Plan.Filler(SegmentFormat.ON_THIS_DAY)
        photoTip(s)?.let { options += it }
        fresh(s, config.bumperMinFactsChars)?.let { options += Plan.Filler(SegmentFormat.BUMPER, it) }
        val quizOk = lastAired[SegmentFormat.QUIZ]?.let { s.nowMs - it >= config.quizGapMs } ?: true
        if (config.quizzes && quizOk) fresh(s, config.quizMinFactsChars)?.let { options += Plan.Filler(SegmentFormat.QUIZ, it) }
        if (!s.themeActive) freshFacet(s)?.let { options += Plan.Filler(SegmentFormat.AREA, areaFacet = it) }
        return options.minByOrNull { lastAired[it.format] ?: Long.MIN_VALUE }
    }

    /** A photo tip for the current photogenic spot, at most one per [Config.photoGapMs] and never twice for a place. */
    private fun photoTip(s: Situation): Plan.Filler? {
        val due = lastAired[SegmentFormat.PHOTO_TIP]?.let { s.nowMs - it >= config.photoGapMs } ?: true
        return s.photoSpot?.takeIf { due && it.place.id !in usedPlaceIds }?.let { Plan.Filler(SegmentFormat.PHOTO_TIP, it) }
    }

    /** The best nearby place that hasn't been told, mentioned or used by a filler, with enough facts. */
    private fun fresh(s: Situation, minChars: Int): RankedCandidate? = s.ranked.firstOrNull { r ->
        r.breakdown.novelty >= 1.0 &&
            r.place.id !in usedPlaceIds &&
            r.breakdown.proximity >= config.minProximity &&
            (r.place.extract ?: r.place.description ?: "").length >= minChars
    }

    /** Non-stop: the best unheard place with some facts above the relaxed threshold. */
    private fun relaxed(s: Situation): RankedCandidate? = s.ranked.firstOrNull { r ->
        r.breakdown.novelty > 0.0 &&
            r.place.id !in usedPlaceIds &&
            r.score >= s.speakThreshold * config.relaxedFactor &&
            !(r.place.extract ?: r.place.description).isNullOrBlank()
    }

    private fun freshFacet(s: Situation): AreaFacet? = s.areaFacets.firstOrNull { it.id !in usedFacetIds }

    fun onStoryAired() {
        lastWasFiller = false
        storiesSinceStationId++
    }

    fun onFillerAired(format: SegmentFormat, placeId: String?, nowMs: Long, dayKey: String, facetId: String? = null) {
        lastWasFiller = true
        markUsed(format, placeId, nowMs, dayKey, facetId)
    }

    /** A filler that could not be made: don't retry it straight away, but it doesn't count as aired. */
    fun onFillerFailed(format: SegmentFormat, placeId: String?, nowMs: Long, dayKey: String, facetId: String? = null) =
        markUsed(format, placeId, nowMs, dayKey, facetId)

    private fun markUsed(format: SegmentFormat, placeId: String?, nowMs: Long, dayKey: String, facetId: String?) {
        lastFillerMs = nowMs
        lastAired[format] = nowMs
        placeId?.let { usedPlaceIds += it }
        facetId?.let { usedFacetIds += it }
        if (format == SegmentFormat.STATION_ID) storiesSinceStationId = 0
        if (format == SegmentFormat.ON_THIS_DAY) onThisDayDoneFor = dayKey
    }

    /** The session widened the discovery radius. */
    fun onWidened() {
        widenSteps++
    }

    fun onQuizAsked(quiz: QuizQuestion) {
        pendingQuiz = quiz
    }

    /** The listener answered (the conversation revealed it) or the reveal aired. */
    fun onQuizResolved() {
        pendingQuiz = null
    }

    /** Area stories told on earlier days (spec A §40): not told again. */
    fun preloadToldFacets(ids: Collection<String>) { usedFacetIds += ids }

    fun reset() {
        lastWasFiller = false
        storiesSinceStationId = 0
        lastFillerMs = null
        lastAired.clear()
        usedPlaceIds.clear()
        usedFacetIds.clear()
        onThisDayDoneFor = null
        widenSteps = 0
        pendingQuiz = null
    }
}
