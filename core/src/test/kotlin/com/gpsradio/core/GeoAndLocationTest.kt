package com.gpsradio.core

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.location.AreaRefreshPolicy
import com.gpsradio.core.location.LocationProcessor
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoAndLocationTest {
    private val start = GeoPoint(47.61, 13.78)

    @Test
    fun distanceAndBearing() {
        val north = Geo.destination(start, 0.0, 1000.0)
        assertEquals(1000.0, Geo.distanceM(start, north), 1.0)
        assertEquals(0.0, Geo.angleDiff(Geo.bearingDeg(start, north), 0.0), 0.5)
        val east = Geo.destination(start, 90.0, 500.0)
        assertEquals(90.0, Geo.bearingDeg(start, east), 0.5)
        assertEquals("east", Geo.compass(Geo.bearingDeg(start, east)))
        assertEquals("to your left", Geo.relativeDirection(270.0, 0.0))
        assertEquals("ahead", Geo.relativeDirection(10.0, 350.0))
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
    }

    @Test
    fun rejectsInaccurateAndStaleSamples() {
        val p = LocationProcessor()
        assertNull(p.accept(LocationSample(start.lat, start.lon, 900f, 1_000), 1_000))
        assertNull(p.accept(LocationSample(start.lat, start.lon, 10f, 1_000), 1_000 + 120_000))
        assertTrue(p.accept(LocationSample(start.lat, start.lon, 10f, 1_000), 1_000) != null)
    }

    @Test
    fun travelModeHasHysteresis() {
        val p = LocationProcessor()
        var t = 0L
        var point = start
        fun step(speed: Float): TravelMode {
            t += 5_000
            point = Geo.destination(point, 90.0, speed * 5.0)
            return p.accept(LocationSample(point.lat, point.lon, 8f, t, speed, 90f), t)!!.travelMode
        }
        assertEquals(TravelMode.WALKING, step(1.4f))
        repeat(3) { assertEquals(TravelMode.WALKING, step(1.4f)) }
        // A short burst of speed does not flip the mode...
        assertEquals(TravelMode.WALKING, step(15f))
        assertEquals(TravelMode.WALKING, step(15f))
        // ...but sustained speed does.
        var mode = TravelMode.WALKING
        repeat(6) { mode = step(15f) }
        assertEquals(TravelMode.DRIVING, mode)
        p.modeOverride = TravelMode.STATIONARY
        assertEquals(TravelMode.STATIONARY, step(15f))
    }

    @Test
    fun refreshOnMeaningfulDisplacementOnly() {
        val policy = AreaRefreshPolicy()
        val p = LocationProcessor()
        val ctx = p.accept(LocationSample(start.lat, start.lon, 5f, 0, 1.2f), 0)!!
        assertTrue(policy.shouldRefresh(ctx, null, null, null))
        val near = ctx.copy(point = Geo.destination(start, 0.0, 100.0))
        assertFalse(policy.shouldRefresh(near, start, near.travelMode, 0))
        val far = ctx.copy(point = Geo.destination(start, 0.0, 900.0))
        assertTrue(policy.shouldRefresh(far, start, far.travelMode, 0))
    }
}
