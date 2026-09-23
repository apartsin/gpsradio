package com.gpsradio.core

import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.location.AreaRefreshPolicy
import com.gpsradio.core.location.LocationProcessor
import com.gpsradio.core.location.LocationProcessor.Companion.targetMode
import com.gpsradio.core.model.ActivityType
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityPriorTest {
    private val start = GeoPoint(47.61, 13.78)

    @Test
    fun speedOnlyDetectionIncludesCycling() {
        assertEquals(TravelMode.CYCLING, targetMode(TravelMode.WALKING, 5.0, null))
        assertEquals(TravelMode.CYCLING, targetMode(TravelMode.STATIONARY, 4.0, null))
        assertEquals(TravelMode.WALKING, targetMode(TravelMode.WALKING, 3.0, null))
        // Hysteresis: a cyclist slowing to 2.5 m/s stays cycling; a fast one becomes a vehicle.
        assertEquals(TravelMode.CYCLING, targetMode(TravelMode.CYCLING, 2.5, null))
        assertEquals(TravelMode.WALKING, targetMode(TravelMode.CYCLING, 1.5, null))
        assertEquals(TravelMode.DRIVING, targetMode(TravelMode.CYCLING, 10.0, null))
        // A car in slow traffic does not turn into a bicycle.
        assertEquals(TravelMode.DRIVING, targetMode(TravelMode.DRIVING, 5.0, null))
        assertEquals(TravelMode.DRIVING, targetMode(TravelMode.WALKING, 15.0, null))
    }

    @Test
    fun activityPriorResolvesTheAmbiguousBand() {
        // 3–7 m/s is ambiguous: runner, cyclist or slow car.
        assertEquals(TravelMode.WALKING, targetMode(TravelMode.WALKING, 4.5, ActivityType.RUNNING))
        assertEquals(TravelMode.CYCLING, targetMode(TravelMode.WALKING, 4.5, ActivityType.ON_BICYCLE))
        assertEquals(TravelMode.DRIVING, targetMode(TravelMode.WALKING, 4.5, ActivityType.IN_VEHICLE))
        // A fast cyclist downhill stays a cyclist with the prior...
        assertEquals(TravelMode.CYCLING, targetMode(TravelMode.CYCLING, 10.0, ActivityType.ON_BICYCLE))
        // ...but motorway speeds contradict it.
        assertEquals(TravelMode.DRIVING, targetMode(TravelMode.CYCLING, 20.0, ActivityType.ON_BICYCLE))
        // Stopped at the lights in a car is still driving; without the prior it would drop to stationary.
        assertEquals(TravelMode.DRIVING, targetMode(TravelMode.DRIVING, 0.1, ActivityType.IN_VEHICLE))
        assertEquals(TravelMode.STATIONARY, targetMode(TravelMode.DRIVING, 0.1, null))
        // Just got in, not moving yet.
        assertEquals(TravelMode.STATIONARY, targetMode(TravelMode.STATIONARY, 0.1, ActivityType.IN_VEHICLE))
        // A clearly contradicted walking prior falls back to speed.
        assertEquals(TravelMode.DRIVING, targetMode(TravelMode.WALKING, 20.0, ActivityType.WALKING))
        assertEquals(TravelMode.STATIONARY, targetMode(TravelMode.WALKING, 0.5, ActivityType.STILL))
        assertEquals(TravelMode.WALKING, targetMode(TravelMode.STATIONARY, 1.3, ActivityType.STILL))
        assertEquals(TravelMode.CYCLING, LocationProcessor.priorMode(ActivityType.ON_BICYCLE))
        assertEquals(TravelMode.WALKING, LocationProcessor.priorMode(ActivityType.RUNNING))
    }

    private class Walker(val p: LocationProcessor, var point: GeoPoint) {
        var t = 1_000_000L
        fun step(speed: Float): TravelMode {
            t += 5_000
            point = Geo.destination(point, 90.0, speed * 5.0)
            return p.accept(LocationSample(point.lat, point.lon, 8f, t, speed, 90f), t)!!.travelMode
        }
    }

    @Test
    fun agreeingPriorSwitchesModeFaster() {
        val withPrior = Walker(LocationProcessor(), start)
        val without = Walker(LocationProcessor(), start)
        repeat(4) { assertEquals(TravelMode.WALKING, withPrior.step(1.4f)); without.step(1.4f) }
        withPrior.p.setActivity(ActivityType.ON_BICYCLE, withPrior.t)
        withPrior.step(5f)
        without.step(5f)
        assertEquals(TravelMode.CYCLING, withPrior.step(5f))
        assertEquals(TravelMode.WALKING, without.step(5f))
        // Speed alone gets there too, just later (15 s hold).
        var mode = TravelMode.WALKING
        repeat(6) { mode = without.step(5f) }
        assertEquals(TravelMode.CYCLING, mode)
    }

    @Test
    fun stalePriorIsIgnored() {
        val fresh = LocationProcessor().apply { setActivity(ActivityType.IN_VEHICLE, 0) }
        assertEquals(TravelMode.DRIVING, fresh.accept(LocationSample(start.lat, start.lon, 8f, 60_000, 4.5f), 60_000)!!.travelMode)
        val stale = LocationProcessor().apply { setActivity(ActivityType.IN_VEHICLE, 0) }
        val later = 3 * 3600_000L
        assertEquals(TravelMode.CYCLING, stale.accept(LocationSample(start.lat, start.lon, 8f, later, 4.5f), later)!!.travelMode)
        // Clearing the prior returns to speed-only detection.
        val cleared = LocationProcessor().apply { setActivity(ActivityType.IN_VEHICLE, 0); setActivity(null, 0) }
        assertEquals(TravelMode.CYCLING, cleared.accept(LocationSample(start.lat, start.lon, 8f, 60_000, 4.5f), 60_000)!!.travelMode)
    }

    @Test
    fun cyclingHasItsOwnRadiusPacingAndLookAhead() {
        val policy = AreaRefreshPolicy()
        assertEquals(3_500, policy.searchRadiusM(TravelMode.CYCLING))
        assertTrue(policy.refreshDistanceM(TravelMode.CYCLING) in 500.0..3_000.0)
        val ctx = LocationContext(start, 5f, 0, 5.0, 90.0, TravelMode.CYCLING)
        val center = policy.searchCenter(ctx)
        assertTrue(Geo.distanceM(start, center) in 500.0..1_500.0)
        assertEquals(90.0, Geo.bearingDeg(start, center), 1.0)
        assertNull(policy.corridorCells(ctx))
        assertEquals(3, policy.corridorCells(ctx.copy(travelMode = TravelMode.DRIVING))!!.size)
        assertNull(policy.corridorCells(ctx.copy(travelMode = TravelMode.DRIVING, headingDeg = null)))
        assertEquals(start, policy.searchCenter(ctx.copy(travelMode = TravelMode.WALKING)))

        val ranker = EditorialRanker()
        assertEquals(1_200.0, ranker.proximityScaleM(TravelMode.CYCLING))
        assertEquals(60_000, ranker.minGapMs(TravelMode.CYCLING))
        assertTrue(RadioAgent.targetSeconds(TravelMode.CYCLING) in RadioAgent.targetSeconds(TravelMode.DRIVING)..RadioAgent.targetSeconds(TravelMode.WALKING))
        // Cycling is ahead-weighted: things off to the side count for less than when walking.
        val beside = place("beside", Geo.destination(start, 180.0, 600.0))
        fun dir(mode: TravelMode) = ranker.rank(
            listOf(beside),
            EditorialRanker.Context(ctx.copy(travelMode = mode), emptyMap(), com.gpsradio.core.editorial.HeardHistory(), 0),
        ).single().breakdown.direction
        assertTrue(dir(TravelMode.CYCLING) < dir(TravelMode.WALKING))
    }
}
