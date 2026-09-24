package com.gpsradio.core

import com.gpsradio.core.ai.QuizQuestion
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.AreaFacet
import com.gpsradio.core.discovery.AreaFacetKind
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.Programme
import com.gpsradio.core.session.Programme.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgrammeTest {
    private val here = GeoPoint(47.61, 13.78)
    private val facts = "The old mill ground flour for the whole valley for three centuries. ".repeat(6)
    private val t0 = 10_000_000L

    private fun rc(
        id: String,
        d: Double = 300.0,
        novelty: Double = 1.0,
        proximity: Double = 0.6,
        extract: String? = facts,
        score: Double = 2.0,
    ) = RankedCandidate(
        place(id, Geo.destination(here, 0.0, d)).copy(extract = extract),
        distanceM = d,
        bearingDeg = 0.0,
        score = score,
        breakdown = ScoreBreakdown(0.1, 0.4, novelty, proximity, 0.5, 0.85, if (novelty == 0.0) 1.0 else 0.0, 0.0),
    )

    private fun sit(
        now: Long = t0,
        lastEnd: Long? = t0 - 120_000,
        mode: TravelMode = TravelMode.WALKING,
        pacing: Pacing = Pacing.BALANCED,
        storyReady: Boolean = false,
        ranked: List<RankedCandidate> = listOf(rc("w1"), rc("w2")),
        titles: List<String> = listOf("A", "B", "C"),
        otd: Boolean = false,
        day: String = "09-23",
        theme: Boolean = false,
        facets: List<AreaFacet> = emptyList(),
    ) = Programme.Situation(
        nowMs = now,
        mode = mode,
        pacing = pacing,
        minGapMs = EditorialRanker().minGapMs(mode, pacing),
        lastSpeechEndMs = lastEnd,
        storyReady = storyReady,
        ranked = ranked,
        recentTitles = titles,
        onThisDayAvailable = otd,
        dayKey = day,
        themeActive = theme,
        speakThreshold = EditorialRanker().thresholdFor(pacing),
        areaFacets = facets,
    )

    @Test
    fun noFillerWhileAGoodStoryIsReady() {
        val p = Programme()
        assertEquals(Plan.None, p.next(sit(storyReady = true)))
        assertTrue(p.next(sit(storyReady = false)) is Plan.Filler)
    }

    @Test
    fun bumperWhenNothingQualifiesAfterTheFullGap() {
        val p = Programme()
        // Walking: base gap 45 s; the hard floor is a quarter of it.
        assertEquals(Plan.Wait, p.next(sit(lastEnd = t0 - 5_000)))
        assertEquals(Plan.None, p.next(sit(lastEnd = t0 - 30_000)))
        val plan = p.next(sit(lastEnd = t0 - 45_000))
        assertEquals(Plan.Filler(SegmentFormat.BUMPER, rc("w1")), plan)
    }

    @Test
    fun neverTwoFillersInARow() {
        val p = Programme()
        val first = p.next(sit()) as Plan.Filler
        p.onFillerAired(first.format, first.candidate?.place?.id, t0, "09-23")
        // Long after, still nothing: a place story has to air first.
        assertEquals(Plan.None, p.next(sit(now = t0 + 3_600_000, lastEnd = t0)))
        p.onStoryAired()
        val second = p.next(sit(now = t0 + 3_600_000, lastEnd = t0 + 3_000_000))
        assertTrue(second is Plan.Filler)
        // The place used by the first bumper is not reused.
        assertTrue(second.candidate?.place?.id != "w1")
    }

    @Test
    fun quizzesRotateInOnlyWhenEnabled() {
        val p = Programme(Programme.Config(quizzes = true))
        var now = t0
        val formats = mutableListOf<SegmentFormat>()
        repeat(3) {
            val plan = p.next(sit(now = now, lastEnd = now - 120_000, otd = true, ranked = (1..6).map { rc("w$it") })) as Plan.Filler
            formats += plan.format
            p.onFillerAired(plan.format, plan.candidate?.place?.id, now, "09-23")
            p.onStoryAired()
            now += 20 * 60_000L
        }
        assertEquals(listOf(SegmentFormat.ON_THIS_DAY, SegmentFormat.BUMPER, SegmentFormat.QUIZ), formats)
    }

    @Test
    fun formatsRotate() {
        val p = Programme()
        var now = t0
        val formats = mutableListOf<SegmentFormat>()
        repeat(4) {
            val plan = p.next(sit(now = now, lastEnd = now - 120_000, otd = true, ranked = (1..6).map { rc("w$it") })) as Plan.Filler
            formats += plan.format
            p.onFillerAired(plan.format, plan.candidate?.place?.id, now, "09-23")
            p.onStoryAired()
            now += 20 * 60_000L
        }
        // On this day first (once per day), then bumpers. No knowledge quizzes: listeners don't want to be tested.
        assertEquals(listOf(SegmentFormat.ON_THIS_DAY, SegmentFormat.BUMPER, SegmentFormat.BUMPER, SegmentFormat.BUMPER), formats)
    }

    @Test
    fun onThisDayOncePerDayAndNotUnderATheme() {
        val p = Programme()
        assertEquals(SegmentFormat.BUMPER, (p.next(sit(otd = true, theme = true)) as Plan.Filler).format)
        assertEquals(SegmentFormat.ON_THIS_DAY, (p.next(sit(otd = true)) as Plan.Filler).format)
        p.onFillerAired(SegmentFormat.ON_THIS_DAY, null, t0, "09-23")
        p.onStoryAired()
        val later = t0 + 3_600_000
        val sameDay = p.next(sit(now = later, lastEnd = later - 120_000, otd = true, ranked = emptyList()))
        assertEquals(Plan.None, sameDay)
        val nextDay = p.next(sit(now = later, lastEnd = later - 120_000, otd = true, ranked = emptyList(), day = "09-24"))
        assertEquals(Plan.Filler(SegmentFormat.ON_THIS_DAY), nextDay)
    }

    @Test
    fun noFillersWhileDrivingThroughADenseArea() {
        val p = Programme()
        val dense = (1..10).map { rc("d$it", d = 1_000.0) }
        val s = sit(mode = TravelMode.DRIVING, ranked = dense, lastEnd = t0 - 600_000)
        assertTrue(p.isDense(s))
        assertEquals(Plan.None, p.next(s))
        val sparse = sit(mode = TravelMode.DRIVING, ranked = dense.take(3), lastEnd = t0 - 600_000)
        assertFalse(p.isDense(sparse))
        assertTrue(p.next(sparse) is Plan.Filler)
        // Walking in the same density is fine.
        assertTrue(p.next(sit(ranked = dense)) is Plan.Filler)
    }

    @Test
    fun drivingKeepsItsSafetyGapBetweenSegments() {
        val p = Programme()
        Pacing.entries.forEach { pacing ->
            val gap = p.storyGapMs(TravelMode.DRIVING, EditorialRanker().minGapMs(TravelMode.DRIVING, pacing), pacing)
            assertTrue(gap >= pacing.drivingMinGapMs)
            assertTrue(gap >= if (pacing == Pacing.NONSTOP) 4_000 else 90_000)
            assertEquals(Plan.Wait, p.next(sit(mode = TravelMode.DRIVING, pacing = pacing, lastEnd = t0 - pacing.drivingMinGapMs + 1_000, storyReady = true)))
            assertEquals(Plan.None, p.next(sit(mode = TravelMode.DRIVING, pacing = pacing, lastEnd = t0 - gap - 1_000, storyReady = true)))
        }
    }

    @Test
    fun stationIdEveryTenStoriesEvenWhenAStoryIsReady() {
        val p = Programme()
        repeat(9) { p.onStoryAired() }
        assertEquals(Plan.None, p.next(sit(storyReady = true)))
        p.onStoryAired()
        assertEquals(Plan.Filler(SegmentFormat.STATION_ID), p.next(sit(storyReady = true)))
        // Needs a few titles to recap.
        assertEquals(Plan.None, p.next(sit(storyReady = true, titles = listOf("A"))))
        p.onFillerAired(SegmentFormat.STATION_ID, null, t0, "09-23")
        p.onStoryAired()
        assertEquals(Plan.None, p.next(sit(storyReady = true)))
    }

    @Test
    fun bumperNeedsAFreshNearbyPlaceWithFacts() {
        val p = Programme()
        val none = listOf(
            rc("heard", novelty = 0.0),
            rc("mentioned", novelty = 0.4),
            rc("far", proximity = 0.05),
            rc("thin", extract = "Short."),
        )
        assertEquals(Plan.None, p.next(sit(ranked = none)))
        val plan = p.next(sit(ranked = none + rc("ok"))) as Plan.Filler
        assertEquals("ok", plan.candidate?.place?.id)
    }

    @Test
    fun quizNeedsRicherFactsAndAGapAndIsRevealedFirst() {
        val p = Programme(Programme.Config(quizzes = true))
        // Enough for a bumper, not for a quiz.
        val medium = "x".repeat(150)
        p.onFillerAired(SegmentFormat.BUMPER, "b0", t0 - 3_600_000, "09-23")
        p.onStoryAired()
        // The quiz format is due (bumper aired last), but this place's facts only support a bumper.
        assertEquals(SegmentFormat.BUMPER, (p.next(sit(ranked = listOf(rc("m", extract = medium)))) as Plan.Filler).format)
        val quiz = p.next(sit(ranked = listOf(rc("q")))) as Plan.Filler
        assertEquals(SegmentFormat.QUIZ, quiz.format)
        p.onFillerAired(SegmentFormat.QUIZ, "q", t0, "09-23")
        val q = QuizQuestion("How long did the mill run?", "Three centuries!", "q")
        p.onQuizAsked(q)
        // The reveal comes before anything else, even right after speech.
        assertEquals(Plan.RevealQuiz(q), p.next(sit(lastEnd = t0, storyReady = true)))
        p.onQuizResolved()
        assertNull(p.pendingQuiz)
        p.onStoryAired()
        // Within 10 minutes of the last quiz, only a bumper is possible.
        val soon = t0 + 6 * 60_000L
        val next = p.next(sit(now = soon, lastEnd = soon - 120_000, ranked = listOf(rc("r"))))
        assertEquals(SegmentFormat.BUMPER, (next as Plan.Filler).format)
    }

    @Test
    fun pacingSetsTheFillerGap() {
        fun afterFiller(pacing: Pacing, minutes: Long): Plan {
            val p = Programme()
            p.onFillerAired(SegmentFormat.BUMPER, "b0", t0, "09-23")
            p.onStoryAired()
            val now = t0 + minutes * 60_000L
            return p.next(sit(now = now, lastEnd = now - 300_000, pacing = pacing))
        }
        assertTrue(afterFiller(Pacing.CHATTY, 3) is Plan.Filler)
        assertEquals(Plan.None, afterFiller(Pacing.BALANCED, 3))
        assertTrue(afterFiller(Pacing.BALANCED, 6) is Plan.Filler)
        assertEquals(Plan.None, afterFiller(Pacing.RARE, 6))
        assertTrue(afterFiller(Pacing.RARE, 13) is Plan.Filler)
    }

    @Test
    fun failedFillerIsNotRetriedStraightAwayButDoesNotCountAsAired() {
        val p = Programme()
        p.onFillerFailed(SegmentFormat.ON_THIS_DAY, null, t0, "09-23")
        assertEquals(Plan.None, p.next(sit(now = t0 + 60_000, lastEnd = t0 - 60_000, otd = true)))
        // Not a filler on air, so no story is needed first; and on this day is done for today.
        val later = t0 + 10 * 60_000L
        assertEquals(SegmentFormat.BUMPER, (p.next(sit(now = later, lastEnd = later - 120_000, otd = true)) as Plan.Filler).format)
    }

    @Test
    fun resetForgetsEverything() {
        val p = Programme()
        p.onFillerAired(SegmentFormat.BUMPER, "w1", t0, "09-23")
        p.onQuizAsked(QuizQuestion("q", "a"))
        p.reset()
        assertNull(p.pendingQuiz)
        assertEquals("w1", (p.next(sit()) as Plan.Filler).candidate?.place?.id)
    }

    // ---- non-stop ---------------------------------------------------------------------------

    @Test
    fun nonstopFallsBackThroughRelaxedPlacesAreaFillersThenAWiderSearch() {
        val p = Programme()
        val facets = listOf(
            AreaFacet("Gmunden", AreaFacetKind.OVERVIEW, facts),
            AreaFacet("Gmunden", AreaFacetKind.HISTORY, facts),
        )
        var now = t0
        fun step(ranked: List<RankedCandidate>): Plan {
            now += 10_000
            return p.next(sit(now = now, lastEnd = now - 6_000, pacing = Pacing.NONSTOP, ranked = ranked, otd = true, facets = facets))
        }
        val weak = rc("weak", score = 1.0) // below the non-stop threshold (1.68), above the relaxed one
        val tooWeak = rc("tooWeak", score = 0.5, extract = "x")
        // (1) a weaker nearby place, told as a full story
        assertEquals(Plan.RelaxedStory(weak), step(listOf(weak, tooWeak)))
        p.onStoryAired()
        val heardNow = listOf(rc("weak", score = 1.0, novelty = 0.0), tooWeak)
        // (2) area facets, one after another (fillers may follow fillers in non-stop)
        val a1 = step(heardNow) as Plan.Filler
        assertEquals(SegmentFormat.AREA to facets[0], a1.format to a1.areaFacet)
        p.onFillerAired(SegmentFormat.AREA, null, now, "09-23", facets[0].id)
        val a2 = step(heardNow) as Plan.Filler
        assertEquals(facets[1], a2.areaFacet)
        p.onFillerAired(SegmentFormat.AREA, null, now, "09-23", facets[1].id)
        assertEquals(listOf(facets[0].id, facets[1].id), p.toldFacets)
        // (3) regular fillers
        val otd = step(heardNow) as Plan.Filler
        assertEquals(SegmentFormat.ON_THIS_DAY, otd.format)
        p.onFillerAired(SegmentFormat.ON_THIS_DAY, null, now, "09-23")
        // (4) a wider search, a bounded number of times; then silence rather than repetition
        repeat(3) {
            assertEquals(Plan.WidenSearch, step(heardNow))
            p.onWidened()
        }
        assertEquals(Plan.None, step(heardNow))
    }

    @Test
    fun nonstopKeepsGapsShortButStillWaitsForReadyStoriesAndDrivingSafety() {
        val p = Programme()
        val gap = EditorialRanker().minGapMs(TravelMode.WALKING, Pacing.NONSTOP)
        assertTrue(gap <= 2_500, "non-stop walking gap is $gap ms")
        assertTrue(EditorialRanker().minGapMs(TravelMode.STATIONARY, Pacing.NONSTOP) <= 3_000)
        // A ready story goes to the ranker.
        assertEquals(Plan.None, p.next(sit(now = t0, lastEnd = t0 - 6_000, pacing = Pacing.NONSTOP, storyReady = true)))
        // Driving keeps talking too, with a short 4 s safety gap (other pacings keep 90 s).
        assertEquals(Plan.Wait, p.next(sit(now = t0, lastEnd = t0 - 2_000, pacing = Pacing.NONSTOP, mode = TravelMode.DRIVING)))
        assertTrue(p.next(sit(now = t0, lastEnd = t0 - 5_000, pacing = Pacing.NONSTOP, mode = TravelMode.DRIVING)) !is Plan.Wait)
        assertEquals(Plan.Wait, p.next(sit(now = t0, lastEnd = t0 - 30_000, pacing = Pacing.BALANCED, mode = TravelMode.DRIVING)))
    }

    // ---- pacing dial in the editorial ranker ------------------------------------------------

    @Test
    fun pacingScalesThresholdAndGap() {
        val r = EditorialRanker()
        assertEquals(r.speakThreshold, r.thresholdFor(Pacing.BALANCED))
        assertTrue(r.thresholdFor(Pacing.CHATTY) < r.speakThreshold)
        assertTrue(r.thresholdFor(Pacing.RARE) > r.speakThreshold)
        assertEquals(45_000, r.minGapMs(TravelMode.WALKING, Pacing.BALANCED))
        assertTrue(r.minGapMs(TravelMode.WALKING, Pacing.CHATTY) < 45_000)
        assertTrue(r.minGapMs(TravelMode.WALKING, Pacing.RARE) > 45_000)
        Pacing.entries.forEach { assertTrue(r.minGapMs(TravelMode.DRIVING, it) >= it.drivingMinGapMs) }
        assertEquals(4_000, r.minGapMs(TravelMode.DRIVING, Pacing.NONSTOP))
        assertEquals(90_000, r.minGapMs(TravelMode.DRIVING, Pacing.BALANCED))
        assertEquals(Pacing.RARE, Pacing.fromKey("rare"))
        assertEquals(Pacing.BALANCED, Pacing.fromKey(null))
        assertEquals(Pacing.BALANCED, Pacing.fromKey("nonsense"))
    }

    @Test
    fun chattyTalksAboutMorePlacesRareAboutFewer() {
        val r = EditorialRanker()
        val walking = LocationContext(here, 5f, 0, 1.3, 0.0, TravelMode.WALKING)
        val ctx = EditorialRanker.Context(walking, mapOf(Topic.HISTORY to 1.0), HeardHistory(), nowMs = 100_000)
        // Find a place scoring between the chatty and balanced thresholds, and one between balanced and rare.
        fun scoreAt(d: Double, relevance: Double) =
            r.rank(listOf(place("x", Geo.destination(here, 0.0, d), relevance = relevance, topics = emptySet())), ctx).single()
        val borderline = (1..80).map { scoreAt(100.0 + it * 50, 0.5) }
            .first { it.score < r.thresholdFor(Pacing.BALANCED) && it.score >= r.thresholdFor(Pacing.CHATTY) }
        assertNotNull(r.pickForAirtime(listOf(borderline), Pacing.CHATTY))
        assertNull(r.pickForAirtime(listOf(borderline), Pacing.BALANCED))
        val good = (1..80).map { scoreAt(100.0 + it * 50, 0.8) }
            .first { it.score >= r.thresholdFor(Pacing.BALANCED) && it.score < r.thresholdFor(Pacing.RARE) }
        assertNotNull(r.pickForAirtime(listOf(good), Pacing.BALANCED))
        assertNull(r.pickForAirtime(listOf(good), Pacing.RARE))
    }

    @Test
    fun storyReadyIgnoresTheTemporaryPostSegmentPenalty() {
        val r = EditorialRanker()
        val walking = LocationContext(here, 5f, 0, 1.3, 0.0, TravelMode.WALKING)
        val justSpoke = EditorialRanker.Context(walking, mapOf(Topic.HISTORY to 1.0), HeardHistory(), nowMs = 100_000, lastSpeechEndMs = 99_000)
        val ranked = r.rank(listOf(place("a", Geo.destination(here, 0.0, 1_200.0))), justSpoke)
        assertNull(r.pickForAirtime(ranked))
        assertTrue(r.storyReady(ranked))
        // The pacing dial also lengthens the post-segment penalty.
        val rare = r.rank(listOf(place("a", Geo.destination(here, 0.0, 1_200.0))), justSpoke.copy(nowMs = 130_000, pacing = Pacing.RARE))
        val balanced = r.rank(listOf(place("a", Geo.destination(here, 0.0, 1_200.0))), justSpoke.copy(nowMs = 130_000))
        assertTrue(rare.single().breakdown.conversationCost > balanced.single().breakdown.conversationCost)
    }
}
