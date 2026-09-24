package com.gpsradio.core

import com.gpsradio.core.discovery.AnglePlanner
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.StoryAngle
import com.gpsradio.core.discovery.WikidataClient
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceFeature
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.forInterests
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec A §44: Jewish & Israel connections are opt-in, and faith or ethnicity only ever of people who have died. */
class OptInTopicsTest {
    private val here = GeoPoint(47.918, 13.799)

    @Test
    fun faithAndEthnicityCountOnlyForPeopleWhoHaveDied() {
        val q = WikidataClient.jewishQuery(here, 5_000, "ru", 40)
        // Citizenship alone for everyone; faith (P140) and ethnicity (P172) need a date of death (P570).
        val sensitive = q.substringAfter("UNION {", "").substringBeforeLast("}")
        assertTrue("P140" in sensitive && "P172" in sensitive && "wdt:P570" in sensitive, q)
        assertFalse("P570" in q.substringBefore("UNION"), "Israeli citizenship does not need it: $q")
    }

    private fun enriched() = runBlocking {
        val svc = DiscoveryService(WikipediaClient(OkHttpClient(), "ua"), OverpassClient(OkHttpClient(), "ua"))
        val castle = place("wiki:en:1", GeoPoint(47.9105, 13.8013), name = "Schloss Ort").copy(wikidataId = "Q100", extract = "A lake castle.")
        val person = listOf(WikidataClient.Person("Q9", "Ruth Example", "Israeli writer"))
        svc.addWikidata(
            listOf(castle), films = emptyList(), events = emptyList(), languageBase = "en",
            jewish = listOf(
                WikidataClient.JewishConnection("Q100", "Schloss Ort", castle.point, person),
                WikidataClient.JewishConnection("Q200", "Gmunden", GeoPoint(47.92, 13.8), person),
            ),
        )
    }

    @Test
    fun withoutOptInTheBornHereNoteIsLeftOut() {
        val out = enriched().mapNotNull { it.forInterests(setOf(Topic.HISTORY)) }
        assertNull(out.firstOrNull { it.wikidataId == "Q200" }, "a place that was only the note is dropped")
        val castle = out.first { it.wikidataId == "Q100" }
        assertEquals("A lake castle.", castle.extract)
        assertTrue(PlaceFeature.JEWISH_HERITAGE !in castle.features && Topic.JEWISH !in castle.topics)
    }

    @Test
    fun withOptInTheConnectionIsTold() {
        val out = enriched().mapNotNull { it.forInterests(setOf(Topic.JEWISH)) }
        val castle = out.first { it.wikidataId == "Q100" }
        assertTrue(castle.extract!!.startsWith("Birthplace of: Ruth Example (Israeli writer)."))
        assertTrue(PlaceFeature.JEWISH_HERITAGE in castle.features && Topic.JEWISH in castle.topics)
        assertNotNull(out.firstOrNull { it.wikidataId == "Q200" })
    }

    @Test
    fun theAngleIsResearchedOnlyWhenChosenOrAskedFor() {
        assertTrue(Topic.JEWISH in Topic.OPT_IN)
        assertFalse(StoryAngle.JEWISH in AnglePlanner.ordered(setOf(Topic.HISTORY), null))
        assertTrue(StoryAngle.JEWISH in AnglePlanner.ordered(setOf(Topic.JEWISH), null))
        assertTrue(StoryAngle.JEWISH in AnglePlanner.ordered(emptySet(), Topic.JEWISH))
    }
}
