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
        // Top tier first (the listener's interests lift an angle one tier), random order within a tier.
        val town = order.filter { it.scope == AngleScope.TOWN }.map { AnglePlanner.tierFor(it.angle!!, setOf(Topic.FOOD)) }
        assertEquals(town.sorted(), town.filter { it == 1 } + town.filter { it == 2 } + town.filter { it == 3 })
        assertEquals(1, town.first())
        assertTrue(StoryAngle.DISHES.let { AnglePlanner.tierFor(it, setOf(Topic.FOOD)) } == 1)
        assertEquals(1, AnglePlanner.tierFor(StoryAngle.DRINKS, setOf(Topic.FOOD)), "an interest lifts tier 2 to 1")
        // Random order within a tier: two trips don't start the same way every time.
        val starts = (1..20).map { AnglePlanner.ordered(emptySet(), null, random = kotlin.random.Random(it)).take(3) }.toSet()
        assertTrue(starts.size > 5, "shuffled: ${starts.size}")
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
        // Tiers: 17 headliners, 16 strong, 17 for the curious.
        assertEquals(listOf(17, 16, 17), (1..3).map { t -> StoryAngle.entries.count { it.tier == t } })
    }

    @Test
    fun scoutParsesFoundAndNotFound() {
        val facts = "The Traunsee is Austria's deepest lake at 191 metres. " + "It was carved by glaciers. ".repeat(4)
        assertEquals("Deepest lake" to facts.trim(), AngleScout.parse("""{"found":true,"title":"Deepest lake","facts":"$facts","interest":5}"""))
        // "Only if interesting": a dull find (3/5) is not told.
        assertNull(AngleScout.parse("""{"found":true,"title":"Town hall","facts":"$facts","interest":3}"""))
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
        assertEquals(1, AnglePlanner.tierFor(r.researched.first().angle!!, setOf(Topic.HISTORY)), "top tier first")
    }

    @Test
    fun aSecondDayInTheSameTownDoesNotRepeatOrReResearch() = runTest {
        val store = object : HistoryStore {
            var data: String? = null
            override fun load() = data
            override fun save(serialized: String) { data = serialized }
        }
        val day = 24L * 3600 * 1000
        var offset = 0L
        fun session(r: Radio) = RadioSession(
            places = r, narrator = r, speech = r, audio = r, historyStore = store,
            config = { SessionConfig("ru-RU", setOf(Topic.HISTORY), pacing = Pacing.NONSTOP) },
            clock = { testScheduler.currentTime + 1_000_000 + offset },
            dispatcher = StandardTestDispatcher(testScheduler),
            areaLabeler = AreaLabeler { _ -> area },
            angleResearch = r,
        )
        suspend fun kotlinx.coroutines.test.TestScope.listen(r: Radio, minutes: Int) {
            val s = session(r)
            try {
                s.start(); runCurrent()
                s.onLocation(LocationSample(here.lat, here.lon, 5f, testScheduler.currentTime + 1_000_000 + offset, 0f)); runCurrent()
                advanceTimeBy(minutes * 60_000L); runCurrent()
            } finally {
                s.stop(); runCurrent()
            }
        }
        val day1 = Radio(listOf(place("castle", Geo.destination(here, 0.0, 150.0))))
        listen(day1, 30)
        // The next day, same town: a new session (app restarted), same stored history.
        offset += day
        val day2 = Radio(listOf(place("castle", Geo.destination(here, 0.0, 150.0))))
        listen(day2, 30)
        val keys1 = day1.researched.map { it.key }.toSet()
        val keys2 = day2.researched.map { it.key }.toSet()
        assertTrue(keys1.isNotEmpty() && keys2.isNotEmpty(), "day1=${keys1.size} day2=${keys2.size}")
        assertTrue(keys1.intersect(keys2).isEmpty(), "re-researched on day 2: ${keys1.intersect(keys2)}")
        val told1 = day1.aired.filter { it.startsWith("AREA") }.toSet()
        val told2 = day2.aired.filter { it.startsWith("AREA") }.toSet()
        assertTrue(told1.intersect(told2).isEmpty(), "repeated on day 2: ${told1.intersect(told2)}")
        assertTrue("STORY castle" !in day2.aired, "the castle was heard yesterday")
    }
}
