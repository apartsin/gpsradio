package com.gpsradio.core.editorial

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Story fingerprints already delivered (spec A PR-12). Entries expire so a place can come back
 * on a much later trip. Names are tracked too, so the same place from another source is suppressed.
 */
class HeardHistory(
    private val retentionMs: Long = 30L * 24 * 3600 * 1000,
) {
    @Serializable
    private data class Snapshot(val ids: Map<String, Long> = emptyMap(), val names: Map<String, Long> = emptyMap())

    private val ids = HashMap<String, Long>()
    private val names = HashMap<String, Long>()

    fun markHeard(storyId: String, name: String, nowMs: Long) {
        ids[storyId] = nowMs
        names[normalizeName(name)] = nowMs
    }

    fun wasHeard(storyId: String, name: String, nowMs: Long): Boolean {
        val t = ids[storyId] ?: names[normalizeName(name)] ?: return false
        return nowMs - t < retentionMs
    }

    fun clear() {
        ids.clear()
        names.clear()
    }

    fun serialize(nowMs: Long): String {
        prune(nowMs)
        return json.encodeToString(Snapshot.serializer(), Snapshot(ids.toMap(), names.toMap()))
    }

    fun restore(serialized: String?, nowMs: Long) {
        if (serialized.isNullOrBlank()) return
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), serialized) }.getOrNull() ?: return
        ids.putAll(snap.ids)
        names.putAll(snap.names)
        prune(nowMs)
    }

    private fun prune(nowMs: Long) {
        ids.entries.removeAll { nowMs - it.value >= retentionMs }
        names.entries.removeAll { nowMs - it.value >= retentionMs }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun normalizeName(name: String): String =
            name.lowercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
