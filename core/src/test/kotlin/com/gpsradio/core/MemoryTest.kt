package com.gpsradio.core

import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.memory.MemoryCategory
import com.gpsradio.core.memory.UserMemory
import com.gpsradio.core.model.Topic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryTest {
    @Test
    fun remembersDedupesAndSupersedesOppositeTopicPreference() {
        val m = UserMemory()
        m.remember(MemoryCategory.LIKE, "Loves castles", Topic.ARCHITECTURE, 1)
        m.remember(MemoryCategory.LIKE, "loves castles.", Topic.ARCHITECTURE, 2)
        assertEquals(1, m.all.size)
        m.remember(MemoryCategory.AVOID, "No war stories", Topic.WAR, 3)
        assertEquals(0.05, m.topicWeights()[Topic.WAR])
        m.remember(MemoryCategory.LIKE, "Actually interested in WWII history", Topic.WAR, 4)
        assertEquals(1.0, m.topicWeights()[Topic.WAR])
        assertTrue(m.all.none { it.category == MemoryCategory.AVOID })
        m.remember(MemoryCategory.STYLE, "Keep stories short", null, 5)
        assertTrue("style: Keep stories short" in m.promptLines())
    }

    @Test
    fun forgetsByDescriptionAndRoundTrips() {
        val m = UserMemory()
        m.remember(MemoryCategory.ABOUT_ME, "Travels with two kids", null, 1)
        m.remember(MemoryCategory.LIKE, "Waterfalls", Topic.NATURE, 2)
        val copy = UserMemory().apply { restore(m.serialize()) }
        assertEquals(m.all, copy.all)
        assertEquals(1, copy.forget("waterfalls"))
        assertEquals(listOf("Travels with two kids"), copy.all.map { it.text })
    }

    @Test
    fun parsesRememberAndForgetFromModelReply() {
        val r = RadioAgent.parseReply(
            """{"reply":"Got it, fewer churches.","action":"none","language":null,"persist_language":false,"theme":null,"entity_id":null,
               "remember":[{"category":"avoid","text":"Not interested in churches","topic":"architecture"},{"category":"bogus","text":"x","topic":null}],
               "forget":["loves churches"]}""",
        )
        assertEquals(1, r.remember.size)
        assertEquals(MemoryCategory.AVOID, r.remember.single().category)
        assertEquals(Topic.ARCHITECTURE, r.remember.single().topic)
        assertEquals(listOf("loves churches"), r.forget)
    }
}
