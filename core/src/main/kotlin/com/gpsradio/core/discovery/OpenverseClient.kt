package com.gpsradio.core.discovery

import com.gpsradio.core.net.fetchString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Openverse: a free, keyless search over openly licensed images (Flickr's Creative Commons photos, museums and
 * more), used after Wikipedia and Commons find nothing (spec A §63). Every result carries its author and licence.
 */
class OpenverseClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: HttpUrl = "https://api.openverse.org/v1/images/".toHttpUrl(),
) {
    @Serializable private data class Response(val results: List<Item> = emptyList())
    @Serializable private data class Item(
        val url: String? = null,
        val thumbnail: String? = null,
        val title: String? = null,
        val creator: String? = null,
        val license: String? = null,
        val license_version: String? = null,
        val source: String? = null,
    )

    /** A found photo: its URL and the credit line ("Jane Doe · CC BY 2.0 · Flickr via Openverse"). */
    data class Photo(val url: String, val credit: String)

    suspend fun search(query: String, limit: Int = 3, accept: (title: String) -> Boolean = { true }): List<Photo> {
        val url = baseUrl.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page_size", "10")
            .addQueryParameter("mature", "false")
            .build()
        val body = http.fetchString(Request.Builder().url(url).header("User-Agent", userAgent).build())
        return json.decodeFromString(Response.serializer(), body).results.mapNotNull { r ->
            val u = r.url?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            if (!accept(r.title.orEmpty())) return@mapNotNull null
            val license = listOfNotNull(r.license?.uppercase()?.let { if (it == "CC0" || it == "PDM") it else "CC $it" }, r.license_version).joinToString(" ")
            val credit = listOfNotNull(r.creator?.trim()?.take(60)?.ifBlank { null }, license.ifBlank { null },
                (r.source?.replaceFirstChar { it.uppercase() } ?: "Openverse") + " via Openverse").joinToString(" · ")
            Photo(u, credit)
        }.take(limit)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
