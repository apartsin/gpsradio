package com.gpsradio.core.discovery

import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.ResearchStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min

/** Something that can list grounded nearby entities. A future backend can implement this too. */
interface PlacesProvider {
    suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String): List<PlaceCandidate>

    /** Real photos of a place for the gallery; by default just its main image. */
    suspend fun gallery(place: PlaceCandidate): List<String> = listOfNotNull(place.imageUrl)
}

/**
 * Finds and enriches nearby entities (spec B §7) from Wikipedia (in the narration language and
 * English) and OpenStreetMap, merges duplicates, and caches area results by coarse cell.
 */
class DiscoveryService(
    private val wikipedia: WikipediaClient,
    private val overpass: OverpassClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = 6 * 3600_000L,
    private val maxCacheEntries: Int = 30,
    private val articlesPerLanguage: Int = 20,
    private val partialCacheTtlMs: Long = 5 * 60_000L,
    private val maxOsmRadiusM: Int = 3_000,
    /** Areas kept on disk so previously visited places still work offline; null keeps memory only. */
    private val diskCache: AreaDiskCache? = null,
    /** Network state; when offline, cached areas are served without trying the network. */
    private val isOnline: () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlacesProvider {

    private data class CacheEntry(val atMs: Long, val places: List<PlaceCandidate>, val ttlMs: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > maxCacheEntries
    }

    override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String): List<PlaceCandidate> {
        val key = cacheKey(center, radiusM, languageBase)
        synchronized(cache) { cache[key] }?.let { if (clock() - it.atMs < it.ttlMs) return it.places }
        if (!isOnline()) return fromDisk(key, center, radiusM, languageBase, IOException("You're offline"))

        val query = Geo.quantize(center)
        val langs = listOf(languageBase, "en").distinct()
        val (wikiResults, osmResult) = coroutineScope {
            val wiki = langs.map { lang -> async { lang to runCatching { wikiCandidates(lang, query, radiusM) } } }
            // Large Overpass radii routinely time out; OSM adds most value close by anyway.
            val osm = async { runCatching { overpass.nearby(query, min(radiusM, maxOsmRadiusM)) } }
            wiki.map { it.await() } to osm.await()
        }
        val failures = wikiResults.mapNotNull { it.second.exceptionOrNull() } + listOfNotNull(osmResult.exceptionOrNull())
        if (failures.size == wikiResults.size + 1) return fromDisk(key, center, radiusM, languageBase, failures.first())

        val merged = merge(
            wiki = wikiResults.flatMap { it.second.getOrDefault(emptyList()) },
            osm = osmResult.getOrDefault(emptyList()),
            languageBase = languageBase,
        )
        // Partial results (a source failed) are cached briefly so the missing source is retried soon.
        val ttl = if (failures.isEmpty()) cacheTtlMs else partialCacheTtlMs
        synchronized(cache) { cache[key] = CacheEntry(clock(), merged, ttl) }
        // Only complete results go to disk, so an offline area is never a half-empty one.
        if (failures.isEmpty() && diskCache != null) {
            withContext(ioDispatcher) { synchronized(diskCache) { diskCache.put(key, center, radiusM, languageBase, merged) } }
        }
        return merged
    }

    /** Offline or every source failed: serve previously visited places around here, or rethrow [cause]. */
    private suspend fun fromDisk(key: String, center: GeoPoint, radiusM: Int, languageBase: String, cause: Throwable): List<PlaceCandidate> {
        val disk = diskCache ?: throw cause
        val places = withContext(ioDispatcher) { synchronized(disk) { disk.around(center, radiusM, languageBase) } }
        if (places.isEmpty()) throw cause
        // Keep briefly in memory; the network is tried again once it expires.
        synchronized(cache) { cache[key] = CacheEntry(clock(), places, partialCacheTtlMs) }
        return places
    }

    private suspend fun wikiCandidates(lang: String, center: GeoPoint, radiusM: Int): List<PlaceCandidate> {
        val hits = wikipedia.geosearch(lang, center, min(radiusM, 10_000), limit = 60)
            .sortedBy { it.distM }
            .take(articlesPerLanguage)
        val byId = hits.associateBy { it.pageId }
        return wikipedia.pages(lang, hits.map { it.pageId }).mapNotNull { page ->
            val hit = byId[page.pageId] ?: return@mapNotNull null
            val extract = page.extract
            val topics = TopicClassifier.fromText(page.title, page.description, extract?.take(1200))
            val len = extract?.length ?: 0
            PlaceCandidate(
                id = "wiki:$lang:${page.pageId}",
                name = page.title,
                category = page.description ?: "place",
                point = hit.point,
                source = "wikipedia:$lang",
                sourceConfidence = if (len > 200) 0.85 else 0.6,
                baseRelevance = (0.3 + min(len / 2500.0, 0.45) + if (topics.isNotEmpty()) 0.1 else 0.0).coerceAtMost(1.0),
                topics = topics,
                description = page.description,
                extract = extract?.take(4000),
                url = wikipedia.articleUrl(lang, page.title),
                wikidataId = page.wikidataId,
                imageUrl = page.thumbnailUrl,
                researchStatus = if (extract != null) ResearchStatus.READY else ResearchStatus.FAILED,
            )
        }
    }

    override suspend fun gallery(place: PlaceCandidate): List<String> {
        val lang = place.source.removePrefix("wikipedia:").takeIf { place.source.startsWith("wikipedia:") }
            ?: return listOfNotNull(place.imageUrl)
        val more = runCatching { wikipedia.articleImages(lang, place.name) }.getOrDefault(emptyList())
        return (listOfNotNull(place.imageUrl) + more).distinctBy { it.substringAfterLast('/').substringAfter("px-") }.take(8)
    }

    internal fun merge(wiki: List<PlaceCandidate>, osm: List<OverpassClient.Element>, languageBase: String): List<PlaceCandidate> {
        // Prefer the narration-language article when the same entity appears in several editions.
        val byEntity = LinkedHashMap<String, PlaceCandidate>()
        wiki.sortedBy { if (it.source == "wikipedia:$languageBase") 0 else 1 }.forEach { c ->
            val k = c.wikidataId ?: c.id
            if (k !in byEntity) byEntity[k] = c
        }
        val out = byEntity.values.toMutableList()

        for (e in osm) {
            val tags = e.tags
            val name = tags["name:$languageBase"] ?: tags["name"] ?: continue
            val osmTopics = TopicClassifier.fromOsmTags(tags)
            val wd = tags["wikidata"]
            val linked = out.indexOfFirst { (wd != null && it.wikidataId == wd) || isSamePlace(it, name, e.point) }
            if (linked >= 0) {
                val c = out[linked]
                out[linked] = c.copy(
                    topics = c.topics + osmTopics,
                    sourceConfidence = min(1.0, c.sourceConfidence + 0.05),
                    imageUrl = c.imageUrl ?: osmImage(tags),
                )
                continue
            }
            val facts = osmFacts(tags)
            out += PlaceCandidate(
                id = e.osmId,
                name = name,
                category = osmCategory(tags),
                point = e.point,
                source = "openstreetmap",
                sourceConfidence = 0.5,
                baseRelevance = 0.2 + (if (wd != null || tags["wikipedia"] != null) 0.15 else 0.0) + min(facts.length / 800.0, 0.2),
                topics = osmTopics,
                description = tags["description"],
                extract = facts.ifBlank { null },
                url = "https://www.openstreetmap.org/${e.osmId.removePrefix("osm:")}",
                wikidataId = wd,
                imageUrl = osmImage(tags),
                researchStatus = ResearchStatus.READY,
            )
        }
        return out
    }

    private fun isSamePlace(c: PlaceCandidate, name: String, p: GeoPoint): Boolean =
        HeardHistory.normalizeName(c.name) == HeardHistory.normalizeName(name) && Geo.distanceM(c.point, p) < 300

    /** OSM `image` (direct URL) or `wikimedia_commons` (File:...) tags → a displayable image URL. */
    private fun osmImage(tags: Map<String, String>): String? {
        tags["image"]?.takeIf { it.startsWith("https://") }?.let { return it }
        val file = tags["wikimedia_commons"]?.takeIf { it.startsWith("File:") } ?: return null
        return "https://commons.wikimedia.org/wiki/Special:FilePath/" +
            java.net.URLEncoder.encode(file.removePrefix("File:").replace(' ', '_'), "UTF-8") + "?width=640"
    }

    private fun osmCategory(tags: Map<String, String>): String =
        listOf("historic", "tourism", "natural", "man_made").firstNotNullOfOrNull { k -> tags[k]?.let { "$k: $it" } } ?: "place"

    private fun osmFacts(tags: Map<String, String>): String = buildList {
        add(osmCategory(tags))
        tags["description"]?.let { add("description: $it") }
        tags["inscription"]?.let { add("inscription: $it") }
        tags["start_date"]?.let { add("dates from: $it") }
        tags["ele"]?.let { add("elevation: $it m") }
        tags["heritage"]?.let { add("heritage-listed") }
        tags["architect"]?.let { add("architect: $it") }
        tags["artist_name"]?.let { add("artist: $it") }
        tags["memorial"]?.let { add("memorial type: $it") }
    }.joinToString("; ")

    private fun cacheKey(center: GeoPoint, radiusM: Int, lang: String): String {
        val q = Geo.quantize(center, 2) // ~1 km cells
        return "${q.lat},${q.lon}|$radiusM|$lang"
    }
}
