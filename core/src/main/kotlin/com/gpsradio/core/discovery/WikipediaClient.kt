package com.gpsradio.core.discovery

import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.net.fetchString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    data class Page(val pageId: Long, val title: String, val extract: String?, val description: String?, val wikidataId: String?)

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
        val missing: Boolean = false,
    )

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
    suspend fun pages(lang: String, pageIds: List<Long>): List<Page> {
        if (pageIds.isEmpty()) return emptyList()
        return pageIds.chunked(20).flatMap { chunk ->
            val url = baseUrl(lang).newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("prop", "extracts|description|pageprops")
                .addQueryParameter("exintro", "1")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("exlimit", "20")
                .addQueryParameter("ppprop", "wikibase_item")
                .addQueryParameter("pageids", chunk.joinToString("|"))
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build()
            val body = http.fetchString(request(url))
            json.decodeFromString(PagesResponse.serializer(), body).query?.pages.orEmpty()
                .filterNot { it.missing }
                .map { Page(it.pageid, it.title, it.extract?.trim()?.ifBlank { null }, it.description, it.pageprops?.get("wikibase_item")) }
        }
    }

    fun articleUrl(lang: String, title: String): String =
        "https://$lang.wikipedia.org/wiki/" + title.replace(' ', '_')

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", userAgent).build()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
