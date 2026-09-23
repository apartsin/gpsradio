package com.gpsradio.core.discovery

import com.gpsradio.core.location.CorridorCache
import com.gpsradio.core.location.CorridorCell
import com.gpsradio.core.model.PlaceCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Driving look-ahead (spec B §24): discovers the corridor cells ahead of the listener that are not
 * cached yet, in parallel, and merges them with the cached ones. Because the far cell is fetched
 * about 10 km ahead, a cell is normally cached long before the car reaches it.
 */
class CorridorDiscovery(
    private val places: PlacesProvider,
    val cache: CorridorCache = CorridorCache(),
) {
    /** Throws only when every missing cell failed and nothing is cached for the corridor. */
    suspend fun discover(cells: List<CorridorCell>, languageBase: String, nowMs: Long): List<PlaceCandidate> {
        val missing = cache.missing(cells, languageBase, nowMs)
        val results = coroutineScope {
            missing.map { cell -> async { cell to runCatching { places.discover(cell.center, cell.radiusM, languageBase) } } }.awaitAll()
        }
        results.forEach { (cell, r) -> r.getOrNull()?.let { cache.put(cell, languageBase, it, nowMs) } }
        val merged = cache.places(cells, languageBase, nowMs)
        val failures = results.mapNotNull { it.second.exceptionOrNull() }
        if (failures.isNotEmpty() && failures.size == results.size && merged.isEmpty()) throw failures.first()
        return merged
    }
}
