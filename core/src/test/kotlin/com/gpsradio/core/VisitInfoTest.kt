package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.PlaceFeature
import com.gpsradio.core.model.RoadTripKind
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import com.gpsradio.core.visit.OpeningHours
import com.gpsradio.core.visit.VisitInfo
import com.gpsradio.core.visit.VisitScout
import com.gpsradio.core.visit.VisitSource
import com.gpsradio.core.visit.Visits
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hours, admission and what a visit/detour involves (spec A §31), and prompt caching (spec B §40). */
class VisitInfoTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val wed = LocalDateTime.parse("2026-09-23T15:00") // a Wednesday
    private val now = wed.atZone(zone).toInstant().toEpochMilli()
    private val here = GeoPoint(47.80, 13.70)

    @Test
    fun openingHoursCommonFormsAndUnknownOnes() {
        assertEquals(listOf("09:00" to "17:00"), OpeningHours.today("Mo-Fr 09:00-17:00; Sa 10:00-14:00", wed))
        assertEquals(emptyList(), OpeningHours.today("Mo-Fr 09:00-17:00; We off", wed), "later rule wins")
        assertEquals(emptyList(), OpeningHours.today("Sa,Su 10:00-18:00", wed), "closed on other days")
        assertEquals(listOf("10:00" to "12:00", "13:00" to "18:00"), OpeningHours.today("Tu-Su 10:00-12:00,13:00-18:00", wed))
        assertEquals(listOf("00:00" to "24:00"), OpeningHours.today("24/7", wed))
        assertEquals(listOf("08:00" to "20:00"), OpeningHours.today("08:00-20:00", wed))
        assertEquals(listOf("18:00" to "02:00"), OpeningHours.today("Fr-We 18:00-02:00", wed), "wrapping day range")
        assertNull(OpeningHours.today("Mo-Fr 09:00-17:00; PH off", wed), "holidays aren't understood: don't guess")
        assertNull(OpeningHours.today("sunrise-sunset", wed))
        assertTrue(OpeningHours.openAt(listOf("09:00" to "17:00"), LocalTime.of(15, 0)))
        assertFalse(OpeningHours.openAt(listOf("09:00" to "17:00"), LocalTime.of(17, 0)))
    }

    @Test
    fun osmTagsBecomeHoursAndFee() {
        val museum = place("m", here).copy(category = "tourism: museum", openingHours = "Tu-Su 10:00-18:00", fee = "adults €8")
        val v = Visits.fromOsm(museum, now, zone)!!
        assertEquals(true, v.openToday)
        assertEquals("10:00–18:00", v.hoursToday)
        assertEquals("osm", v.source)
        assertEquals("open 10:00–18:00 · adults €8", v.summary())
        assertNull(Visits.fromOsm(place("x", here), now, zone))

        val svc = DiscoveryService(WikipediaClient(OkHttpClient(), "ua"), OverpassClient(OkHttpClient(), "ua"))
        val merged = svc.merge(emptyList(), listOf(
            OverpassClient.Element("osm:node/1", here, mapOf("tourism" to "museum", "name" to "Salt Museum", "opening_hours" to "Mo-Su 09:00-17:00", "fee" to "yes")),
            OverpassClient.Element("osm:node/2", Geo.destination(here, 0.0, 500.0), mapOf("historic" to "castle", "name" to "Burg", "charge" to "5 EUR")),
        ), "en")
        assertEquals("Mo-Su 09:00-17:00", merged[0].openingHours)
        assertEquals("paid entry", merged[0].fee)
        assertEquals("5 EUR", merged[1].fee)
    }

    @Test
    fun whatIsWorthCheckingOnline() {
        assertTrue(Visits.worthChecking(place("a", here).copy(category = "museum"), null))
        assertTrue(Visits.worthChecking(place("b", here).copy(category = "memorial", features = setOf(PlaceFeature.EAT_DRINK)), null))
        assertTrue(Visits.worthChecking(place("c", here).copy(category = "memorial"), RoadTripKind.WORTH_A_STOP))
        assertFalse(Visits.worthChecking(place("d", here).copy(category = "natural: peak"), null), "no tickets for a mountain view")
        assertFalse(Visits.worthChecking(place("e", here).copy(category = "memorial"), RoadTripKind.VISIBLE))
    }

    private fun visitJson(open: String = "true", hours: String = "10:00–17:00", fee: String = "adults €8", url: String = "https://example.org/abbey") =
        """{"open_today":$open,"hours_today":"$hours","admission":"$fee","visit_type":"visit","visit_minutes":45,"walk_effort":"easy","walk_note":"5 min from the car park","expect":"Baroque library, café","source_url":"$url"}"""

    @Test
    fun webCheckIsParsedAndUnlinkedHoursArentTrusted() {
        val v = VisitScout.parse(visitJson(), now)!!
        assertEquals(true, v.openToday)
        assertEquals("open 10:00–17:00 · adults €8 · ~45 min visit · easy walk", v.summary())
        val unlinked = VisitScout.parse(visitJson(url = ""), now)!!
        assertNull(unlinked.hoursToday)
        assertNull(unlinked.admission)
        assertNull(unlinked.openToday)
        assertEquals(45, unlinked.visitMinutes, "time to spend and effort are general advice, kept")
        assertEquals("closed today", VisitScout.parse(visitJson(open = "false", hours = ""), now)!!.summary().substringBefore(" ·"))
        assertNull(VisitScout.parse("nope", now))
    }

    private fun respond(text: String, cached: Int = 0) = MockResponse().setBody(
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}],""" +
            """"usage":{"input_tokens":3200,"input_tokens_details":{"cached_tokens":$cached},"output_tokens":90}}""",
    )

    @Test
    fun scoutRequestAndNarrationContextWithCacheKeys() = runBlocking {
        val server = MockWebServer()
        server.enqueue(respond(visitJson()))
        server.enqueue(respond("Story.", cached = 3072))
        server.start()
        try {
            val openAi = OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString())
            val abbey = place("abbey", Geo.destination(here, 0.0, 300.0), name = "Lambach Abbey").copy(category = "monastery")
            val v = VisitScout(openAi, { ModelConfig() }).lookup(abbey, null, now, zone)!!
            val scoutBody = server.takeRequest().body.readUtf8()
            assertTrue("\"web_search\"" in scoutBody && "\"visit_info\"" in scoutBody && "\"prompt_cache_key\":\"gpsradio-visit\"" in scoutBody)
            assertTrue("weekday\\\":\\\"wednesday" in scoutBody, scoutBody.take(600))

            val agent = RadioAgent(openAi, { ModelConfig() }, structuredNarration = false)
            val loc = LocationContext(here, 5f, now, 22.0, 0.0, TravelMode.DRIVING)
            val c = EditorialRanker().rank(listOf(abbey), EditorialRanker.Context(loc, mapOf(Topic.HISTORY to 1.0), HeardHistory(), now)).single()
            agent.narrate(NarrationRequest(c, loc, "ru-RU", setOf(Topic.HISTORY), emptyList(), visit = v, detourMinutes = 6))
            val body = server.takeRequest().body.readUtf8()
            assertTrue("\"prompt_cache_key\":\"gpsradio-narr-ru-RU-entertaining\"" in body, body.take(300))
            assertTrue("hours_today\\\":\\\"10:00–17:00" in body && "time_to_spend_min\\\":45" in body && "detour_minutes\\\":6" in body)
            assertTrue("spoken_language" in body && "(ru-RU)" in body)
            val instr = RadioAgent.narrationInstructions("en-US")
            assertTrue("don't guess" in instr, "no-guess rule")
            assertTrue("how long to spend" in instr, "detour details rule")
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun cachedTokensAreReported() {
        val r = OpenAiClient.parseResponse(
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"x","annotations":[]}]}],"usage":{"input_tokens":4000,"input_tokens_details":{"cached_tokens":3584}}}""",
        )
        assertEquals(3584, r.cachedTokens)
        assertEquals(4000, r.inputTokens)
    }

    // ---- session: a detour story carries today's checked details; checked once per day --------

    private class Fake(val list: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        val requests = mutableListOf<NarrationRequest>()
        var hist: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun narrate(req: NarrationRequest): Segment {
            requests += req
            return Segment("${req.candidate.place.name}. Want me to navigate there?", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.drive(s: RadioSession, from: Int, seconds: Int) {
        for (t in from until from + seconds) {
            val p = Geo.destination(here, 0.0, 20.0 * t)
            s.onLocation(LocationSample(p.lat, p.lon, 5f, now + t * 1_000L, 20f, 0f))
            advanceTimeBy(1_000); runCurrent()
        }
    }

    @Test
    fun detourStoryIncludesTodaysVisitDetailsAndTheCardShowsThem() = runTest {
        val abbey = place("abbey", Geo.destination(Geo.destination(here, 0.0, 4_000.0), 90.0, 1_200.0), relevance = 0.95, name = "Lambach Abbey")
            .copy(category = "historic: monastery", extract = "Lambach Abbey is a Benedictine monastery founded in 1056. ".repeat(4))
        val f = Fake(listOf(abbey))
        var lookups = 0
        val scout = VisitSource { p, _, t, _ ->
            lookups++
            VisitInfo(true, "10:00–17:00", "adults €8", "visit", 45, "easy", "5 min from the car park", "Baroque library", "https://example.org", "web", t)
        }
        val s = RadioSession(
            places = f, narrator = f, speech = f, audio = AudioOutput { delay(3_000) }, historyStore = f,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY), askAboutTrip = false) },
            clock = { testScheduler.currentTime + now },
            dispatcher = StandardTestDispatcher(testScheduler),
            visitScout = scout,
            zone = { zone },
        )
        try {
            s.start(); runCurrent()
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            var t = 0
            while (f.requests.isEmpty() && t < 90) { drive(s, t, 1); t++ }
            val req = f.requests.first()
            assertEquals("adults €8", req.visit?.admission)
            assertTrue((req.detourMinutes ?: 0) in 1..15)
            drive(s, t, 30)
            assertEquals(1, lookups, "one web check per place per day")
            val card = s.state.value.detours.firstOrNull()
            if (card != null) assertEquals("open 10:00–17:00 · adults €8 · ~45 min visit · easy walk", card.visit)
        } finally {
            s.stop(); runCurrent()
        }
    }
}
