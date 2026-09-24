package com.gpsradio.core

import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.discovery.CorridorDiscovery
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.editorial.RoadTrip
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.location.Corridor
import com.gpsradio.core.location.CorridorCache
import com.gpsradio.core.location.LocationProcessor
import com.gpsradio.core.location.ManeuverDetector
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RoadTripKind
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadTripTest {
    private val here = GeoPoint(47.61, 13.78)
    private val driving = LocationContext(here, 5f, 1_000_000, 25.0, 0.0, TravelMode.DRIVING)
    private val ranker = EditorialRanker()

    private fun site(id: String, at: GeoPoint, category: String, relevance: Double = 0.4, topics: Set<Topic> = setOf(Topic.NATURE)) =
        PlaceCandidate(id, id, category, at, "test", 0.85, relevance, topics, extract = "Facts about $id.")

    private fun ctx(loc: LocationContext = driving) =
        EditorialRanker.Context(loc, mapOf(Topic.HISTORY to 1.0), HeardHistory(), nowMs = loc.timestampMs)

    // ---- corridor geometry ------------------------------------------------------------------

    @Test
    fun corridorCellsLieAlongTheHeading() {
        val cells = Corridor.cells(here, 90.0)
        assertEquals(listOf(2_000.0, 6_000.0, 10_000.0), cells.map { it.aheadM })
        cells.forEach { c ->
            assertEquals(c.aheadM, Geo.distanceM(here, c.center), 1.0)
            assertEquals(0.0, Geo.angleDiff(Geo.bearingDeg(here, c.center), 90.0), 0.2)
            assertEquals(Corridor.DEFAULT_CELL_RADIUS_M, c.radiusM)
        }
        // Consecutive cells overlap, so the corridor has no gaps along the route.
        cells.zipWithNext().forEach { (a, b) -> assertTrue(Geo.distanceM(a.center, b.center) < a.radiusM + b.radiusM) }
        assertEquals(2, Corridor.cells(here, 0.0, offsetsM = listOf(1_000.0, 3_000.0), radiusM = 1_500).size)
    }

    @Test
    fun alongAndCrossTrackDistances() {
        // Heading east: 5 km ahead, then 3 km to the right (south).
        val p = Geo.destination(Geo.destination(here, 90.0, 5_000.0), 180.0, 3_000.0)
        assertEquals(5_000.0, Corridor.alongTrackM(here, 90.0, p), 50.0)
        assertEquals(3_000.0, Corridor.crossTrackM(here, 90.0, p), 50.0)
        assertEquals(3_000.0, Corridor.distanceToRouteM(here, 90.0, p), 50.0)
        // To the left is negative cross-track.
        val left = Geo.destination(Geo.destination(here, 90.0, 5_000.0), 0.0, 2_000.0)
        assertEquals(-2_000.0, Corridor.crossTrackM(here, 90.0, left), 50.0)
        // Behind the listener: distance to the route's start.
        val behind = Geo.destination(here, 270.0, 2_000.0)
        assertTrue(Corridor.alongTrackM(here, 90.0, behind) < 0)
        assertEquals(2_000.0, Corridor.distanceToRouteM(here, 90.0, behind), 50.0)
        // Beyond the end of the assumed route line: distance to its end.
        val beyond = Geo.destination(here, 90.0, 20_000.0)
        assertEquals(5_000.0, Corridor.distanceToRouteM(here, 90.0, beyond, lengthM = 15_000.0), 80.0)
        // Detour: there and back from the route line.
        assertEquals(6_000.0, Corridor.detourM(here, 90.0, p), 100.0)
    }

    @Test
    fun corridorCacheFetchesOnlyTheNewFarCellAfterMovingAhead() {
        val cache = CorridorCache(maxAgeMs = 60_000)
        val first = Corridor.cells(here, 0.0)
        assertEquals(first, cache.missing(first, "en", 0))
        first.forEach { cache.put(it, "en", listOf(site("p${it.aheadM.toInt()}", it.center, "x")), 0) }
        assertTrue(cache.missing(first, "en", 1_000).isEmpty())
        // 3 km later: the near and middle cells are covered by earlier ones; only the far one is new.
        val moved = Corridor.cells(Geo.destination(here, 0.0, 3_000.0), 0.0)
        assertEquals(listOf(10_000.0), cache.missing(moved, "en", 1_000).map { it.aheadM })
        assertEquals(setOf("p6000", "p10000"), cache.places(moved, "en", 1_000).map { it.id }.toSet())
        // Other language editions and expired entries are not reused.
        assertEquals(3, cache.missing(first, "de", 1_000).size)
        assertEquals(3, cache.missing(first, "en", 61_000).size)
    }

    private class CountingProvider(var fail: Boolean = false) : PlacesProvider {
        val calls = mutableListOf<Pair<GeoPoint, Int>>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String): List<PlaceCandidate> {
            calls += center to radiusM
            if (fail) throw IOException("offline")
            return listOf(PlaceCandidate("id${calls.size}", "P${calls.size}", "x", center, "test", 0.5, 0.5, emptySet()))
        }
    }

    @Test
    fun corridorDiscoveryFetchesMissingCellsAndMergesCached() = runBlocking {
        val provider = CountingProvider()
        val corridor = CorridorDiscovery(provider)
        val first = corridor.discover(Corridor.cells(here, 0.0), "en", 0)
        assertEquals(3, provider.calls.size)
        assertEquals(3, first.size)
        assertTrue(provider.calls.all { it.second == Corridor.DEFAULT_CELL_RADIUS_M })
        val second = corridor.discover(Corridor.cells(Geo.destination(here, 0.0, 3_000.0), 0.0), "en", 10_000)
        assertEquals(4, provider.calls.size)
        assertEquals(3, second.size)
        // Offline: cached cells still answer; nothing cached at all is an error.
        provider.fail = true
        val callsBefore = provider.calls.size
        assertTrue(corridor.discover(Corridor.cells(Geo.destination(here, 0.0, 6_000.0), 0.0), "en", 20_000).isNotEmpty())
        assertTrue(provider.calls.size > callsBefore) // the new far cell was attempted
        assertFailsWith<IOException> { corridor.discover(Corridor.cells(here, 180.0), "en", 30_000) }
    }

    // ---- road-trip categories -----------------------------------------------------------------

    @Test
    fun landmarksAheadAreVisibleFromTheRoad() {
        val peak = site("peak", Geo.destination(here, 30.0, 8_000.0), "natural: peak")
        assertEquals(RoadTripKind.VISIBLE, RoadTrip.classify(peak, driving))
        assertEquals(RoadTripKind.VISIBLE, RoadTrip.classify(site("lake", Geo.destination(here, 60.0, 5_000.0), "lake in Upper Austria"), driving))
        assertEquals(RoadTripKind.VISIBLE, RoadTrip.classify(site("vp", Geo.destination(here, 0.0, 3_000.0), "tourism: viewpoint"), driving))
        assertEquals(RoadTripKind.VISIBLE, RoadTrip.classify(site("lh", Geo.destination(here, 80.0, 2_000.0), "man_made: lighthouse"), driving))
        // Behind, too far, or not a landmark: no bonus.
        assertNull(RoadTrip.classify(site("peakBehind", Geo.destination(here, 180.0, 8_000.0), "natural: peak"), driving))
        assertNull(RoadTrip.classify(site("farPeak", Geo.destination(here, 0.0, 14_000.0), "natural: peak"), driving))
        assertNull(RoadTrip.classify(site("plaque", Geo.destination(here, 0.0, 2_000.0), "historic: memorial"), driving))
        // Only while driving.
        assertNull(RoadTrip.classify(peak, driving.copy(travelMode = TravelMode.WALKING)))
        assertNull(RoadTrip.classify(peak, driving.copy(headingDeg = null)))
    }

    @Test
    fun highRelevanceSightsNearTheRouteAreWorthAStop() {
        val museum = site("museum", Geo.destination(Geo.destination(here, 0.0, 9_000.0), 90.0, 2_000.0), "museum", relevance = 0.8)
        assertEquals(RoadTripKind.WORTH_A_STOP, RoadTrip.classify(museum, driving))
        // Too little material, too far off the route, or already behind: not worth a stop.
        assertNull(RoadTrip.classify(museum.copy(baseRelevance = 0.4), driving))
        assertNull(RoadTrip.classify(site("off", Geo.destination(here, 90.0, 8_000.0), "museum", relevance = 0.9), driving))
        assertNull(RoadTrip.classify(site("past", Geo.destination(here, 180.0, 1_000.0), "museum", relevance = 0.9), driving))
        // A castle near the route is worth a stop; a mountain summit is only visible.
        assertEquals(RoadTripKind.WORTH_A_STOP, RoadTrip.classify(site("castle", Geo.destination(here, 10.0, 4_000.0), "castle in Austria", 0.8), driving))
        assertEquals(RoadTripKind.VISIBLE, RoadTrip.classify(site("summit", Geo.destination(here, 10.0, 4_000.0), "natural: peak", 0.9), driving))
    }

    @Test
    fun rankerFlagsRoadTripCandidatesAndBoostsThem() {
        val peakAt = Geo.destination(here, 30.0, 8_000.0)
        val peak = site("peak", peakAt, "natural: peak")
        val plain = site("plain", peakAt, "place")
        val ranked = ranker.rank(listOf(plain, peak), ctx())
        assertEquals("peak", ranked.first().place.id)
        assertEquals(RoadTripKind.VISIBLE, ranked.first().roadTrip)
        assertNull(ranked.last().roadTrip)
        assertEquals(1.0, ranked.first().breakdown.roadTrip)
        assertEquals(ranker.weights.roadTrip, ranked.first().score - ranked.last().score, 1e-9)
        // The bonus lets a distant, visible landmark reach airtime where an ordinary place would not.
        assertNotNull(ranker.pickForAirtime(ranked))
        assertNull(ranker.pickForAirtime(ranker.rank(listOf(plain), ctx())))
        // No road-trip flags while walking.
        val walk = ctx(driving.copy(travelMode = TravelMode.WALKING, speedMps = 1.3))
        assertTrue(ranker.rank(listOf(peak), walk).all { it.roadTrip == null && it.breakdown.roadTrip == 0.0 })
    }

    @Test
    fun narrationCarriesTheRoadTripKindAndOffersNavigationForAStop() {
        val museum = site("museum", Geo.destination(Geo.destination(here, 0.0, 6_000.0), 90.0, 2_000.0), "museum", relevance = 0.8)
        val c = ranker.rank(listOf(museum), ctx()).single()
        assertEquals(RoadTripKind.WORTH_A_STOP, c.roadTrip)
        val req = NarrationRequest(c, driving, "en-US", setOf(Topic.HISTORY), emptyList())
        assertEquals(RoadTripKind.WORTH_A_STOP, req.roadTrip)
        assertEquals("worth_a_stop" to "about 4 km there and back", RadioAgent.roadTripContext(req))
        assertEquals("visible_from_road" to null, RadioAgent.roadTripContext(req.copy(roadTrip = RoadTripKind.VISIBLE)))
        assertNull(RadioAgent.roadTripContext(req.copy(roadTrip = null)))
        val instructions = RadioAgent.narrationInstructions("en-US")
        assertTrue("worth_a_stop" in instructions && "navigate there" in instructions)
        assertTrue("visible_from_road" in instructions)
        // Guardrails stay in place.
        assertTrue("Never invent or embellish facts" in instructions)
        assertTrue("Never invent opening hours" in instructions)
        assertTrue("offered_navigation" in RadioAgent.conversationInstructions("en-US", searchAvailable = false))
    }

    // ---- driving pacing -----------------------------------------------------------------------

    @Test
    fun drivingPacingUsesLongerGapsShortSegmentsAndJunctionSilence() {
        assertEquals(90_000, ranker.minGapMs(TravelMode.DRIVING))
        assertTrue(RadioAgent.targetSeconds(TravelMode.DRIVING) <= 30)
        val now = driving.timestampMs
        assertTrue(ranker.holdForPacing(driving, lastSpeechEndMs = now - 60_000, nowMs = now))
        assertFalse(ranker.holdForPacing(driving, lastSpeechEndMs = now - 95_000, nowMs = now))
        assertFalse(ranker.holdForPacing(driving, lastSpeechEndMs = null, nowMs = now))
        val junction = driving.copy(maneuvering = true)
        assertTrue(ranker.holdForPacing(junction, lastSpeechEndMs = null, nowMs = now))
        // A stale fix (tunnel) does not hold stories forever; walking is never held.
        assertFalse(ranker.holdForPacing(junction, lastSpeechEndMs = null, nowMs = now + 60_000))
        assertFalse(ranker.holdForPacing(junction.copy(travelMode = TravelMode.WALKING), now - 1_000, now))
        // Non-stop keeps talking while driving: ~4 s between segments, but junctions still mean silence.
        assertFalse(ranker.holdForPacing(driving, lastSpeechEndMs = now - 5_000, nowMs = now, pacing = Pacing.NONSTOP))
        assertTrue(ranker.holdForPacing(driving, lastSpeechEndMs = now - 2_000, nowMs = now, pacing = Pacing.NONSTOP))
        assertTrue(ranker.holdForPacing(junction, lastSpeechEndMs = null, nowMs = now, pacing = Pacing.NONSTOP))
    }

    @Test
    fun maneuverHeuristicDetectsBrakingAndTurns() {
        fun s(t: Long, v: Double, h: Double? = 0.0) = ManeuverDetector.Sample(t, v, h)
        assertFalse(ManeuverDetector.isManeuvering(listOf(s(0, 25.0), s(4_000, 24.0), s(8_000, 25.5))))
        // Hard braking into a roundabout.
        assertTrue(ManeuverDetector.isManeuvering(listOf(s(0, 25.0), s(4_000, 18.0), s(8_000, 11.0))))
        // Turning 90° at moderate speed.
        assertTrue(ManeuverDetector.isManeuvering(listOf(s(0, 8.0, 0.0), s(4_000, 7.0, 45.0), s(8_000, 8.0, 90.0))))
        // Heading noise while nearly stopped is ignored; a single sample is never a maneuver.
        assertFalse(ManeuverDetector.isManeuvering(listOf(s(0, 1.0, 0.0), s(4_000, 1.2, 120.0))))
        assertFalse(ManeuverDetector.isManeuvering(listOf(s(0, 25.0))))
    }

    @Test
    fun locationContextReportsManeuveringFromSpeedHistory() {
        val p = LocationProcessor()
        var t = 1_000_000L
        fun step(speed: Float, bearing: Float = 0f): LocationContext {
            t += 4_000
            return p.accept(LocationSample(here.lat, here.lon, 5f, t, speed, bearing), t)!!
        }
        repeat(5) { assertFalse(step(25f).maneuvering) }
        step(15f)
        assertTrue(step(9f).maneuvering)
        // Once the window only holds steady driving again, the flag clears.
        var last = step(20f)
        repeat(6) { last = step(20f) }
        assertFalse(last.maneuvering)
        assertTrue(step(20f, bearing = 90f).maneuvering)
    }
}
