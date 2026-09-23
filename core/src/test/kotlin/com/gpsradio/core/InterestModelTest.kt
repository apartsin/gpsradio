package com.gpsradio.core

import com.gpsradio.core.editorial.InterestModel
import com.gpsradio.core.editorial.InterestModel.Signal
import com.gpsradio.core.model.Topic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterestModelTest {
    private val day = 24 * 3600_000L

    @Test
    fun signalsMoveTopicWeightsInTheRightDirection() {
        val m = InterestModel()
        m.record(Signal.COMPLETED, setOf(Topic.HISTORY), 0)
        m.record(Signal.EARLY_SKIP, setOf(Topic.WAR), 0)
        m.record(Signal.FOLLOW_UP, setOf(Topic.NATURE), 0)
        val history = m.weight(Topic.HISTORY, 0)
        val war = m.weight(Topic.WAR, 0)
        val nature = m.weight(Topic.NATURE, 0)
        assertTrue(history > 0 && war < 0 && nature > 0)
        // A follow-up question counts more than a full listen; an early skip counts most.
        assertTrue(nature > history)
        assertTrue(-war > nature)
        assertEquals(0.0, m.weight(Topic.FOOD, 0))
    }

    @Test
    fun weightsAreBoundedAndDecay() {
        val m = InterestModel(halfLifeMs = day, maxAbs = 0.5)
        repeat(20) { m.record(Signal.EARLY_SKIP, setOf(Topic.WAR), 0) }
        assertEquals(-0.5, m.weight(Topic.WAR, 0), 1e-9)
        repeat(50) { m.record(Signal.FOLLOW_UP, setOf(Topic.HISTORY), 0) }
        assertEquals(0.5, m.weight(Topic.HISTORY, 0), 1e-9)
        // Half-life: one day later the offset has halved, and it keeps fading.
        assertEquals(-0.25, m.weight(Topic.WAR, day), 1e-9)
        assertTrue(kotlin.math.abs(m.weight(Topic.WAR, 30 * day)) < 1e-6)
        assertTrue(m.weights(30 * day).isEmpty())
    }

    @Test
    fun sameSignalForTheSameStoryCountsOnce() {
        val m = InterestModel()
        assertTrue(m.record(Signal.FOLLOW_UP, setOf(Topic.HISTORY), 0, key = "castle"))
        assertFalse(m.record(Signal.FOLLOW_UP, setOf(Topic.HISTORY), 0, key = "castle"))
        assertEquals(Signal.FOLLOW_UP.delta, m.weight(Topic.HISTORY, 0), 1e-9)
        // Another story, or another signal for the same story, still counts.
        assertTrue(m.record(Signal.FOLLOW_UP, setOf(Topic.HISTORY), 0, key = "tower"))
        assertTrue(m.record(Signal.COMPLETED, setOf(Topic.HISTORY), 0, key = "castle"))
        assertFalse(m.record(Signal.COMPLETED, emptySet(), 0))
    }

    @Test
    fun adjustAddsOffsetsButRespectsExplicitPreferences() {
        val m = InterestModel()
        m.record(Signal.EARLY_SKIP, setOf(Topic.WAR, Topic.HISTORY), 0)
        m.record(Signal.FOLLOW_UP, setOf(Topic.NATURE), 0)
        val base = mapOf(Topic.HISTORY to 1.0, Topic.WAR to 0.05)
        val out = m.adjust(base, 0, explicit = setOf(Topic.WAR), defaultInterest = 0.4)
        assertEquals(1.0 + Signal.EARLY_SKIP.delta, out.getValue(Topic.HISTORY), 1e-9)
        // Remembered likes/avoids are never overridden by implicit signals.
        assertEquals(0.05, out.getValue(Topic.WAR))
        // Topics without an explicit weight start from the default.
        assertEquals(0.4 + Signal.FOLLOW_UP.delta, out.getValue(Topic.NATURE), 1e-9)
        // Adjusted weights stay in a sane range.
        repeat(10) { m.record(Signal.EARLY_SKIP, setOf(Topic.FOOD), 0) }
        assertEquals(0.02, m.adjust(emptyMap(), 0).getValue(Topic.FOOD), 1e-9)
    }

    @Test
    fun serializationRoundTrips() {
        val m = InterestModel(halfLifeMs = day)
        m.record(Signal.FOLLOW_UP, setOf(Topic.LEGENDS), 0)
        m.record(Signal.EARLY_SKIP, setOf(Topic.WAR), 0)
        val restored = InterestModel(halfLifeMs = day).apply { restore(m.serialize(day)) }
        assertEquals(m.weight(Topic.LEGENDS, 2 * day), restored.weight(Topic.LEGENDS, 2 * day), 1e-9)
        assertEquals(m.weight(Topic.WAR, day), restored.weight(Topic.WAR, day), 1e-9)
        // Corrupt or empty data is ignored.
        InterestModel().apply { restore("{not json"); assertTrue(weights(0).isEmpty()) }
        InterestModel().apply { restore(null); assertTrue(weights(0).isEmpty()) }
    }
}
