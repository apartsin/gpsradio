package com.gpsradio.core.discovery

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persists the serialized area cache between app runs (spec B §15), so visited areas work offline. */
interface AreaCacheStore {
    fun load(): String?
    fun save(serialized: String)
}

/**
 * On-disk cache of discovered areas, bounded by age ([ttlMs]), count ([maxAreas]) and serialized
 * size ([maxBytes]). Extracts are trimmed to what narration uses. Not thread-safe on its own;
 * [DiscoveryService] guards it.
 */
class AreaDiskCache(
    private val store: AreaCacheStore,
    private val clock: () -> Long = System::currentTimeMillis,
    val ttlMs: Long = 14 * 24 * 3600_000L,
    private val maxAreas: Int = 24,
    private val maxBytes: Int = 1_500_000,
    private val maxPlacesPerArea: Int = 60,
    private val maxExtractChars: Int = 1_500,
) {
    @Serializable
    internal data class Area(
        val key: String,
        val center: GeoPoint,
        val radiusM: Int,
        val lang: String,
        val atMs: Long,
        val places: List<PlaceCandidate>,
    )

    @Serializable
    internal data class Snapshot(val version: Int, val areas: List<Area>)

    private var areas: MutableList<Area>? = null

    private fun loaded(): MutableList<Area> = areas ?: run {
        val snap = runCatching { store.load()?.let { json.decodeFromString(Snapshot.serializer(), it) } }.getOrNull()
        val now = clock()
        (snap?.takeIf { it.version == VERSION }?.areas.orEmpty())
            .filter { now - it.atMs in 0L until ttlMs }
            .toMutableList()
            .also { areas = it }
    }

    /** Remembers a complete discovery result and writes the cache out. */
    fun put(key: String, center: GeoPoint, radiusM: Int, lang: String, places: List<PlaceCandidate>) {
        val list = loaded()
        list.removeAll { it.key == key }
        val slim = places.take(maxPlacesPerArea).map { p -> p.copy(extract = p.extract?.take(maxExtractChars)) }
        list += Area(key, center, radiusM, lang, clock(), slim)
        prune(list)
        var text = json.encodeToString(Snapshot.serializer(), Snapshot(VERSION, list))
        while (text.length > maxBytes && list.size > 1) {
            list.removeAt(0)
            text = json.encodeToString(Snapshot.serializer(), Snapshot(VERSION, list))
        }
        runCatching { store.save(text) }
    }

    /** Places from any cached area in [lang] that lie within [radiusM] of [center], newest area first. */
    fun around(center: GeoPoint, radiusM: Int, lang: String): List<PlaceCandidate> {
        val list = loaded()
        prune(list)
        val out = LinkedHashMap<String, PlaceCandidate>()
        list.filter { it.lang == lang && Geo.distanceM(it.center, center) <= it.radiusM + radiusM }
            .sortedByDescending { it.atMs }
            .forEach { a -> a.places.forEach { p -> if (Geo.distanceM(center, p.point) <= radiusM) out.putIfAbsent(p.id, p) } }
        return out.values.toList()
    }

    val size: Int get() = loaded().size

    private fun prune(list: MutableList<Area>) {
        val now = clock()
        list.removeAll { now - it.atMs !in 0L until ttlMs }
        list.sortBy { it.atMs }
        while (list.size > maxAreas) list.removeAt(0)
    }

    private companion object {
        const val VERSION = 2
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
