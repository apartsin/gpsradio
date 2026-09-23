package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.FillerRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.AreaInfoSource
import com.gpsradio.core.discovery.OnThisDayEvent
import com.gpsradio.core.discovery.OnThisDaySource
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.AreaLabeler
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.Programme
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Segment formats and pacing, end to end through [RadioSession] with virtual time. */
class ProgrammeSessionTest {
    private val here = GeoPoint(47.918, 13.799)
    private val facts = "The mill ground flour for the whole valley for three centuries, until the flood of 1899. ".repeat(4)

    private class Clip(val text: String, val startMs: Long, val endMs: Long)

    private inner class Fake(var places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        val clips = mutableListOf<Clip>()
        val radii = mutableListOf<Int>()
        val fillerRequests = mutableListOf<FillerRequest>()
        val asked = mutableListOf<ConversationRequest>()
        var extraWhenWide: List<PlaceCandidate> = emptyList()
        var hist: String? = null

        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String): List<PlaceCandidate> {
            radii += radiusM
            return places + if (radiusM > 1_500) extraWhenWide else emptyList()
        }
        override suspend fun narrate(req: NarrationRequest): Segment {
            val id = req.candidate.place.id
            return when (req.format) {
                SegmentFormat.QUIZ -> Segment("QUIZ $id", id, id, emptyList(), quizAnswer = "ANSWER $id")
                else -> Segment("${req.format} $id", id, id, emptyList())
            }
        }
        override suspend fun narrateFiller(req: FillerRequest): Segment {
            fillerRequests += req
            val text = when (req.format) {
                SegmentFormat.ON_THIS_DAY -> "ON_THIS_DAY ${req.event?.year}"
                SegmentFormat.AREA -> "AREA ${req.areaFacet?.id}"
                else -> "STATION_ID ${req.recap.size}"
            }
            return Segment(text, null, req.format.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
            asked += req
            return ConversationReply("Yes, well done: it's the Traunsee!")
        }
        override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle) = "Where are we heading?"
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.session(
        f: Fake,
        pacing: Pacing = Pacing.BALANCED,
        // Quiz mechanics stay covered here; the product ships with quizzes off (see Programme.Config.quizzes).
        programme: Programme = Programme(Programme.Config(quizzes = true)),
        onThisDay: OnThisDaySource? = null,
        areaInfo: AreaInfoSource? = null,
        area: AreaLabel? = null,
    ) = RadioSession(
        places = f, narrator = f, speech = f, historyStore = f,
        audio = AudioOutput { bytes ->
            val start = testScheduler.currentTime
            delay(10_000)
            f.clips += Clip(String(bytes), start, testScheduler.currentTime)
        },
        config = { SessionConfig("en-US", setOf(Topic.HISTORY), pacing = pacing) },
        areaLabeler = area?.let { a -> AreaLabeler { a } },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        programme = programme,
        onThisDay = onThisDay,
        areaInfo = areaInfo,
    )

    /** Always stops the session so a failed assertion cannot leave the scheduler loop running forever. */
    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try { s.start(); runCurrent(); body() } finally { s.stop(); runCurrent() }
    }

    private fun fix(speed: Float = 0f) = LocationSample(here.lat, here.lon, 5f, 1_000_000, speed, 0f)

    private fun strong(id: String, bearing: Double = 0.0, d: Double = 100.0) = place(id, Geo.destination(here, bearing, d))

    /** Below the speak threshold, but nearby with rich facts: bumper and quiz material. */
    private fun weak(id: String, bearing: Double = 90.0, d: Double = 300.0) =
        place(id, Geo.destination(here, bearing, d), relevance = 0.1, topics = emptySet()).copy(extract = facts, category = "museum")

    private fun isFiller(text: String) = text.substringBefore(' ').let { f ->
        SegmentFormat.entries.firstOrNull { it.name == f }?.isFiller == true
    }

    @Test
    fun fillersAirWhenNothingQualifiesAndNeverBackToBack() = runTest {
        val f = Fake(listOf(strong("a"), weak("w1"), weak("w2", bearing = 180.0)))
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5 * 60_000L); runCurrent()
            // The good story goes first; then one bumper about a weaker nearby place, and no second filler.
            assertEquals(2, f.clips.size, f.clips.map { it.text }.toString())
            assertEquals("STORY a", f.clips[0].text)
            val bumped = f.clips[1].text.removePrefix("BUMPER ")
            assertTrue(bumped in setOf("w1", "w2"), f.clips[1].text)
            val other = if (bumped == "w1") "w2" else "w1"
            // A new strong place turns up at the next area refresh: a story must air before the next filler,
            // which then uses the other weak place (never the same one twice).
            f.places = f.places + strong("b", bearing = 270.0, d = 120.0)
            advanceTimeBy(15 * 60_000L); runCurrent()
            assertEquals(listOf("STORY a", "BUMPER $bumped", "STORY b", "QUIZ $other", "ANSWER $other"), f.clips.map { it.text })
            val kinds = f.clips.filterNot { it.text.startsWith("ANSWER") }.map { isFiller(it.text) }
            assertTrue(kinds.zipWithNext().none { (x, y) -> x && y }, "two fillers in a row: ${f.clips.map { it.text }}")
            // Fillers wait for the full gap after the last segment (45–60 s here).
            assertTrue(f.clips[1].startMs - f.clips[0].endMs >= 45_000)
        }
    }

    @Test
    fun quizAnsweredInConversationIsNotRevealedAgain() = runTest {
        val f = Fake(listOf(strong("a"), weak("w1")))
        val s = session(f, programme = Programme(Programme.Config(bumperMinFactsChars = 10_000, quizzes = true)))
        running(s) {
            s.onLocation(fix()); runCurrent()
            var t = 0
            while (f.clips.none { it.text == "QUIZ w1" } && t++ < 300) { advanceTimeBy(1_000); runCurrent() }
            assertEquals(RadioState.CONVERSING, s.state.value.radioState)
            s.ask("Is it the Traunsee?"); runCurrent()
            advanceTimeBy(5 * 60_000L); runCurrent()
            assertEquals("ANSWER w1", f.asked.single().quiz?.answer)
            assertTrue(f.clips.none { it.text == "ANSWER w1" })
        }
    }

    @Test
    fun onThisDayPrefersAnEventAboutTheListenersCountry() = runTest {
        val f = Fake(listOf(strong("a")))
        val events = listOf(
            OnThisDayEvent(1861, "The first ascent of the Weisshorn in Switzerland."),
            OnThisDayEvent(1900, "A treaty is signed in Vienna, Austria."),
        )
        var asked: Triple<String, Int, Int>? = null
        val s = session(
            f,
            onThisDay = OnThisDaySource { lang, m, d -> asked = Triple(lang, m, d); events },
            area = AreaLabel("Gmunden", "Upper Austria", "AT"),
        )
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5 * 60_000L); runCurrent()
            assertEquals(listOf("STORY a", "ON_THIS_DAY 1900"), f.clips.map { it.text })
            assertEquals("en", asked?.first)
            assertEquals("Gmunden", f.fillerRequests.single().area?.city)
        }
    }

    @Test
    fun stationIdRecapsAfterTenStories() = runTest {
        val f = Fake((1..12).map { strong("p$it", bearing = it * 30.0, d = 100.0 + it * 5) })
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(15 * 60_000L); runCurrent()
            val texts = f.clips.map { it.text }
            val idAt = texts.indexOfFirst { it.startsWith("STATION_ID") }
            assertEquals(10, idAt, texts.toString())
            assertEquals("STATION_ID 10", texts[idAt])
            assertTrue(texts.drop(idAt + 1).first().startsWith("STORY"))
        }
    }

    @Test
    fun drivingKeepsAtLeastNinetySecondsBetweenSegmentsEvenWhenChatty() = runTest {
        val f = Fake((1..4).map { strong("r$it", bearing = 0.0, d = 500.0 * it) })
        val s = session(f, pacing = Pacing.CHATTY)
        running(s) {
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            s.onLocation(fix(speed = 20f)); runCurrent()
            advanceTimeBy(12 * 60_000L); runCurrent()
            assertTrue(f.clips.size >= 4, f.clips.map { it.text }.toString())
            f.clips.zipWithNext().forEach { (x, y) ->
                assertTrue(y.startMs - x.endMs >= 90_000, "only ${(y.startMs - x.endMs) / 1000} s between ${x.text} and ${y.text}")
            }
        }
    }

    @Test
    fun nonstopKeepsTalkingWithDistinctGroundedSegmentsAndShortGaps() = runTest {
        // Nothing qualifies even for non-stop: far, thin-relevance places only.
        fun faint(id: String, bearing: Double, d: Double = 1_400.0) =
            place(id, Geo.destination(here, bearing, d), relevance = 0.1, topics = emptySet()).copy(category = "museum")
                .copy(sourceConfidence = 0.3, extract = facts)
        val f = Fake(listOf(faint("v1", 0.0), faint("v2", 120.0), faint("v3", 240.0)))
        f.extraWhenWide = listOf(faint("far1", 60.0, d = 2_500.0))
        val article = WikipediaClient.Article(
            "Gmunden",
            "Gmunden is a lakeside town. ${"It is known for ceramics and its castle in the lake. ".repeat(5)}\n" +
                "== History ==\n${"Salt was shipped from here for centuries. ".repeat(6)}\n" +
                "== Notable people ==\n${"Brahms spent summers in the town. ".repeat(7)}\n",
            "https://en.wikipedia.org/wiki/Gmunden",
        )
        val s = session(
            f,
            pacing = Pacing.NONSTOP,
            onThisDay = { _, _, _ -> listOf(OnThisDayEvent(1900, "A treaty is signed in Vienna, Austria.")) },
            areaInfo = { _, title -> if (title == "Gmunden") article else null },
            area = AreaLabel("Gmunden", "Upper Austria", "AT"),
        )
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(10 * 60_000L); runCurrent()
            val texts = f.clips.map { it.text }
            // (1) weaker places as full stories, (2) area facets, (3) on this day, (4) a wider search finds more.
            assertEquals(
                listOf(
                    "STORY v1", "STORY v2", "STORY v3",
                    "AREA Gmunden#overview", "AREA Gmunden#history", "AREA Gmunden#people",
                    "ON_THIS_DAY 1900", "STORY far1",
                ),
                texts.take(8).let { first -> first.take(3).sorted() + first.drop(3) },
            )
            assertEquals(texts.size, texts.toSet().size, "a segment was repeated: $texts")
            assertTrue(f.radii.any { it > 1_500 }, "the search was never widened: ${f.radii}")
            f.clips.zipWithNext().forEach { (x, y) ->
                assertTrue(y.startMs - x.endMs <= 10_000, "${(y.startMs - x.endMs) / 1000} s of silence between ${x.text} and ${y.text}")
            }
            // Area facts come from the fetched article; told facets are passed on so they aren't repeated.
            val areaReqs = f.fillerRequests.filter { it.format == SegmentFormat.AREA }
            assertTrue("Salt was shipped" in areaReqs[1].areaFacet!!.facts)
            assertEquals(listOf("Gmunden#overview"), areaReqs[1].areaToldFacets)
        }
    }
}
