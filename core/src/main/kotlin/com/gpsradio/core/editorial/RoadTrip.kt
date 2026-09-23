package com.gpsradio.core.editorial

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.location.Corridor
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RoadTripKind
import com.gpsradio.core.model.TravelMode

/**
 * Road-trip categories while driving (spec A §16 PR-27, spec B §24):
 * - [RoadTripKind.VISIBLE]: landmarks you can see from the road (peaks, volcanoes, lakes,
 *   viewpoints, castles, towers, bridges, lighthouses), ahead or beside the car within sight range;
 * - [RoadTripKind.WORTH_A_STOP]: high-relevance sights ahead within a short detour of the route
 *   line, so the story can end with an offer to navigate there.
 */
object RoadTrip {
    /** Maximum distance at which a landmark is assumed visible from the road. */
    const val VISIBLE_MAX_M = 12_000.0
    /** Landmarks further off the heading than this are behind or out of view (unless very close). */
    const val VISIBLE_MAX_ANGLE_DEG = 100.0
    /** "Worth a stop": within about this distance of the route line (≈ 5 min detour each way). */
    const val STOP_MAX_OFF_ROUTE_M = 5_000.0
    const val STOP_MIN_RELEVANCE = 0.65

    /** Kinds of things you can see from a car window, derived from OSM tags or source descriptions. */
    private val landmarkPattern = Regex(
        listOf(
            "natural: ?(peak|volcano|water|glacier|cliff|waterfall)", "tourism: ?viewpoint",
            "man_made: ?(lighthouse|tower|windmill|bridge)", "historic: ?(castle|fort|tower|city_gate|ruins)",
            "\\bmountains?\\b", "\\bpeak\\b", "\\bsummit\\b", "\\bvolcano", "\\blake\\b", "\\breservoir\\b", "\\bglacier\\b",
            "\\bwaterfall\\b", "\\bviewpoint\\b", "\\bcastles?\\b", "\\bfortress\\b", "\\bcitadel\\b", "\\btowers?\\b",
            "\\bbridges?\\b", "\\bviaduct\\b", "\\blighthouse\\b", "\\bwindmill\\b", "\\bburg\\b", "\\bschloss\\b",
        ).joinToString("|"),
    )

    /** Natural sights you can see but not visit on a short stop. */
    private val notAStopPattern = Regex("natural: ?(peak|volcano|glacier)|\\bmountains?\\b|\\bpeak\\b|\\bsummit\\b|\\bvolcano|\\bglacier\\b")

    private fun haystack(p: PlaceCandidate) = listOfNotNull(p.category, p.description, p.name).joinToString(" | ").lowercase()

    /** Whether the place is the kind of landmark visible from a road (by category, description or name). */
    fun isLandmark(p: PlaceCandidate): Boolean = landmarkPattern.containsMatchIn(haystack(p))

    fun classify(p: PlaceCandidate, loc: LocationContext): RoadTripKind? {
        if (loc.travelMode != TravelMode.DRIVING) return null
        val heading = loc.headingDeg ?: return null
        val d = Geo.distanceM(loc.point, p.point)
        val along = Corridor.alongTrackM(loc.point, heading, p.point)
        val offRoute = Corridor.distanceToRouteM(loc.point, heading, p.point)
        val hay = haystack(p)
        if (p.baseRelevance >= STOP_MIN_RELEVANCE && along > 0 && offRoute <= STOP_MAX_OFF_ROUTE_M && !notAStopPattern.containsMatchIn(hay)) {
            return RoadTripKind.WORTH_A_STOP
        }
        if (landmarkPattern.containsMatchIn(hay) && d <= VISIBLE_MAX_M) {
            val angle = Geo.angleDiff(Geo.bearingDeg(loc.point, p.point), heading)
            if (angle <= VISIBLE_MAX_ANGLE_DEG || d < 1_500) return RoadTripKind.VISIBLE
        }
        return null
    }

    /** Ranking bonus 0..1 for a road-trip kind. */
    fun bonus(kind: RoadTripKind?): Double = when (kind) {
        RoadTripKind.VISIBLE -> 1.0
        RoadTripKind.WORTH_A_STOP -> 0.8
        null -> 0.0
    }
}
