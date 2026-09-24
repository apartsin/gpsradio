package com.gpsradio.core.editorial

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Story fingerprints already delivered (spec A PR-12). Entries expire so a place can come back
 * on a much later trip. Names are tracked too, so the same place from another source is suppressed.
 *
 * Also remembered across days (spec A §40): the research angles already tried for an area and the area
 * stories already told, so a second day in the same town neither repeats them nor pays to research them again.
 */
class HeardHistory(
    private val retentionMs: Long = 180L * 24 * 3600 * 1000,
    private val angleRetentionMs: Long = 90L * 24 * 3600 * 1000,
) {
    @Serializable
    private data class Snapshot(
        val ids: Map<String, Long> = emptyMap(),
        val names: Map<String, Long> = emptyMap(),
        val angles: Map<String, Long> = emptyMap(),
        val facets: Map<String, Long> = emptyMap(),
    )

    private val ids = HashMap<String, Long>()
    private val names = HashMap<String, Long>()
    private val angles = HashMap<String, Long>()
    private val facets = HashMap<String, Long>()

    /** A research angle tried for an area (found or not): don't pay for it again for [angleRetentionMs]. */
    fun markAngleTried(key: String, nowMs: Long) { angles[key] = nowMs }

    fun triedAngles(nowMs: Long): Set<String> = angles.filterValues { nowMs - it < angleRetentionMs }.keys

    /** An area or researched story that has been told. */
    fun markFacetTold(id: String, nowMs: Long) { facets[id] = nowMs }

    fun toldFacets(nowMs: Long): Set<String> = facets.filterValues { nowMs - it < angleRetentionMs }.keys

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
        angles.clear()
        facets.clear()
    }

    fun serialize(nowMs: Long): String {
        prune(nowMs)
        return json.encodeToString(Snapshot.serializer(), Snapshot(ids.toMap(), names.toMap(), angles.toMap(), facets.toMap()))
    }

    fun restore(serialized: String?, nowMs: Long) {
        if (serialized.isNullOrBlank()) return
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), serialized) }.getOrNull() ?: return
        ids.putAll(snap.ids)
        names.putAll(snap.names)
        angles.putAll(snap.angles)
        facets.putAll(snap.facets)
        prune(nowMs)
    }

    private fun prune(nowMs: Long) {
        ids.entries.removeAll { nowMs - it.value >= retentionMs }
        names.entries.removeAll { nowMs - it.value >= retentionMs }
        angles.entries.removeAll { nowMs - it.value >= angleRetentionMs }
        facets.entries.removeAll { nowMs - it.value >= angleRetentionMs }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun normalizeName(name: String): String =
            name.lowercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
