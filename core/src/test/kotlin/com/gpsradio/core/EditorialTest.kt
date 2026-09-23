package com.gpsradio.core

import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun place(id: String, at: GeoPoint, relevance: Double = 0.8, topics: Set<Topic> = setOf(Topic.HISTORY), name: String = id) =
    PlaceCandidate(id, name, "castle", at, "test", 0.85, relevance, topics, extract = "Facts about $name.")

class EditorialTest {
    private val here = GeoPoint(47.61, 13.78)
    private val walking = LocationContext(here, 5f, 0, 1.3, 0.0, TravelMode.WALKING)
    private val ranker = EditorialRanker()

    private fun ctx(heard: HeardHistory = HeardHistory(), theme: Topic? = null, lastEnd: Long? = null) =
        EditorialRanker.Context(walking, mapOf(Topic.HISTORY to 1.0), heard, nowMs = 100_000, lastSpeechEndMs = lastEnd, theme = theme)

    @Test
    fun closerAndAheadRanksHigher() {
        val ahead = place("ahead", Geo.destination(here, 0.0, 200.0))
        val behindFar = place("behind", Geo.destination(here, 180.0, 1200.0))
        val ranked = ranker.rank(listOf(behindFar, ahead), ctx())
        assertEquals("ahead", ranked.first().place.id)
        assertNotNull(ranker.pickForAirtime(ranked))
    }

    @Test
    fun silenceWhenNothingIsGoodEnough() {
        val weak = place("weak", Geo.destination(here, 0.0, 1400.0), relevance = 0.1, topics = emptySet())
        assertNull(ranker.pickForAirtime(ranker.rank(listOf(weak), ctx())))
    }

    @Test
    fun heardStoriesAreSuppressedAcrossSourcesByName() {
        val heard = HeardHistory()
        heard.markHeard("wiki:en:1", "Schloss Ort (Gmunden)", 50_000)
        val sameByName = place("osm:node/9", Geo.destination(here, 0.0, 100.0), name = "Schloss Ort")
        val ranked = ranker.rank(listOf(sameByName), ctx(heard))
        assertEquals(1.0, ranked.single().breakdown.repetition)
        assertNull(ranker.pickForAirtime(ranked))
    }

    @Test
    fun recentSpeechAddsConversationCostAndThemeFilters() {
        val p = place("p", Geo.destination(here, 0.0, 150.0))
        val fresh = ranker.rank(listOf(p), ctx()).single().score
        val justSpoke = ranker.rank(listOf(p), ctx(lastEnd = 99_000)).single().score
        assertTrue(justSpoke < fresh)
        assertTrue(ranker.rank(listOf(p), ctx(theme = Topic.WAR)).isEmpty())
    }

    @Test
    fun historySerializationRoundTripsAndExpires() {
        val h = HeardHistory(retentionMs = 1_000)
        h.markHeard("a", "Alpha", 0)
        val restored = HeardHistory(retentionMs = 1_000).apply { restore(h.serialize(10), 10) }
        assertTrue(restored.wasHeard("a", "x", 500))
        assertFalse(restored.wasHeard("a", "x", 2_000))
    }
}
