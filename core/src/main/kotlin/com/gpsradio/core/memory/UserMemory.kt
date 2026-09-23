package com.gpsradio.core.memory

import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.model.Topic
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Durable listener preferences learned from conversation ("I love castles", "no war stories",
 * "shorter please"). Stored on the device and fed back into every prompt in later sessions.
 */
@Serializable
data class MemoryItem(
    val id: String,
    val category: MemoryCategory,
    val text: String,
    /** Optional content category this preference maps to; drives editorial ranking. */
    val topic: String? = null,
    val createdMs: Long = 0,
) {
    val topicOrNull: Topic? get() = topic?.let { Topic.fromKey(it) }
}

@Serializable
enum class MemoryCategory {
    /** Things the listener enjoys. */
    LIKE,
    /** Things to avoid or tell less of. */
    AVOID,
    /** How to talk: length, tone, pace, detail. */
    STYLE,
    /** Useful context about the listener (e.g. "travels with kids", "vegetarian"). */
    ABOUT_ME;

    companion object {
        fun parse(s: String?): MemoryCategory? = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** Where the serialized memory lives (a file on the device). */
interface MemoryStore {
    fun load(): String?
    fun save(serialized: String)
}

class UserMemory(private val maxItems: Int = 40) {
    private val items = ArrayList<MemoryItem>()

    val all: List<MemoryItem> get() = items.toList()

    /** Adds a preference; a new item with the same meaning replaces the older one. */
    fun remember(category: MemoryCategory, text: String, topic: Topic?, nowMs: Long): MemoryItem? {
        val clean = text.trim().removeSuffix(".").take(200)
        if (clean.isBlank()) return null
        val key = HeardHistory.normalizeName(clean)
        items.removeAll { HeardHistory.normalizeName(it.text) == key }
        // A new like/avoid on the same topic supersedes the opposite one.
        if (topic != null && (category == MemoryCategory.LIKE || category == MemoryCategory.AVOID)) {
            items.removeAll { it.topicOrNull == topic && it.category != category && it.category in setOf(MemoryCategory.LIKE, MemoryCategory.AVOID) }
        }
        val item = MemoryItem("m$nowMs-${items.size}", category, clean, topic?.key, nowMs)
        items += item
        while (items.size > maxItems) items.removeAt(0)
        return item
    }

    /** Removes items whose text matches the description (case/punctuation-insensitive containment). */
    fun forget(description: String): Int {
        val key = HeardHistory.normalizeName(description)
        if (key.isBlank()) return 0
        val before = items.size
        items.removeAll { val t = HeardHistory.normalizeName(it.text); t == key || t.contains(key) || key.contains(t) }
        return before - items.size
    }

    fun remove(id: String) = items.removeAll { it.id == id }

    fun clear() = items.clear()

    /** Topic weight adjustments for the editorial ranker: likes → 1.0, avoids → near zero. */
    fun topicWeights(): Map<Topic, Double> = buildMap {
        items.forEach { m ->
            val t = m.topicOrNull ?: return@forEach
            when (m.category) {
                MemoryCategory.LIKE -> put(t, 1.0)
                MemoryCategory.AVOID -> put(t, 0.05)
                else -> Unit
            }
        }
    }

    /** Compact profile lines for prompts, e.g. "likes: castles and medieval history". */
    fun promptLines(): List<String> = items.map { "${it.category.name.lowercase().replace('_', ' ')}: ${it.text}" }

    fun serialize(): String = json.encodeToString(ListSerializer(MemoryItem.serializer()), items)

    fun restore(serialized: String?) {
        if (serialized.isNullOrBlank()) return
        val loaded = runCatching { json.decodeFromString(ListSerializer(MemoryItem.serializer()), serialized) }.getOrNull() ?: return
        items.clear()
        items.addAll(loaded.takeLast(maxItems))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
