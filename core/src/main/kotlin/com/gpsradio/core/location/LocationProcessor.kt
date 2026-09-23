package com.gpsradio.core.location

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.ActivityType
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
        /** Enter cycling above this speed when there is no activity prior (~13 km/h, faster than most runners). */
        val cyclingEnterMps: Double = 3.5,
        /** Leave cycling below this speed (without a prior). */
        val cyclingExitMps: Double = 2.2,
        /** Without a prior, sustained speed above this while cycling means a vehicle (~32 km/h). */
        val cyclingToDrivingMps: Double = 9.0,
        /** With an ON_BICYCLE prior, speeds above this still mean a vehicle (~43 km/h). */
        val bicycleMaxMps: Double = 12.0,
        /** With an IN_VEHICLE prior, moving at least this fast counts as driving (slow traffic). */
        val vehicleMinMps: Double = 2.0,
        /** A new mode must persist this long before it is adopted. */
        val modeHoldMs: Long = 15_000,
        /** Shorter hold when the activity prior agrees with the new mode. */
        val priorAgreeHoldMs: Long = 5_000,
        /** An activity transition older than this no longer biases detection. */
        val priorMaxAgeMs: Long = 60 * 60_000L,
        val minHeadingSpeedMps: Double = 0.8,
    )

    /** Manual mode selection; overrides detection when set. */
    var modeOverride: TravelMode? = null

    private var last: LocationContext? = null
    private var smoothedSpeed = 0.0
    private var detectedMode = TravelMode.UNKNOWN
    private var pendingMode: TravelMode? = null
    private var pendingSinceMs = 0L
    private var activity: ActivityType? = null
    private var activityAtMs = 0L
    private val maneuvers = ManeuverDetector()

    /**
     * Latest activity-recognition transition (e.g. entering IN_VEHICLE). It biases mode detection
     * in the ambiguous 3–7 m/s band and shortens the hold when it agrees with the speed; speeds
     * that clearly contradict it (a "walking" phone at 20 m/s) fall back to speed-only rules.
     */
    fun setActivity(type: ActivityType?, atMs: Long) {
        activity = type
        activityAtMs = atMs
    }

    private fun prior(tsMs: Long): ActivityType? = activity?.takeIf { tsMs - activityAtMs <= config.priorMaxAgeMs }

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

        updateMode(sample.timestampMs, prior(sample.timestampMs))
        maneuvers.add(sample.timestampMs, rawSpeed, heading)

        val ctx = LocationContext(
            point = point,
            accuracyM = sample.accuracyM,
            timestampMs = sample.timestampMs,
            speedMps = smoothedSpeed,
            headingDeg = heading,
            travelMode = detectedMode,
            maneuvering = maneuvers.isManeuvering(sample.timestampMs),
        )
        last = ctx
        return ctx.copy(travelMode = modeOverride ?: detectedMode)
    }

    private fun updateMode(tsMs: Long, prior: ActivityType?) {
        val target = targetMode(detectedMode, smoothedSpeed, prior, config)
        if (detectedMode == TravelMode.UNKNOWN) {
            detectedMode = target
            pendingMode = null
            return
        }
        if (target == detectedMode) {
            pendingMode = null
            return
        }
        val hold = if (prior != null && priorMode(prior) == target) config.priorAgreeHoldMs else config.modeHoldMs
        if (pendingMode != target) {
            pendingMode = target
            pendingSinceMs = tsMs
        }
        if (tsMs - pendingSinceMs >= hold) {
            detectedMode = target
            pendingMode = null
        }
    }

    companion object {
        /** The mode an activity type suggests. */
        fun priorMode(type: ActivityType): TravelMode = when (type) {
            ActivityType.IN_VEHICLE -> TravelMode.DRIVING
            ActivityType.ON_BICYCLE -> TravelMode.CYCLING
            ActivityType.WALKING, ActivityType.RUNNING -> TravelMode.WALKING
            ActivityType.STILL -> TravelMode.STATIONARY
        }

        /**
         * Pure mode decision for one step: the mode the smoothed [speed] points to from [current],
         * biased by the activity [prior] when present. Hysteresis thresholds differ per current mode.
         * Without a prior the 3.5–6.5 m/s band reads as cycling from rest or walking; with one, the
         * ambiguous band follows the prior (a car in slow traffic, a runner, a slow cyclist).
         */
        fun targetMode(current: TravelMode, speed: Double, prior: ActivityType?, config: Config = Config()): TravelMode {
            val s = speed
            val c = config
            return when (prior) {
                ActivityType.IN_VEHICLE -> when {
                    // Stopped at lights or crawling in traffic is still driving.
                    s >= c.vehicleMinMps || current == TravelMode.DRIVING -> TravelMode.DRIVING
                    s < c.stationaryEnterMps -> TravelMode.STATIONARY
                    else -> speedOnly(current, s, c)
                }
                ActivityType.ON_BICYCLE -> when {
                    s >= c.bicycleMaxMps -> TravelMode.DRIVING
                    s < c.stationaryEnterMps -> TravelMode.STATIONARY
                    else -> TravelMode.CYCLING
                }
                ActivityType.WALKING, ActivityType.RUNNING -> when {
                    // Clearly contradicted (a stale prior): trust the speed.
                    s >= c.drivingEnterMps -> speedOnly(current, s, c)
                    s < c.stationaryEnterMps -> TravelMode.STATIONARY
                    else -> TravelMode.WALKING
                }
                ActivityType.STILL -> if (s < c.walkingEnterMps) TravelMode.STATIONARY else speedOnly(current, s, c)
                null -> speedOnly(current, s, c)
            }
        }

        private fun speedOnly(current: TravelMode, s: Double, c: Config): TravelMode = when (current) {
            TravelMode.DRIVING -> when {
                s >= c.drivingExitMps -> TravelMode.DRIVING
                s >= c.walkingEnterMps -> TravelMode.WALKING
                else -> TravelMode.STATIONARY
            }
            TravelMode.CYCLING -> when {
                s >= c.cyclingToDrivingMps -> TravelMode.DRIVING
                s >= c.cyclingExitMps -> TravelMode.CYCLING
                s >= c.walkingEnterMps -> TravelMode.WALKING
                else -> TravelMode.STATIONARY
            }
            TravelMode.WALKING -> when {
                s >= c.drivingEnterMps -> TravelMode.DRIVING
                s >= c.cyclingEnterMps -> TravelMode.CYCLING
                s < c.stationaryEnterMps -> TravelMode.STATIONARY
                else -> TravelMode.WALKING
            }
            TravelMode.STATIONARY, TravelMode.UNKNOWN -> when {
                s >= c.drivingEnterMps -> TravelMode.DRIVING
                s >= c.cyclingEnterMps -> TravelMode.CYCLING
                s >= c.walkingEnterMps -> TravelMode.WALKING
                else -> TravelMode.STATIONARY
            }
        }
    }
}

/**
 * Junction/roundabout heuristic for driving pacing (spec B §24): recent raw speed and heading
 * samples show a sharp change (hard braking or accelerating, or a tight turn at speed).
 * Stories are held while this is true so the host stays quiet at complex junctions.
 */
class ManeuverDetector(
    private val windowMs: Long = 20_000,
    /** Speed range within the window that counts as a sharp change (~15 km/h). */
    private val speedRangeMps: Double = 4.0,
    /** ...and the slowest sample must be well below the fastest one. */
    private val minRatio: Double = 0.65,
    /** Heading change within the window that counts as a turn, while moving above [turnMinSpeedMps]. */
    private val turnDeg: Double = 45.0,
    private val turnMinSpeedMps: Double = 2.0,
) {
    data class Sample(val tMs: Long, val speedMps: Double, val headingDeg: Double?)

    private val samples = ArrayDeque<Sample>()

    fun add(tMs: Long, speedMps: Double, headingDeg: Double?) {
        samples.addLast(Sample(tMs, speedMps, headingDeg))
        while (samples.isNotEmpty() && tMs - samples.first().tMs > windowMs) samples.removeFirst()
    }

    fun isManeuvering(nowMs: Long): Boolean =
        isManeuvering(samples.filter { nowMs - it.tMs <= windowMs }, speedRangeMps, minRatio, turnDeg, turnMinSpeedMps)

    companion object {
        fun isManeuvering(
            window: List<Sample>,
            speedRangeMps: Double = 4.0,
            minRatio: Double = 0.65,
            turnDeg: Double = 45.0,
            turnMinSpeedMps: Double = 2.0,
        ): Boolean {
            if (window.size < 2) return false
            val max = window.maxOf { it.speedMps }
            val min = window.minOf { it.speedMps }
            if (max - min >= speedRangeMps && min < max * minRatio) return true
            // Largest heading change between any two moving samples in the window.
            val headings = window.filter { it.speedMps >= turnMinSpeedMps }.mapNotNull { it.headingDeg }
            for (i in headings.indices) for (j in i + 1 until headings.size) {
                if (Geo.angleDiff(headings[i], headings[j]) >= turnDeg) return true
            }
            return false
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
        TravelMode.CYCLING -> 1_200.0
        TravelMode.DRIVING -> 3_000.0
    }

    fun searchRadiusM(mode: TravelMode): Int = when (mode) {
        TravelMode.STATIONARY, TravelMode.WALKING, TravelMode.UNKNOWN -> 1_500
        TravelMode.CYCLING -> 3_500
        TravelMode.DRIVING -> 8_000
    }

    /**
     * Where to centre a single-circle search: shifted ahead along the heading when driving (used when
     * the corridor is unavailable) and slightly ahead when cycling (ahead-weighted).
     */
    fun searchCenter(ctx: LocationContext): GeoPoint {
        val heading = ctx.headingDeg ?: return ctx.point
        return when (ctx.travelMode) {
            TravelMode.DRIVING -> Geo.destination(ctx.point, heading, searchRadiusM(ctx.travelMode) * 0.5)
            TravelMode.CYCLING -> Geo.destination(ctx.point, heading, searchRadiusM(ctx.travelMode) * 0.3)
            TravelMode.STATIONARY, TravelMode.WALKING, TravelMode.UNKNOWN -> ctx.point
        }
    }

    /** Driving look-ahead corridor cells along the heading, or null when a single circle should be used. */
    fun corridorCells(ctx: LocationContext): List<CorridorCell>? {
        val heading = ctx.headingDeg ?: return null
        return if (ctx.travelMode == TravelMode.DRIVING) Corridor.cells(ctx.point, heading) else null
    }

    fun shouldRefresh(ctx: LocationContext, lastCenter: GeoPoint?, lastMode: TravelMode?, lastRefreshMs: Long?): Boolean {
        if (lastCenter == null || lastMode == null || lastRefreshMs == null) return true
        if (lastMode != ctx.travelMode && ctx.travelMode != TravelMode.UNKNOWN) return true
        if (ctx.timestampMs - lastRefreshMs > maxAgeMs) return true
        return Geo.distanceM(ctx.point, lastCenter) >= refreshDistanceM(ctx.travelMode)
    }
}
