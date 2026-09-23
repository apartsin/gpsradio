package com.gpsradio.core

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.tour.TourPlanner
import com.gpsradio.core.tour.TourState
import com.gpsradio.core.tour.TourText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TourPlannerTest {
    private val here = GeoPoint(47.61, 13.78)
    private val planner = TourPlanner()
    private val zero = ScoreBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun ranked(id: String, bearing: Double, d: Double, score: Double): RankedCandidate {
        val p = place(id, Geo.destination(here, bearing, d))
        return RankedCandidate(p, d, bearing, score, zero)
    }

    private fun length(route: List<PlaceCandidate>, loop: Boolean = true): Double {
        var total = 0.0
        var prev = here
        route.forEach { total += Geo.distanceM(prev, it.point); prev = it.point }
        if (loop) total += Geo.distanceM(prev, here)
        return total
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list) else list.flatMap { x -> permutations(list - x).map { listOf(x) + it } }

    @Test
    fun plansThreeToSixStopsWithinTheBudget() {
        val candidates = (0 until 12).map { i -> ranked("p$i", i * 30.0, 150.0 + 20 * i, 3.0 - i * 0.1) }
        for (minutes in listOf(30, 60)) {
            val plan = assertNotNull(planner.plan(candidates, here, minutes))
            assertTrue(plan.stops.size in 3..6, "stops for $minutes min: ${plan.stops.size}")
            assertTrue(plan.totalMin <= minutes, "total ${plan.totalMin} > $minutes")
            assertEquals(minutes, plan.minutes)
            assertTrue(plan.loop)
        }
        val short = planner.plan(candidates, here, 30)!!
        val long = planner.plan(candidates, here, 60)!!
        assertTrue(long.stops.size >= short.stops.size)
        assertEquals(6, long.stops.size, "an hour around a dozen close sights fills the six stops")
    }

    @Test
    fun legsAndEtaAddUp() {
        val candidates = listOf(ranked("a", 0.0, 150.0, 3.0), ranked("b", 90.0, 200.0, 2.9), ranked("c", 180.0, 180.0, 2.8))
        val plan = planner.plan(candidates, here, 30)!!
        var prev = here
        var eta = 0.0
        plan.stops.forEachIndexed { i, s ->
            assertEquals(Geo.distanceM(prev, s.point), s.legM, 0.01)
            eta += planner.walkMinutes(s.legM) + if (i > 0) planner.minutesPerStop else 0.0
            assertEquals(eta, s.etaMin, 0.001)
            prev = s.point
        }
        assertTrue(plan.stops.zipWithNext().all { (x, y) -> y.etaMin > x.etaMin })
        assertEquals(length(plan.stops.map { it.place }), plan.totalWalkM, 0.01)
        assertEquals(planner.walkMinutes(plan.totalWalkM) + 4.0 * plan.stops.size, plan.totalMin, 0.001)
    }

    @Test
    fun routeOrderIsOptimalForSmallTours() {
        // Scattered points: nearest neighbour alone would zig-zag; 2-opt removes crossings.
        val pts = listOf(
            ranked("n", 0.0, 300.0, 3.0), ranked("s", 180.0, 300.0, 3.0), ranked("e", 90.0, 310.0, 3.0),
            ranked("w", 270.0, 290.0, 3.0), ranked("ne", 45.0, 120.0, 3.0),
        ).map { it.place }
        val route = planner.order(pts, here)
        val best = permutations(pts).minOf { length(it) }
        assertEquals(best, length(route), 1.0)
        assertEquals(pts.toSet(), route.toSet())
    }

    @Test
    fun prefersHighScoresWhenOnlySomeFit() {
        // Five sights at similar distances in different directions; only a few fit into 30 minutes.
        val candidates = listOf(
            ranked("best", 0.0, 200.0, 4.0), ranked("good", 60.0, 200.0, 3.5), ranked("ok", 120.0, 200.0, 3.2),
            ranked("meh", 180.0, 200.0, 1.0), ranked("poor", 240.0, 200.0, 0.5),
        )
        val plan = planner.plan(candidates, here, 30)!!
        val ids = plan.stops.map { it.id }.toSet()
        assertTrue("best" in ids && "good" in ids, "picked $ids")
        assertFalse("poor" in ids && "best" !in ids)
    }

    @Test
    fun farAwayPlacesAreLeftOutAndTinyBudgetsFallBackOrFail() {
        val near = listOf(ranked("a", 0.0, 60.0, 3.0), ranked("b", 90.0, 70.0, 2.0))
        val far = ranked("far", 180.0, 3_000.0, 9.0)
        val plan = planner.plan(near + far, here, 30)!!
        assertEquals(setOf("a", "b"), plan.stops.map { it.id }.toSet(), "two close stops is better than none")
        assertNull(planner.plan(listOf(far), here, 60))
        assertNull(planner.plan(emptyList(), here, 30))
        assertNull(planner.plan(near, here, 0))
    }

    @Test
    fun capsAtSixStopsAndIgnoresDuplicates() {
        val candidates = (0 until 20).map { i -> ranked("p$i", i * 18.0, 40.0 + i, 3.0) }
        val plan = planner.plan(candidates + candidates, here, 120)!!
        assertEquals(6, plan.stops.size)
        assertEquals(6, plan.stops.map { it.id }.toSet().size)
    }

    @Test
    fun hostLineDraftsNameStopsAndDirections() {
        val candidates = listOf(ranked("a", 0.0, 150.0, 3.0), ranked("b", 90.0, 120.0, 2.9), ranked("c", 180.0, 130.0, 2.8))
        val t = TourState.of(planner.plan(candidates, here, 30)!!)
        val intro = TourText.intro(t, here, null)
        assertTrue(intro.startsWith("Here's a 30-minute loop with 3 stops: "), intro)
        t.stops.forEach { assertTrue(it.name in intro) }
        assertTrue("First stop: ${t.stops[0].name}, about" in intro, intro)

        // Heading known: relative directions; otherwise compass.
        val north = Geo.destination(here, 0.0, 250.0)
        assertEquals("about 250 metres ahead", TourText.way(here, 0.0, north))
        assertEquals("about 250 metres to your left", TourText.way(here, 90.0, north))
        assertEquals("about 250 metres to the north", TourText.way(here, null, north))
        assertEquals("just a few steps away", TourText.way(here, null, Geo.destination(here, 0.0, 20.0)))
        assertEquals("about 1.5 km", TourText.distance(1_520.0))
        assertEquals("Next stop: X, about 250 metres to your left.", TourText.next(t.stops[0].copy(place = place("x", north, name = "X")), here, 90.0))

        val atEnd = Geo.destination(here, 90.0, 300.0)
        val finish = TourText.finish(t.copy(nextIndex = 3), atEnd, null)
        assertTrue(finish.startsWith("That was the last stop of our 30-minute tour."))
        assertTrue("Your starting point is about 300 metres to the west." in finish, finish)
        assertEquals("A, B and C", TourText.names(listOf("A", "B", "C")))
        assertTrue(t.copy(nextIndex = 3).finished)
        assertNull(t.copy(nextIndex = 3).next)
    }
}
