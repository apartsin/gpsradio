package com.gpsradio.core.favorites

import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A place the listener starred to revisit later. Self-contained so it survives leaving the area. */
@Serializable
data class FavoritePlace(
    val id: String,
    val name: String,
    val category: String,
    val point: GeoPoint,
    val summary: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val savedMs: Long = 0,
) {
    companion object {
        fun of(p: PlaceCandidate, nowMs: Long) = FavoritePlace(
            id = p.id,
            name = p.name,
            category = p.category,
            point = p.point,
            summary = (p.extract ?: p.description)?.let { firstSentences(it, 280) },
            url = p.url,
            imageUrl = p.imageUrl,
            savedMs = nowMs,
        )

        private fun firstSentences(text: String, max: Int): String {
            if (text.length <= max) return text
            val cut = text.take(max)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
            return if (end > 60) cut.take(end + 1) else "$cut…"
        }
    }
}

interface FavoritesStore {
    fun load(): String?
    fun save(serialized: String)
}

class Favorites {
    private val items = LinkedHashMap<String, FavoritePlace>()

    val all: List<FavoritePlace> get() = items.values.sortedByDescending { it.savedMs }

    fun contains(id: String) = id in items

    /** Stars or un-stars; returns true when the place is now starred. */
    fun toggle(place: FavoritePlace): Boolean {
        if (items.remove(place.id) != null) return false
        items[place.id] = place
        return true
    }

    fun add(place: FavoritePlace) {
        items.putIfAbsent(place.id, place)
    }

    fun remove(id: String) = items.remove(id) != null

    fun serialize(): String = json.encodeToString(ListSerializer(FavoritePlace.serializer()), items.values.toList())

    fun restore(serialized: String?) {
        if (serialized.isNullOrBlank()) return
        val loaded = runCatching { json.decodeFromString(ListSerializer(FavoritePlace.serializer()), serialized) }.getOrNull() ?: return
        items.clear()
        loaded.forEach { items[it.id] = it }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Plain-text share message for the Android share sheet (works in any messenger or mail app). */
object ShareText {
    fun mapUrl(p: GeoPoint): String =
        "https://www.openstreetmap.org/?mlat=${p.lat}&mlon=${p.lon}#map=17/${p.lat}/${p.lon}"

    fun build(place: FavoritePlace): String = buildString {
        append("📍 ").append(place.name)
        place.summary?.let { append("\n\n").append(it) }
        place.url?.let { append("\n\nRead more: ").append(it) }
        append("\nMap: ").append(mapUrl(place.point))
        append("\n\nDiscovered with GPS Radio 📻")
    }

    fun subject(place: FavoritePlace): String = "Check this out: ${place.name}"
}
