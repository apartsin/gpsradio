package com.gpsradio.core.editorial

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.location.Corridor
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.RoadTripKind
import com.gpsradio.core.model.TravelMode
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Photo tips (spec A §28): nearby or upcoming scenery worth a photo. Photogenic places come from OSM
 * tags and descriptions (viewpoints, peaks, waterfalls, lakes, castles, bridges, lighthouses…). The
 * light hint comes from the sun's position, computed on the device.
 */
object PhotoSpots {
    private val photogenic = Regex(
        listOf(
            "tourism: ?viewpoint", "natural: ?(peak|volcano|water|waterfall|cliff|rock|glacier|beach|bay|cave_entrance)",
            "man_made: ?(lighthouse|bridge|windmill|tower)", "historic: ?(castle|fort|ruins|city_gate|monastery)",
            "\\bviewpoints?\\b", "\\blookout\\b", "\\bpanoram", "\\bwaterfalls?\\b", "\\blakes?\\b", "\\bgorge\\b",
            "\\bcastles?\\b", "\\bschloss\\b", "\\bburg\\b", "\\bpalace\\b", "\\bruins?\\b", "\\bbridges?\\b", "\\bviaduct\\b",
            "\\blighthouse\\b", "\\bwindmill\\b", "\\bcathedral\\b", "\\bbasilica\\b", "\\bpeak\\b", "\\bsummit\\b", "\\bcliffs?\\b",
        ).joinToString("|"),
    )
    private val viewpoint = Regex("tourism: ?viewpoint|\\bviewpoints?\\b|\\blookout\\b|\\bpanoram")

    private fun haystack(p: PlaceCandidate) = listOfNotNull(p.category, p.description, p.name).joinToString(" | ").lowercase()

    fun isPhotogenic(p: PlaceCandidate): Boolean = photogenic.containsMatchIn(haystack(p))

    fun isViewpoint(p: PlaceCandidate): Boolean = viewpoint.containsMatchIn(haystack(p))

    /** Walking/cycling: within this distance; stationary listeners get a little more. */
    const val WALK_MAX_M = 600.0
    /** Driving: only designated viewpoints (safe to pull over) this close to the road, ahead. */
    const val DRIVE_MAX_OFF_ROUTE_M = 1_500.0
    const val DRIVE_MAX_AHEAD_M = 8_000.0

    /**
     * Whether [r] is a good photo suggestion right now. Driving suggests only viewpoints just off the
     * road ahead, so the tip is "pull over at…", never "take a photo now".
     */
    fun suitable(r: RankedCandidate, loc: LocationContext): Boolean {
        val p = r.place
        if (!isPhotogenic(p)) return false
        return when (loc.travelMode) {
            TravelMode.DRIVING -> {
                val heading = loc.headingDeg ?: return false
                val along = Corridor.alongTrackM(loc.point, heading, p.point)
                isViewpoint(p) && along in 300.0..DRIVE_MAX_AHEAD_M &&
                    Corridor.distanceToRouteM(loc.point, heading, p.point) <= DRIVE_MAX_OFF_ROUTE_M
            }
            TravelMode.STATIONARY -> r.distanceM <= WALK_MAX_M * 1.5
            else -> r.distanceM <= WALK_MAX_M
        }
    }

    /** Sun azimuth (degrees from north) and elevation (degrees) — NOAA-style approximation, ±1°. */
    data class Sun(val azimuthDeg: Double, val elevationDeg: Double)

    fun sun(point: GeoPoint, epochMs: Long): Sun {
        val rad = PI / 180
        val days = epochMs / 86_400_000.0 - 10_957.5 // days since J2000.0
        val meanLon = (280.460 + 0.9856474 * days).mod(360.0)
        val meanAnom = ((357.528 + 0.9856003 * days).mod(360.0)) * rad
        val eclLon = (meanLon + 1.915 * sin(meanAnom) + 0.020 * sin(2 * meanAnom)) * rad
        val obliq = (23.439 - 0.0000004 * days) * rad
        val ra = atan2(cos(obliq) * sin(eclLon), cos(eclLon))
        val dec = asin(sin(obliq) * sin(eclLon))
        val gmst = (18.697374558 + 24.06570982441908 * days).mod(24.0)
        val lst = (gmst * 15 + point.lon) * rad
        val ha = lst - ra
        val lat = point.lat * rad
        val elev = asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha))
        val az = atan2(-sin(ha), cos(lat) * sin(dec) / cos(dec) - sin(lat) * cos(ha))
        return Sun(((az / rad) + 360).mod(360.0), elev / rad)
    }

    /**
     * Plain-language light for photographing a subject that lies in [subjectBearingDeg] from the
     * listener, e.g. "golden hour, the sun behind you: warm, even light on the subject".
     */
    fun lightHint(sun: Sun, subjectBearingDeg: Double?): String {
        val e = sun.elevationDeg
        val phase = when {
            e < -6 -> return "after dark: only lit landmarks work; hold the phone steady or brace it"
            e < 0 -> "blue hour"
            e < 10 -> "golden hour"
            e > 50 -> "harsh midday sun"
            else -> "daylight"
        }
        val b = subjectBearingDeg ?: return phase
        val off = Geo.angleDiff(sun.azimuthDeg, b)
        val side = when {
            off <= 45 -> "the sun is behind the subject (backlight: silhouettes, or step aside)"
            off >= 135 -> "the sun is behind you (front light: bright colours)"
            else -> "side light (good texture and depth)"
        }
        return "$phase, $side"
    }
}

/**
 * Drive-by detours (spec A §28): "worth a stop" places a few minutes off the road ahead, offered with
 * a navigation handoff.
 */
object Detours {
    /** Average speed on the small roads of a detour. */
    private const val DETOUR_SPEED_MPS = 13.9 // 50 km/h
    const val MAX_MINUTES = 15

    /** Minutes of extra driving (there and back) to visit [p] from the current road; null without a heading. */
    fun minutes(loc: LocationContext, p: GeoPoint): Int? {
        val heading = loc.headingDeg ?: return null
        val m = Corridor.detourM(loc.point, heading, p)
        return (m / DETOUR_SPEED_MPS / 60).roundToInt().coerceAtLeast(1)
    }

    /** Spoken/visual label, e.g. "right by the road" or "about 6 min detour". */
    fun label(minutes: Int): String = if (minutes <= 1) "right by the road" else "about $minutes min detour"

    /** Detours ahead, best first: worth-a-stop places within [MAX_MINUTES], unheard. */
    fun ahead(ranked: List<RankedCandidate>, loc: LocationContext?, limit: Int = 3): List<Pair<RankedCandidate, Int>> {
        if (loc == null || loc.travelMode != TravelMode.DRIVING) return emptyList()
        return ranked.asSequence()
            .filter { it.roadTrip == RoadTripKind.WORTH_A_STOP && it.breakdown.novelty > 0.0 }
            .mapNotNull { r -> minutes(loc, r.place.point)?.takeIf { it <= MAX_MINUTES }?.let { r to it } }
            .take(limit)
            .toList()
    }
}
