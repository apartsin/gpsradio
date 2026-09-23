package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.FillerRequest
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.events.EventScout
import com.gpsradio.core.events.EventSource
import com.gpsradio.core.events.LocalEvent
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Events today nearby (spec A §30): found, filtered for visitors, announced once, in time. */
class LocalEventsTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val now = LocalDateTime.parse("2026-09-23T15:00").atZone(zone).toInstant().toEpochMilli()
    private val here = GeoPoint(47.918, 13.799)

    private fun ev(title: String, start: String, category: String = "concert", url: String = "https://example.org/$title", end: String = "", km: Double = 2.0) =
        """{"title":${JsonPrimitive(title)},"category":"$category","venue":"Esplanade","start_local":"$start","end_local":"$end","url":"$url","why":"Open-air","distance_km":$km}"""

    @Test
    fun keepsOnlyVisitorWorthyLinkedNearbyEventsInTodaysWindow() {
        val json = """{"events":[
            ${ev("Jazz on the Lake", "2026-09-23T20:00")},
            ${ev("Sunset Yoga class", "2026-09-23T18:00", category = "other")},
            ${ev("Farmers market", "2026-09-23T13:00", category = "market", end = "2026-09-23T18:00")},
            ${ev("No link gig", "2026-09-23T19:00", url = "")},
            ${ev("Tomorrow's festival", "2026-09-24T10:00", category = "festival")},
            ${ev("Morning concert", "2026-09-23T09:00")},
            ${ev("Far away fireworks", "2026-09-23T21:00", category = "fireworks", km = 80.0)},
            ${ev("Business networking evening", "2026-09-23T19:00", category = "other")},
            ${ev("Jazz on the Lake", "2026-09-23T20:00")}
        ]}"""
        val events = EventScout.parse(json, now, zone)
        assertEquals(listOf("Farmers market", "Jazz on the Lake"), events.map { it.title })
        assertEquals(LocalDateTime.parse("2026-09-23T20:00").atZone(zone).toInstant().toEpochMilli(), events[1].startMs)
        assertEquals("20:00", EventScout.clock(events[1].startMs, zone))
        assertEquals("Today nearby: right now, Farmers market at Esplanade; at 20:00, Jazz on the Lake at Esplanade.", EventScout.spoken(events, now, zone))
        assertTrue(EventScout.parse("not json", now, zone).isEmpty())
    }

    @Test
    fun relevanceFilterRejectsClassesInSeveralLanguages() {
        fun e(t: String) = LocalEvent(t, "other", "", now, null, "https://x")
        assertFalse(EventScout.isRelevant(e("Pilates for beginners")))
        assertFalse(EventScout.isRelevant(e("Йога в парке")))
        assertFalse(EventScout.isRelevant(e("Cooking course")))
        assertTrue(EventScout.isRelevant(e("Harbour festival")))
        assertFalse(EventScout.isRelevant(e("Harbour festival").copy(category = "yoga")))
    }

    @Test
    fun scoutUsesWebSearchWithAStrictSchema() = runBlocking {
        val server = MockWebServer()
        val text = """{"events":[${ev("Jazz on the Lake", "2026-09-23T20:00")}]}"""
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}]}"""))
        server.start()
        try {
            val scout = EventScout(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() })
            val found = scout.find(AreaLabel("Gmunden", "Upper Austria", "AT"), here, now, zone, "ru-RU")
            assertEquals(listOf("Jazz on the Lake"), found.map { it.title })
            val body = server.takeRequest().body.readUtf8()
            assertTrue("\"web_search\"" in body && "\"local_events\"" in body && "\"strict\":true" in body)
            assertTrue("2026-09-23T15:00" in body && "Gmunden" in body, body.take(400))
            assertTrue("output_language" in body && "(ru-RU)" in body)
            assertTrue("Exclude: classes" in EventScout.INSTRUCTIONS)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun eventsSegmentPromptAndFallback() = runBlocking {
        val e = LocalEvent("Jazz on the Lake", "concert", "Esplanade", now + 5 * 3_600_000L, null, "https://example.org/jazz", "Open-air")
        val instr = RadioAgent.narrationInstructions("en-US")
        assertTrue("format \"events\"" in instr && "never invent prices" in instr)
        val plain = object : Narrator {
            override suspend fun narrate(req: NarrationRequest) = Segment("", null, "", emptyList())
            override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("")
        }
        val seg = plain.narrateFiller(FillerRequest(SegmentFormat.EVENTS, "en", null, events = listOf(e), nowMs = now, zone = zone))
        assertEquals("Today nearby: at 20:00, Jazz on the Lake at Esplanade.", seg.text)
        assertEquals("https://example.org/jazz", seg.sources.single().url)
        assertTrue(RadioAgent.targetSeconds(TravelMode.DRIVING, SegmentFormat.EVENTS) <= 30)
    }

    @Test
    fun programmeAnnouncesDueEventsEvenWhenAStoryIsReady() {
        val p = Programme()
        val s = Programme.Situation(
            nowMs = now, mode = TravelMode.WALKING, pacing = Pacing.BALANCED, minGapMs = 45_000, lastSpeechEndMs = now - 120_000,
            storyReady = true, ranked = emptyList(), recentTitles = emptyList(), onThisDayAvailable = false, dayKey = "09-23", eventsDue = true,
        )
        assertEquals(Programme.Plan.Filler(SegmentFormat.EVENTS), p.next(s))
        assertEquals(Programme.Plan.None, p.next(s.copy(eventsDue = false)))
    }

    // ---- session ---------------------------------------------------------------------------

    private class Fake : PlacesProvider, Narrator, SpeechService, HistoryStore {
        val clips = mutableListOf<String>()
        val fillers = mutableListOf<FillerRequest>()
        var hist: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) =
            listOf(place("castle", Geo.destination(center, 0.0, 150.0)).copy(extract = "Castle facts. ".repeat(30)))
        override suspend fun narrate(req: NarrationRequest) = Segment("STORY ${req.candidate.place.id}", req.candidate.place.id, req.candidate.place.name, emptyList())
        override suspend fun narrateFiller(req: FillerRequest): Segment {
            fillers += req
            return Segment("EVENTS " + req.events.joinToString { it.title }, null, "Today nearby", emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    @Test
    fun sessionFindsEventsAnnouncesThemFirstAndOnlyOnce() = runTest {
        val f = Fake()
        var searches = 0
        val base = now
        val scout = EventSource { _, _, nowMs, _, _ ->
            searches++
            listOf(LocalEvent("Jazz on the Lake", "concert", "Esplanade", nowMs + 2 * 3_600_000L, null, "https://example.org/jazz", "Open-air"))
        }
        val s = RadioSession(
            places = f, narrator = f, speech = f, historyStore = f,
            audio = AudioOutput { bytes -> f.clips += String(bytes); delay(10_000) },
            config = { SessionConfig("en-US", setOf(Topic.HISTORY), localEvents = true, askAboutTrip = false) },
            clock = { testScheduler.currentTime + base },
            dispatcher = StandardTestDispatcher(testScheduler),
            areaLabeler = AreaLabeler { AreaLabel("Gmunden", "Upper Austria", "AT") },
            eventScout = scout,
            zone = { zone },
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, base, 0f)); runCurrent()
            var t = 0
            while (t++ < 600) { advanceTimeBy(1_000); runCurrent() }
            assertEquals(1, searches, "one search per area per 3 h")
            assertEquals("Jazz on the Lake", s.state.value.todayEvents.single().title)
            assertEquals(1, f.clips.count { it.startsWith("EVENTS") }, "announced once: ${f.clips}")
            assertTrue(f.clips.indexOfFirst { it.startsWith("EVENTS") } <= 1, "time-sensitive: goes out early: ${f.clips}")
            assertEquals(zone, f.fillers.single().zone)
        } finally {
            s.stop(); runCurrent()
        }
    }
}
