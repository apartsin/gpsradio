package com.gpsradio.core.geo

import com.gpsradio.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from north in [0, 360). */
    fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDeg(Math.toDegrees(atan2(y, x)))
    }

    /** Point reached by travelling [distanceM] from [from] along [bearingDeg]. */
    fun destination(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / EARTH_RADIUS_M
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540) % 360) - 180)
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360) + 360) % 360

    /** Smallest absolute angle between two bearings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180) 360 - d else d
    }

    fun compass(deg: Double): String {
        val names = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        return names[((normalizeDeg(deg) + 22.5) / 45).toInt() % 8]
    }

    /** Direction relative to the user's heading, e.g. "ahead", "to your left". */
    fun relativeDirection(bearingToTarget: Double, headingDeg: Double): String {
        val rel = normalizeDeg(bearingToTarget - headingDeg)
        return when {
            rel < 30 || rel > 330 -> "ahead"
            rel < 150 -> "to your right"
            rel <= 210 -> "behind you"
            else -> "to your left"
        }
    }

    /** Reduce coordinate precision before sending it off-device (4 decimals ≈ 11 m). */
    fun quantize(p: GeoPoint, decimals: Int = 4): GeoPoint {
        val f = Math.pow(10.0, decimals.toDouble())
        return GeoPoint(round(p.lat * f) / f, round(p.lon * f) / f)
    }
}
