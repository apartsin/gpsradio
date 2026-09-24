package com.gpsradio.core.discovery

import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.net.fetchString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Wikipedia geosearch + intro extracts: free, keyless, and well-sourced local history. */
class WikipediaClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    /** Override for tests; receives the language edition code. */
    private val baseUrl: (String) -> HttpUrl = { lang -> "https://$lang.wikipedia.org/w/api.php".toHttpUrl() },
) {
    data class GeoHit(val pageId: Long, val title: String, val point: GeoPoint, val distM: Double)
    data class Page(
        val pageId: Long,
        val title: String,
        val extract: String?,
        val description: String?,
        val wikidataId: String?,
        val thumbnailUrl: String? = null,
    )

    @Serializable private data class GeoResponse(val query: GeoQuery? = null)
    @Serializable private data class GeoQuery(val geosearch: List<GeoItem> = emptyList())
    @Serializable private data class GeoItem(val pageid: Long, val title: String, val lat: Double, val lon: Double, val dist: Double = 0.0)

    @Serializable private data class PagesResponse(val query: PagesQuery? = null)
    @Serializable private data class PagesQuery(val pages: List<PageItem> = emptyList())
    @Serializable private data class PageItem(
        val pageid: Long = 0,
        val title: String = "",
        val extract: String? = null,
        val description: String? = null,
        val pageprops: Map<String, String>? = null,
        val thumbnail: Thumb? = null,
        val missing: Boolean = false,
    )
    @Serializable private data class Thumb(val source: String)

    suspend fun geosearch(lang: String, center: GeoPoint, radiusM: Int, limit: Int = 50): List<GeoHit> {
        val url = baseUrl(lang).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "geosearch")
            .addQueryParameter("gscoord", "${center.lat}|${center.lon}")
            .addQueryParameter("gsradius", radiusM.coerceIn(10, 10_000).toString())
            .addQueryParameter("gslimit", limit.coerceIn(1, 500).toString())
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val body = http.fetchString(request(url))
        val parsed = json.decodeFromString(GeoResponse.serializer(), body)
        return parsed.query?.geosearch.orEmpty().map { GeoHit(it.pageid, it.title, GeoPoint(it.lat, it.lon), it.dist) }
    }

    /** Intro extracts for up to 20 pages per call (API limit for exintro). */
    suspend fun pages(lang: String, pageIds: List<Long>): List<Page> = fetchPages(lang, "pageids", pageIds.map { it.toString() })

    /** Like [pages], by article title (redirects followed). */
    suspend fun pagesByTitle(lang: String, titles: List<String>): List<Page> = fetchPages(lang, "titles", titles.distinct())

    private suspend fun fetchPages(lang: String, key: String, values: List<String>): List<Page> {
        if (values.isEmpty()) return emptyList()
        return values.chunked(20).flatMap { chunk ->
            val url = baseUrl(lang).newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("prop", "extracts|description|pageprops|pageimages")
                .addQueryParameter("exintro", "1")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("exlimit", "20")
                .addQueryParameter("ppprop", "wikibase_item")
                .addQueryParameter("piprop", "thumbnail")
                .addQueryParameter("pithumbsize", "640")
                .addQueryParameter("pilimit", "20")
                .addQueryParameter(key, chunk.joinToString("|"))
                .apply { if (key == "titles") addQueryParameter("redirects", "1") }
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build()
            val body = http.fetchString(request(url))
            json.decodeFromString(PagesResponse.serializer(), body).query?.pages.orEmpty()
                .filterNot { it.missing }
                .map {
                    Page(
                        it.pageid, it.title, it.extract?.trim()?.ifBlank { null }, it.description,
                        it.pageprops?.get("wikibase_item"), it.thumbnail?.source,
                    )
                }
        }
    }

    @Serializable private data class ImagesResponse(val query: ImagesQuery? = null)
    @Serializable private data class ImagesQuery(val pages: List<ImagePage> = emptyList())
    @Serializable private data class ImagePage(val title: String = "", val imageinfo: List<ImageInfo> = emptyList())
    @Serializable private data class ImageInfo(val thumburl: String? = null, val url: String? = null)

    /**
     * Photos used in an article (via Wikimedia Commons), best first. Icons, maps, flags, logos and
     * diagrams are filtered out so the gallery shows the place itself.
     */
    suspend fun articleImages(lang: String, title: String, limit: Int = 8): List<String> {
        val url = baseUrl(lang).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("generator", "images")
            .addQueryParameter("titles", title)
            .addQueryParameter("gimlimit", "30")
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url")
            .addQueryParameter("iiurlwidth", "800")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val body = http.fetchString(request(url))
        return json.decodeFromString(ImagesResponse.serializer(), body).query?.pages.orEmpty()
            .filter { isPhoto(it.title) }
            .mapNotNull { p -> p.imageinfo.firstOrNull()?.let { it.thumburl ?: it.url } }
            .distinct()
            .take(limit)
    }

    @Serializable private data class CreditsResponse(val query: CreditsQuery? = null)
    @Serializable private data class CreditsQuery(val pages: List<CreditsPage> = emptyList())
    @Serializable private data class CreditsPage(val title: String = "", val imageinfo: List<CreditsInfo> = emptyList())
    @Serializable private data class CreditsInfo(val extmetadata: Map<String, MetaValue> = emptyMap())
    @Serializable private data class MetaValue(val value: String? = null)

    /**
     * Author and licence of Wikimedia Commons photos (spec A §45), keyed by the image URL: "Jane Doe · CC BY-SA 4.0 ·
     * Wikimedia Commons" (the app adds a localized "Photo:"). Photos whose file can't be identified, or that have no metadata, are left out.
     */
    suspend fun photoCredits(urls: List<String>, lang: String = "en"): Map<String, String> {
        val byTitle = urls.mapNotNull { u -> fileTitle(u)?.let { "File:$it" to u } }.toMap()
        if (byTitle.isEmpty()) return emptyMap()
        val url = baseUrl(lang).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("titles", byTitle.keys.take(50).joinToString("|"))
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "extmetadata")
            .addQueryParameter("iiextmetadatafilter", "Artist|LicenseShortName")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val body = http.fetchString(request(url))
        return json.decodeFromString(CreditsResponse.serializer(), body).query?.pages.orEmpty().mapNotNull { p ->
            val meta = p.imageinfo.firstOrNull()?.extmetadata ?: return@mapNotNull null
            val source = byTitle[p.title] ?: byTitle[p.title.replace(' ', '_')] ?: return@mapNotNull null
            credit(meta["Artist"]?.value, meta["LicenseShortName"]?.value)?.let { source to it }
        }.toMap()
    }

    /** A whole article as plain text, with `== Section ==` headings kept (for area stories); null if missing. */
    suspend fun articleByTitle(lang: String, title: String): Article? {
        val url = baseUrl(lang).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "extracts")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exsectionformat", "wiki")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("titles", title)
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val body = http.fetchString(request(url))
        val page = json.decodeFromString(PagesResponse.serializer(), body).query?.pages.orEmpty()
            .firstOrNull { !it.missing && !it.extract.isNullOrBlank() } ?: return null
        return Article(page.title, page.extract!!.trim(), articleUrl(lang, page.title))
    }

    data class Article(val title: String, val text: String, val url: String)

    fun articleUrl(lang: String, title: String): String =
        "https://$lang.wikipedia.org/wiki/" + title.replace(' ', '_')

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", userAgent).build()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val nonPhoto = Regex(
            "(icon|logo|flag|coat[ _]of[ _]arms|wappen|map|karte|locator|symbol|signature|diagram|plan|commons-|wiki|edit|question|stub|pictogram)",
            RegexOption.IGNORE_CASE,
        )

        /** "Jane Doe · CC BY-SA 4.0 · Wikimedia Commons" from the raw (HTML) metadata; null when neither is known. */
        fun credit(artistHtml: String?, license: String?): String? {
            val artist = artistHtml?.replace(Regex("<[^>]*>"), "")?.replace("&amp;", "&")?.replace("&nbsp;", " ")
                ?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.ifBlank { null }
            val lic = license?.trim()?.ifBlank { null }
            if (artist == null && lic == null) return null
            return listOfNotNull(artist, lic, "Wikimedia Commons").joinToString(" · ")
        }

        /** The Commons file name in an upload.wikimedia.org URL (original or thumbnail), with spaces; null otherwise. */
        fun fileTitle(url: String): String? {
            val u = url.toHttpUrlOrNull() ?: return null
            // Wikipedia serves thumbnails from thumb.wikimedia.org too (same path layout).
            if (u.host != "upload.wikimedia.org" && u.host != "thumb.wikimedia.org") return null
            val seg = u.pathSegments
            val i = seg.indexOf("thumb")
            val name = if (i >= 0) seg.getOrNull(i + 3) else seg.lastOrNull()
            return name?.takeIf { it.contains('.') }?.replace('_', ' ')
        }

        fun isPhoto(fileTitle: String): Boolean {
            val t = fileTitle.lowercase()
            val ext = t.endsWith(".jpg") || t.endsWith(".jpeg") || t.endsWith(".png") || t.endsWith(".webp")
            return ext && !nonPhoto.containsMatchIn(t.substringAfter(':'))
        }
    }
}
