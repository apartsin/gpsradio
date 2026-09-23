package com.gpsradio.core.editorial

import com.gpsradio.core.model.Topic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

/** Where learned interest weights are persisted between sessions (a small file on the device). */
interface InterestStore {
    fun load(): String?
    fun save(serialized: String)
}

/**
 * Implicit personalization (spec A §18 item 9): listening behaviour nudges topic interests.
 * - a story heard to the end: a small plus for its topics;
 * - a skip within the first seconds of playback: a strong minus;
 * - a follow-up question about the active story: a clear plus.
 *
 * Each topic keeps one learned offset in [-[maxAbs], +[maxAbs]] that decays towards zero with
 * [halfLifeMs], so old habits fade. Offsets are added to the explicit interest weights, but never
 * override what the listener explicitly asked to remember (like/avoid). Pure and clock-free:
 * callers pass the time.
 */
class InterestModel(
    private val halfLifeMs: Long = 7 * 24 * 3600_000L,
    private val maxAbs: Double = 0.5,
) {
    enum class Signal(val delta: Double) {
        /** Listened to the end. */
        COMPLETED(0.05),
        /** Skipped within the first seconds of playback. */
        EARLY_SKIP(-0.25),
        /** Asked a follow-up question about the story. */
        FOLLOW_UP(0.15),
    }

    @Serializable
    private data class Entry(val topic: String, val value: Double, val atMs: Long)

    @Serializable
    private data class Snapshot(val entries: List<Entry> = emptyList())

    private val values = HashMap<Topic, Pair<Double, Long>>()
    /** (signal, story) pairs already counted, so repeated questions about one story count once. */
    private val counted = LinkedHashSet<String>()

    private fun decayed(value: Double, atMs: Long, nowMs: Long): Double {
        val dt = (nowMs - atMs).coerceAtLeast(0L)
        return value * 0.5.pow(dt.toDouble() / halfLifeMs)
    }

    /** Current learned offset for [topic], decayed to [nowMs]. */
    fun weight(topic: Topic, nowMs: Long): Double = values[topic]?.let { (v, at) -> decayed(v, at, nowMs) } ?: 0.0

    fun weights(nowMs: Long): Map<Topic, Double> =
        values.keys.associateWith { weight(it, nowMs) }.filterValues { kotlin.math.abs(it) >= 1e-3 }

    /**
     * Records a signal for a story's [topics]. With a [key] (usually the story id) the same signal
     * for the same story counts only once. Returns true when anything changed.
     */
    fun record(signal: Signal, topics: Collection<Topic>, nowMs: Long, key: String? = null): Boolean {
        if (topics.isEmpty()) return false
        if (key != null && !counted.add("${signal.name}|$key")) return false
        while (counted.size > 200) counted.remove(counted.first())
        topics.forEach { t ->
            val v = (weight(t, nowMs) + signal.delta).coerceIn(-maxAbs, maxAbs)
            values[t] = v to nowMs
        }
        return true
    }

    /**
     * Applies learned offsets to [base] interest weights (topics missing from [base] start at
     * [defaultInterest]). Topics in [explicit] (remembered likes/avoids) are left untouched.
     */
    fun adjust(base: Map<Topic, Double>, nowMs: Long, explicit: Set<Topic> = emptySet(), defaultInterest: Double = 0.4): Map<Topic, Double> {
        val out = base.toMutableMap()
        weights(nowMs).forEach { (t, w) ->
            if (t in explicit) return@forEach
            out[t] = ((base[t] ?: defaultInterest) + w).coerceIn(0.02, 1.0)
        }
        return out
    }

    fun clear() {
        values.clear()
        counted.clear()
    }

    fun serialize(nowMs: Long): String = json.encodeToString(
        Snapshot.serializer(),
        Snapshot(weights(nowMs).map { (t, v) -> Entry(t.key, v, nowMs) }),
    )

    fun restore(serialized: String?) {
        values.clear()
        if (serialized.isNullOrBlank()) return
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), serialized) }.getOrNull() ?: return
        snap.entries.forEach { e ->
            Topic.fromKey(e.topic)?.let { values[it] = e.value.coerceIn(-maxAbs, maxAbs) to e.atMs }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
