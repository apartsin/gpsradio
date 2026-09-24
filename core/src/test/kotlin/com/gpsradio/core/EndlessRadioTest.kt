package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.FillerRequest
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.AnglePlanner
import com.gpsradio.core.discovery.AngleResearch
import com.gpsradio.core.discovery.AngleScope
import com.gpsradio.core.discovery.AngleScout
import com.gpsradio.core.discovery.AngleTarget
import com.gpsradio.core.discovery.AreaFacet
import com.gpsradio.core.discovery.AreaFacetKind
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.discovery.StoryAngle
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.Topic
import com.gpsradio.core.session.AreaLabeler
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Non-stop radio never runs dry: the 50 angles, researched town → region → country (spec A §37). */
class EndlessRadioTest {
    private val here = GeoPoint(47.918, 13.799)
    private val area = AreaLabel("Gmunden", "Upper Austria", "AT")

    @Test
    fun catalogueHasFiftyDistinctAngles() {
        assertEquals(50, StoryAngle.entries.size)
        assertEquals(50, StoryAngle.entries.map { it.key }.toSet().size)
        assertTrue(StoryAngle.entries.all { it.label.isNotBlank() && it.hint.isNotBlank() && it.topics.isNotEmpty() })
    }

    @Test
    fun plannerGoesTownThenRegionThenCountryAndFollowsInterests() {
        val tried = mutableSetOf<String>()
        val order = generateSequence { AnglePlanner.next(area, setOf(Topic.FOOD), null, tried)?.also { tried += it.key } }.toList()
        // Every angle once in town, once in the region, and the country-scale ones for the country.
        assertEquals(50 + 50 + StoryAngle.entries.count { it.countryOk }, order.size)
        assertEquals(AngleScope.TOWN, order.first().scope)
        assertEquals("Gmunden", order.first().scopeName)
        assertTrue(order.first().angle!!.topics.contains(Topic.FOOD), "interests first: ${order.first().angle}")
        val firstRegion = order.indexOfFirst { it.scope == AngleScope.REGION }
        assertTrue(order.take(firstRegion).all { it.scope == AngleScope.TOWN })
        assertTrue(order.filter { it.scope == AngleScope.COUNTRY }.all { it.angle!!.countryOk && it.scopeName == "Austria" })
        assertNull(AnglePlanner.next(area, setOf(Topic.FOOD), null, tried))
    }

    @Test
    fun themeNarrowsAndAvoidedTopicsAreLeftOut() {
        val food = AnglePlanner.ordered(emptySet(), Topic.FOOD)
        assertTrue(food.isNotEmpty() && food.all { Topic.FOOD in it.topics })
        val noWar = AnglePlanner.ordered(emptySet(), null, avoid = setOf(Topic.WAR))
        assertTrue(StoryAngle.WAR_MEMORY !in noWar)
        // Consecutive angles vary instead of five history angles in a row.
        val all = AnglePlanner.ordered(emptySet(), null)
        assertTrue(all.zipWithNext().count { (a, b) -> a.topics == b.topics } < 10)
    }

    @Test
    fun scoutParsesFoundAndNotFound() {
        val facts = "The Traunsee is Austria's deepest lake at 191 metres. " + "It was carved by glaciers. ".repeat(4)
        assertEquals("Deepest lake" to facts.trim(), AngleScout.parse("""{"found":true,"title":"Deepest lake","facts":"$facts"}"""))
        assertNull(AngleScout.parse("""{"found":false,"title":"","facts":""}"""))
        assertNull(AngleScout.parse("""{"found":true,"title":"x","facts":"too short"}"""))
        assertNull(AngleScout.parse("not json"))
    }

    private class Radio(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore, AngleResearch {
        val aired = mutableListOf<String>()
        val researched = mutableListOf<AngleTarget>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest): Segment {
            aired += "STORY ${req.candidate.place.id}"
            return Segment("Story ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun narrateFiller(req: FillerRequest): Segment {
            aired += "${req.format} ${req.areaFacet?.title ?: ""}".trim()
            return Segment("Filler", null, "Filler", emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) = delay(30_000)
        override fun load(): String? = null
        override fun save(serialized: String) {}
        override suspend fun research(target: AngleTarget, area: AreaLabel?, point: GeoPoint?, alreadyTold: List<String>): AreaFacet? {
            researched += target
            delay(5_000)
            // Some angles have nothing specific here: the loop moves on.
            if (target.angle == StoryAngle.SKY_SCIENCE) return null
            return AreaFacet(target.scopeName, AreaFacetKind.OVERVIEW, "Facts. ".repeat(20), angle = target.angle?.key ?: "request", title = "${target.scope}:${target.angle?.key ?: target.custom}")
        }
    }

    @Test
    fun nonStopKeepsTalkingAfterThePlacesRunOut() = runTest {
        val r = Radio(listOf(place("castle", Geo.destination(here, 0.0, 150.0))))
        val s = RadioSession(
            places = r, narrator = r, speech = r, audio = r, historyStore = r,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY), pacing = Pacing.NONSTOP) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            areaLabeler = AreaLabeler { _ -> area },
            angleResearch = r,
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(60 * 60_000L); runCurrent()
        } finally {
            s.stop(); runCurrent()
        }
        val researchedStories = r.aired.count { it.startsWith("AREA TOWN:") }
        // One hour: the castle, then researched angles (rate-limited), never a long silence.
        assertTrue(r.aired.first() == "STORY castle", r.aired.toString())
        assertTrue(researchedStories >= 15, "researched stories: $researchedStories; ${r.aired}")
        assertTrue(r.researched.size <= RadioSession.ANGLE_LOOKUPS_PER_HOUR, "rate limit: ${r.researched.size}")
        assertEquals(r.researched.size, r.researched.map { it.key }.toSet().size, "each angle researched once")
        assertTrue(r.researched.first().angle!!.topics.contains(Topic.HISTORY), "interests first")
    }
}
