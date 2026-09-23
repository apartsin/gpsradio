package com.gpsradio.core.discovery

import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.net.fetchString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenStreetMap Overpass: named historic sites, sights and natural features around a point, plus
 * notable places to eat, drink or shop (only those with a Wikipedia/Wikidata entry, heritage status
 * or a historic tag, so the radio mentions places really worth remembering, not every café).
 */
class OverpassClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
) {
    data class Element(val osmId: String, val point: GeoPoint, val tags: Map<String, String>)

    @Serializable private data class Response(val elements: List<Item> = emptyList())
    @Serializable private data class Center(val lat: Double, val lon: Double)
    @Serializable private data class Item(
        val type: String,
        val id: Long,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    fun buildQuery(center: GeoPoint, radiusM: Int, limit: Int): String {
        val a = "around:$radiusM,${center.lat},${center.lon}"
        return """
            [out:json][timeout:20];
            (
              nwr($a)["historic"]["name"];
              nwr($a)["tourism"~"^(attraction|viewpoint|museum|artwork|gallery)$"]["name"];
              node($a)["natural"~"^(peak|volcano|cave_entrance|spring|waterfall|rock|saddle)$"]["name"];
              nwr($a)["natural"="water"]["name"];
              nwr($a)["man_made"~"^(lighthouse|windmill|watermill|tower|observatory)$"]["name"];
              nwr($a)["amenity"~"^(restaurant|cafe|pub|bar|biergarten|ice_cream)$"]["name"][~"^(wikidata|wikipedia|heritage|historic)$"~"."];
              nwr($a)["shop"]["name"][~"^(wikidata|wikipedia|heritage|historic)$"~"."];
              nwr($a)["religion"="jewish"]["name"];
            );
            out center tags $limit;
            node(around:400,${center.lat},${center.lon})["memorial"="stolperstein"];
            out tags 40;
        """.trimIndent()
    }

    suspend fun nearby(center: GeoPoint, radiusM: Int, limit: Int = 80): List<Element> {
        val req = Request.Builder()
            .url(endpoint)
            .header("User-Agent", userAgent)
            .post(FormBody.Builder().add("data", buildQuery(center, radiusM, limit)).build())
            .build()
        val body = http.fetchString(req)
        return json.decodeFromString(Response.serializer(), body).elements.mapNotNull { e ->
            val lat = e.lat ?: e.center?.lat ?: return@mapNotNull null
            val lon = e.lon ?: e.center?.lon ?: return@mapNotNull null
            Element("osm:${e.type}/${e.id}", GeoPoint(lat, lon), e.tags)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
