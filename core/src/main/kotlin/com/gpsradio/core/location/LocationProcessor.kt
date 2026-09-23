package com.gpsradio.core.location

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.TravelMode

/**
 * Normalizes raw fixes (spec B §6): rejects stale/inaccurate samples, smooths speed and heading,
 * and derives travel mode with hysteresis so the mode does not oscillate.
 */
class LocationProcessor(private val config: Config = Config()) {

    data class Config(
        /** Coarse indoor/balanced-power fixes are still good enough for area discovery. */
        val maxAccuracyM: Float = 300f,
        val maxAgeMs: Long = 60_000,
        val speedSmoothing: Double = 0.35,
        /** Enter walking above this speed. */
        val walkingEnterMps: Double = 0.8,
        /** Drop back to stationary below this speed. */
        val stationaryEnterMps: Double = 0.35,
        /** Enter driving above this speed (~23 km/h). */
        val drivingEnterMps: Double = 6.5,
        /** Leave driving below this speed (~11 km/h). */
        val drivingExitMps: Double = 3.0,
        /** A new mode must persist this long before it is adopted. */
        val modeHoldMs: Long = 15_000,
        val minHeadingSpeedMps: Double = 0.8,
    )

    /** Manual mode selection; overrides detection when set. */
    var modeOverride: TravelMode? = null

    private var last: LocationContext? = null
    private var smoothedSpeed = 0.0
    private var detectedMode = TravelMode.UNKNOWN
    private var pendingMode: TravelMode? = null
    private var pendingSinceMs = 0L

    val current: LocationContext? get() = last?.let { it.copy(travelMode = modeOverride ?: it.travelMode) }

    /** Returns the new context, or null when the sample is rejected. */
    fun accept(sample: LocationSample, nowMs: Long): LocationContext? {
        if (sample.accuracyM > config.maxAccuracyM) return null
        if (nowMs - sample.timestampMs > config.maxAgeMs) return null
        val prev = last
        if (prev != null && sample.timestampMs <= prev.timestampMs) return null

        val point = GeoPoint(sample.lat, sample.lon)
        val moved = prev?.let { Geo.distanceM(it.point, point) } ?: 0.0
        val dtSec = prev?.let { (sample.timestampMs - it.timestampMs) / 1000.0 } ?: 0.0

        val rawSpeed = sample.speedMps?.toDouble()
            ?: if (dtSec > 0) moved / dtSec else 0.0
        smoothedSpeed = if (prev == null) rawSpeed
        else smoothedSpeed + config.speedSmoothing * (rawSpeed - smoothedSpeed)

        val heading: Double? = when {
            smoothedSpeed < config.minHeadingSpeedMps -> if (detectedMode == TravelMode.STATIONARY) null else prev?.headingDeg
            sample.bearingDeg != null -> sample.bearingDeg.toDouble()
            prev != null && moved > maxOf(15.0, sample.accuracyM.toDouble()) -> Geo.bearingDeg(prev.point, point)
            else -> prev?.headingDeg
        }

        updateMode(sample.timestampMs)

        val ctx = LocationContext(
            point = point,
            accuracyM = sample.accuracyM,
            timestampMs = sample.timestampMs,
            speedMps = smoothedSpeed,
            headingDeg = heading,
            travelMode = detectedMode,
        )
        last = ctx
        return ctx.copy(travelMode = modeOverride ?: detectedMode)
    }

    private fun updateMode(tsMs: Long) {
        val s = smoothedSpeed
        val target = when (detectedMode) {
            TravelMode.DRIVING -> when {
                s >= config.drivingExitMps -> TravelMode.DRIVING
                s >= config.walkingEnterMps -> TravelMode.WALKING
                else -> TravelMode.STATIONARY
            }
            TravelMode.WALKING -> when {
                s >= config.drivingEnterMps -> TravelMode.DRIVING
                s < config.stationaryEnterMps -> TravelMode.STATIONARY
                else -> TravelMode.WALKING
            }
            TravelMode.STATIONARY, TravelMode.UNKNOWN -> when {
                s >= config.drivingEnterMps -> TravelMode.DRIVING
                s >= config.walkingEnterMps -> TravelMode.WALKING
                else -> TravelMode.STATIONARY
            }
        }
        if (detectedMode == TravelMode.UNKNOWN) {
            detectedMode = target
            pendingMode = null
            return
        }
        if (target == detectedMode) {
            pendingMode = null
            return
        }
        if (pendingMode != target) {
            pendingMode = target
            pendingSinceMs = tsMs
        } else if (tsMs - pendingSinceMs >= config.modeHoldMs) {
            detectedMode = target
            pendingMode = null
        }
    }
}

/** Decides when the surroundings changed enough to re-run area discovery (spec B §6). */
class AreaRefreshPolicy(
    private val maxAgeMs: Long = 15 * 60_000L,
) {
    fun refreshDistanceM(mode: TravelMode): Double = when (mode) {
        TravelMode.STATIONARY -> 400.0
        TravelMode.WALKING, TravelMode.UNKNOWN -> 500.0
        TravelMode.DRIVING -> 3_000.0
    }

    fun searchRadiusM(mode: TravelMode): Int = when (mode) {
        TravelMode.STATIONARY, TravelMode.WALKING, TravelMode.UNKNOWN -> 1_500
        TravelMode.DRIVING -> 8_000
    }

    /** Where to centre the search: shifted ahead along the heading when driving (look-ahead). */
    fun searchCenter(ctx: LocationContext): GeoPoint {
        val heading = ctx.headingDeg
        return if (ctx.travelMode == TravelMode.DRIVING && heading != null) {
            Geo.destination(ctx.point, heading, searchRadiusM(ctx.travelMode) * 0.5)
        } else ctx.point
    }

    fun shouldRefresh(ctx: LocationContext, lastCenter: GeoPoint?, lastMode: TravelMode?, lastRefreshMs: Long?): Boolean {
        if (lastCenter == null || lastMode == null || lastRefreshMs == null) return true
        if (lastMode != ctx.travelMode && ctx.travelMode != TravelMode.UNKNOWN) return true
        if (ctx.timestampMs - lastRefreshMs > maxAgeMs) return true
        return Geo.distanceM(ctx.point, lastCenter) >= refreshDistanceM(ctx.travelMode)
    }
}
