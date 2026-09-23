package com.gpsradio.core.location

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceCandidate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One discovery cell of the driving look-ahead corridor. */
data class CorridorCell(
    val center: GeoPoint,
    val radiusM: Int,
    /** How far ahead of the listener the cell centre lies along the heading. */
    val aheadM: Double,
)

/**
 * Pure geometry for the driving look-ahead corridor (spec B §24): instead of one circle shifted
 * ahead, discovery runs over a few overlapping cells along the heading, and places are related
 * to the route line (the heading ray) by along-track and cross-track distance.
 */
object Corridor {
    /** Cell centres ahead of the listener along the heading. */
    val DEFAULT_OFFSETS_M: List<Double> = listOf(2_000.0, 6_000.0, 10_000.0)

    /** Overlapping cells: 4 km radius around centres 4 km apart leave no gaps along the line. */
    const val DEFAULT_CELL_RADIUS_M: Int = 4_000

    /** Length of the assumed route line used for "near the route" decisions. */
    const val ROUTE_LENGTH_M: Double = 15_000.0

    fun cells(
        origin: GeoPoint,
        headingDeg: Double,
        offsetsM: List<Double> = DEFAULT_OFFSETS_M,
        radiusM: Int = DEFAULT_CELL_RADIUS_M,
    ): List<CorridorCell> = offsetsM.map { ahead -> CorridorCell(Geo.destination(origin, headingDeg, ahead), radiusM, ahead) }

    /** Local east/north offset of [p] from [origin] in metres (equirectangular; fine for tens of km). */
    fun localXY(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
        val mPerDegLat = 111_320.0
        val x = (p.lon - origin.lon).let { d -> ((d + 540) % 360) - 180 } * mPerDegLat * cos(Math.toRadians((origin.lat + p.lat) / 2))
        val y = (p.lat - origin.lat) * mPerDegLat
        return x to y
    }

    /** Signed distance of [p] ahead of [origin] along [headingDeg] (negative = behind). */
    fun alongTrackM(origin: GeoPoint, headingDeg: Double, p: GeoPoint): Double {
        val (x, y) = localXY(origin, p)
        val h = Math.toRadians(headingDeg)
        return x * sin(h) + y * cos(h)
    }

    /** Signed perpendicular distance of [p] from the heading line (positive = to the right). */
    fun crossTrackM(origin: GeoPoint, headingDeg: Double, p: GeoPoint): Double {
        val (x, y) = localXY(origin, p)
        val h = Math.toRadians(headingDeg)
        return x * cos(h) - y * sin(h)
    }

    /**
     * Distance from [p] to the route segment that starts at [origin] and runs [lengthM] along
     * [headingDeg]. Points behind the listener measure to the origin, points beyond the end to the end.
     */
    fun distanceToRouteM(origin: GeoPoint, headingDeg: Double, p: GeoPoint, lengthM: Double = ROUTE_LENGTH_M): Double {
        val along = alongTrackM(origin, headingDeg, p)
        val cross = crossTrackM(origin, headingDeg, p)
        return when {
            along < 0 -> hypot(along, cross)
            along > lengthM -> hypot(along - lengthM, cross)
            else -> abs(cross)
        }
    }

    /** Rough extra driving distance to visit [p] and come back to the route: there and back again. */
    fun detourM(origin: GeoPoint, headingDeg: Double, p: GeoPoint): Double =
        2 * distanceToRouteM(origin, headingDeg, p, lengthM = Double.MAX_VALUE)
}

/**
 * Places discovered per corridor cell, kept so cells are fetched once, ahead of arrival. A wanted
 * cell is served from the cache when an earlier, still-fresh cell in the same language lies close
 * enough to cover it; only uncovered cells are fetched.
 */
class CorridorCache(
    private val maxAgeMs: Long = 30 * 60_000L,
    private val maxCells: Int = 16,
    /** A cached cell covers a wanted one when their centres are at most this fraction of the radius apart. */
    private val coverFraction: Double = 0.5,
) {
    private data class Entry(val cell: CorridorCell, val language: String, val atMs: Long, val places: List<PlaceCandidate>)

    private val entries = ArrayList<Entry>()

    val size: Int get() = entries.size

    private fun covering(cell: CorridorCell, language: String, nowMs: Long): Entry? = entries
        .filter { it.language == language && nowMs - it.atMs < maxAgeMs && it.cell.radiusM >= cell.radiusM * 0.75 }
        .minByOrNull { Geo.distanceM(it.cell.center, cell.center) }
        ?.takeIf { Geo.distanceM(it.cell.center, cell.center) <= cell.radiusM * coverFraction }

    /** Cells that still need fetching. */
    fun missing(cells: List<CorridorCell>, language: String, nowMs: Long): List<CorridorCell> =
        cells.filter { covering(it, language, nowMs) == null }

    fun put(cell: CorridorCell, language: String, places: List<PlaceCandidate>, nowMs: Long) {
        entries.removeAll { nowMs - it.atMs >= maxAgeMs }
        entries += Entry(cell, language, nowMs, places)
        while (entries.size > maxCells) entries.removeAt(0)
    }

    /** All cached places for the wanted cells, deduplicated by id (nearest cell first). */
    fun places(cells: List<CorridorCell>, language: String, nowMs: Long): List<PlaceCandidate> {
        val out = LinkedHashMap<String, PlaceCandidate>()
        cells.mapNotNull { covering(it, language, nowMs) }.distinct().forEach { e -> e.places.forEach { out.putIfAbsent(it.id, it) } }
        return out.values.toList()
    }

    fun clear() = entries.clear()
}
