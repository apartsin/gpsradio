package com.gpsradio.core.discovery

import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.PlaceFeature
import com.gpsradio.core.model.Topic
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
    /** Film locations and dated historical events (spec A §29); optional, never blocks the other sources. */
    private val wikidata: WikidataClient? = null,
    private val maxWikidataRadiusM: Int = 8_000,
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
        val wdRadius = min(radiusM, maxWikidataRadiusM)
        val (wikiResults, osmResult, extras) = coroutineScope {
            val wiki = langs.map { lang -> async { lang to runCatching { wikiCandidates(lang, query, radiusM) } } }
            // Large Overpass radii routinely time out; OSM adds most value close by anyway.
            val osm = async { runCatching { overpass.nearby(query, min(radiusM, maxOsmRadiusM)) } }
            // Optional: a slow or failing Wikidata never fails discovery (and isn't counted as a failure).
            val films = async { wikidata?.let { w -> runCatching { w.filmLocations(query, wdRadius, languageBase) }.getOrNull() }.orEmpty() }
            val events = async { wikidata?.let { w -> runCatching { w.events(query, wdRadius, languageBase) }.getOrNull() }.orEmpty() }
            val jewish = async { wikidata?.let { w -> runCatching { w.jewishConnections(query, wdRadius, languageBase) }.getOrNull() }.orEmpty() }
            Triple(wiki.map { it.await() }, osm.await(), Triple(films.await(), events.await(), jewish.await()))
        }
        val failures = wikiResults.mapNotNull { it.second.exceptionOrNull() } + listOfNotNull(osmResult.exceptionOrNull())
        if (failures.size == wikiResults.size + 1) return fromDisk(key, center, radiusM, languageBase, failures.first())

        val merged = addWikidata(
            merge(
                wiki = wikiResults.flatMap { it.second.getOrDefault(emptyList()) },
                osm = osmResult.getOrDefault(emptyList()),
                languageBase = languageBase,
            ),
            films = extras.first,
            events = extras.second,
            jewish = extras.third,
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
                features = if (Topic.JEWISH in topics) setOf(PlaceFeature.JEWISH_HERITAGE) else emptySet(),
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

        val (stones, others) = osm.partition { it.tags["memorial"] == "stolperstein" }
        out += stolpersteine(stones)
        for (e in others) {
            val tags = e.tags
            val name = tags["name:$languageBase"] ?: tags["name"] ?: continue
            val osmTopics = TopicClassifier.fromOsmTags(tags)
            val wd = tags["wikidata"]
            val linked = out.indexOfFirst { (wd != null && it.wikidataId == wd) || isSamePlace(it, name, e.point) }
            if (linked >= 0) {
                val c = out[linked]
                out[linked] = c.copy(
                    openingHours = c.openingHours ?: tags["opening_hours"],
                    fee = c.fee ?: osmFee(tags),
                    topics = c.topics + osmTopics,
                    sourceConfidence = min(1.0, c.sourceConfidence + 0.05),
                    imageUrl = c.imageUrl ?: osmImage(tags),
                )
                continue
            }
            val facts = osmFacts(tags)
            val features = osmFeatures(tags)
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
                features = features,
                openingHours = tags["opening_hours"],
                fee = osmFee(tags),
            )
        }
        return out
    }

    /**
     * Stolpersteine (brass memorial stones for victims of Nazism) come in dozens per street, so nearby
     * stones (within ~120 m) become one place, named by street, with the names and inscriptions as facts.
     */
    internal fun stolpersteine(stones: List<OverpassClient.Element>): List<PlaceCandidate> {
        val clusters = ArrayList<MutableList<OverpassClient.Element>>()
        for (s in stones) {
            val c = clusters.firstOrNull { Geo.distanceM(it.first().point, s.point) <= 120 }
            if (c != null) c += s else clusters += mutableListOf(s)
        }
        return clusters.map { group ->
            val first = group.first()
            val street = group.firstNotNullOfOrNull { it.tags["addr:street"] }
            val lines = group.mapNotNull { e ->
                val who = e.tags["memorial:name"] ?: e.tags["name"]
                val text = e.tags["inscription"]?.replace('\n', ' ')
                listOfNotNull(who, text).joinToString(": ").ifBlank { null }
            }.distinct().take(8)
            PlaceCandidate(
                id = first.osmId,
                name = listOfNotNull("Stolpersteine", street).joinToString(", "),
                category = "historic: memorial (stolperstein)",
                point = first.point,
                source = "openstreetmap",
                sourceConfidence = 0.6,
                baseRelevance = min(0.75, 0.4 + 0.05 * group.size),
                topics = setOf(Topic.JEWISH, Topic.HISTORY, Topic.WAR),
                description = "Stolpersteine: memorial stones for victims of Nazi persecution",
                extract = "Stolpersteine (memorial stones set into the pavement for victims of Nazi persecution), ${group.size} here" +
                    (if (lines.isNotEmpty()) ". " + lines.joinToString("; ") else "") + ".",
                url = "https://www.openstreetmap.org/${first.osmId.removePrefix("osm:")}",
                researchStatus = ResearchStatus.READY,
                features = setOf(PlaceFeature.JEWISH_HERITAGE),
            )
        }
    }

    private fun osmFee(tags: Map<String, String>): String? =
        tags["charge"] ?: when (tags["fee"]) {
            "yes" -> "paid entry"
            "no" -> "free"
            else -> null
        }

    private fun osmFeatures(tags: Map<String, String>): Set<PlaceFeature> = buildSet {
        if (TopicClassifier.isJewish(tags)) add(PlaceFeature.JEWISH_HERITAGE)
        if (tags["amenity"] in TopicClassifier.EAT_DRINK) add(PlaceFeature.EAT_DRINK)
        if (tags["shop"] != null) add(PlaceFeature.SHOP)
    }

    /**
     * Film locations, historical events and Jewish/Israeli birthplace connections from Wikidata: enrich a
     * place already found (same Wikidata item or same name nearby) or add it. Events without a Wikipedia
     * article in the narration language are only added when they have a description, with low relevance.
     */
    internal suspend fun addWikidata(
        places: List<PlaceCandidate>,
        films: List<WikidataClient.FilmLocation>,
        events: List<WikidataClient.Event>,
        languageBase: String,
        jewish: List<WikidataClient.JewishConnection> = emptyList(),
    ): List<PlaceCandidate> {
        if (films.isEmpty() && events.isEmpty() && jewish.isEmpty()) return places
        val out = places.toMutableList()
        fun indexOf(qid: String, name: String, p: GeoPoint) = out.indexOfFirst { it.wikidataId == qid || isSamePlace(it, name, p) }

        for (j in jewish) {
            val born = "Birthplace of: ${WikidataClient.peopleText(j.people)}."
            val i = indexOf(j.qid, j.name, j.point)
            if (i >= 0) {
                val c = out[i]
                out[i] = c.copy(
                    // Feature and topic are added only for listeners who opted in (PlaceCandidate.forInterests).
                    // First, so the narration's fact budget (MAX_FACTS_CHARS) never cuts it off.
                    extract = listOfNotNull(born, c.extract).joinToString(" "), baseRelevance = min(1.0, c.baseRelevance + 0.1),
                    bornHere = born,
                )
            } else {
                out += PlaceCandidate(
                    id = "wd:${j.qid}", name = j.name, category = "birthplace", point = j.point, source = "wikidata",
                    sourceConfidence = 0.8, baseRelevance = min(0.8, 0.45 + 0.05 * j.people.size),
                    topics = setOf(Topic.JEWISH, Topic.HISTORY), description = "birthplace", extract = born,
                    url = "https://www.wikidata.org/wiki/${j.qid}", wikidataId = j.qid,
                    researchStatus = ResearchStatus.READY, features = setOf(PlaceFeature.JEWISH_HERITAGE), bornHere = born,
                )
            }
        }

        for (loc in films) {
            val filmed = "Filming location of: ${WikidataClient.filmsText(loc.films)}."
            val i = indexOf(loc.qid, loc.name, loc.point)
            if (i >= 0) {
                val c = out[i]
                out[i] = c.copy(
                    features = c.features + PlaceFeature.FILM_LOCATION,
                    topics = c.topics + Topic.FILM,
                    extract = listOfNotNull(filmed, c.extract).joinToString(" "),
                    baseRelevance = min(1.0, c.baseRelevance + 0.15),
                )
            } else {
                out += PlaceCandidate(
                    id = "wd:${loc.qid}", name = loc.name, category = "film location", point = loc.point, source = "wikidata",
                    sourceConfidence = 0.8, baseRelevance = min(0.9, 0.5 + 0.05 * loc.films.size),
                    topics = setOf(Topic.FILM, Topic.CULTURE), description = "film location", extract = filmed,
                    url = "https://www.wikidata.org/wiki/${loc.qid}", wikidataId = loc.qid,
                    researchStatus = ResearchStatus.READY, features = setOf(PlaceFeature.FILM_LOCATION),
                )
            }
        }

        val needArticles = ArrayList<WikidataClient.Event>()
        for (e in events) {
            val i = indexOf(e.qid, e.name, e.point)
            if (i >= 0) {
                val c = out[i]
                out[i] = c.copy(
                    features = c.features + PlaceFeature.HISTORIC_EVENT, eventYear = c.eventYear ?: e.year,
                    topics = c.topics + Topic.HISTORY, baseRelevance = min(1.0, c.baseRelevance + 0.1),
                )
            } else {
                needArticles += e
            }
        }
        // Facts for new events come from their Wikipedia intro (grounded), fetched in one batch.
        val pages = needArticles.mapNotNull { it.article }.takeIf { it.isNotEmpty() }?.let { titles ->
            runCatching { wikipedia.pagesByTitle(languageBase, titles) }.getOrDefault(emptyList())
        }.orEmpty().associateBy { HeardHistory.normalizeName(it.title) }
        for (e in needArticles) {
            val page = e.article?.let { pages[HeardHistory.normalizeName(it)] }
            val extract = page?.extract
            if (extract == null && e.description == null) continue
            val topics = TopicClassifier.fromText(e.name, e.description, extract?.take(1200)) + Topic.HISTORY
            out += PlaceCandidate(
                id = page?.let { "wiki:$languageBase:${it.pageId}" } ?: "wd:${e.qid}",
                name = page?.title ?: e.name,
                category = listOfNotNull("historical event", e.year?.toString()).joinToString(", "),
                point = e.point,
                source = if (page != null) "wikipedia:$languageBase" else "wikidata",
                sourceConfidence = if (extract != null) 0.85 else 0.55,
                baseRelevance = if (extract != null) (0.45 + min(extract.length / 2500.0, 0.4)) else 0.3,
                topics = topics,
                description = e.description,
                extract = (extract ?: e.description)?.take(4000),
                url = page?.let { wikipedia.articleUrl(languageBase, it.title) } ?: "https://www.wikidata.org/wiki/${e.qid}",
                wikidataId = e.qid,
                imageUrl = page?.thumbnailUrl,
                researchStatus = ResearchStatus.READY,
                features = setOf(PlaceFeature.HISTORIC_EVENT),
                eventYear = e.year,
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
        listOf("historic", "tourism", "natural", "man_made", "amenity", "shop").firstNotNullOfOrNull { k -> tags[k]?.let { "$k: $it" } } ?: "place"

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
        tags["cuisine"]?.let { add("cuisine: ${it.replace(';', ',').replace('_', ' ')}") }
        tags["opening_date"]?.let { add("opened: $it") }
    }.joinToString("; ")

    private fun cacheKey(center: GeoPoint, radiusM: Int, lang: String): String {
        val q = Geo.quantize(center, 2) // ~1 km cells
        return "${q.lat},${q.lon}|$radiusM|$lang"
    }
}
