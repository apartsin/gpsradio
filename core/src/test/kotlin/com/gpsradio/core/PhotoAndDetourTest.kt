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
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.Detours
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.editorial.PhotoSpots
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.RoadTripKind
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.OfferKind
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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoAndDetourTest {
    private val here = GeoPoint(47.80, 13.70)

    private fun rc(p: PlaceCandidate, loc: LocationContext, roadTrip: RoadTripKind? = null) = RankedCandidate(
        p, Geo.distanceM(loc.point, p.point), Geo.bearingDeg(loc.point, p.point), 2.0,
        ScoreBreakdown(0.8, 0.5, 1.0, 0.6, 0.5, 0.85, 0.0, 0.0), roadTrip,
    )

    private fun walking(t: Long = 0) = LocationContext(here, 5f, t, 1.3, 0.0, TravelMode.WALKING)
    private fun driving() = LocationContext(here, 5f, 0, 25.0, 0.0, TravelMode.DRIVING)

    @Test
    fun photogenicPlacesAreRecognised() {
        assertTrue(PhotoSpots.isPhotogenic(place("v", here).copy(category = "tourism: viewpoint")))
        assertTrue(PhotoSpots.isPhotogenic(place("w", here, name = "Traunfall waterfall")))
        assertTrue(PhotoSpots.isPhotogenic(place("c", here, name = "Schloss Ort")))
        assertFalse(PhotoSpots.isPhotogenic(place("o", here, name = "Tax office").copy(category = "office")))
        assertTrue(PhotoSpots.isViewpoint(place("l", here, name = "Grünberg lookout")))
    }

    @Test
    fun walkingSuggestsCloseSpotsAndDrivingOnlyViewpointsJustOffTheRoadAhead() {
        val w = walking()
        val castleNear = place("c", Geo.destination(here, 90.0, 300.0), name = "Schloss Ort")
        val castleFar = place("f", Geo.destination(here, 90.0, 2_000.0), name = "Castle far")
        assertTrue(PhotoSpots.suitable(rc(castleNear, w), w))
        assertFalse(PhotoSpots.suitable(rc(castleFar, w), w))

        val d = driving()
        val viewAhead = place("v", Geo.destination(Geo.destination(here, 0.0, 4_000.0), 90.0, 500.0)).copy(category = "tourism: viewpoint")
        val viewBehind = place("b", Geo.destination(here, 180.0, 3_000.0)).copy(category = "tourism: viewpoint")
        val castleAhead = place("k", Geo.destination(here, 0.0, 3_000.0), name = "Castle ahead")
        assertTrue(PhotoSpots.suitable(rc(viewAhead, d), d))
        assertFalse(PhotoSpots.suitable(rc(viewBehind, d), d), "behind the car")
        assertFalse(PhotoSpots.suitable(rc(castleAhead, d), d), "driving: designated viewpoints only (safe to stop)")
    }

    @Test
    fun sunPositionAndLightHints() {
        // Summer solstice, solar noon in Austria (~11:05 UTC at 13.7°E): sun high in the south.
        val noon = PhotoSpots.sun(here, Instant.parse("2026-06-21T11:05:00Z").toEpochMilli())
        assertTrue(abs(noon.elevationDeg - 65.6) < 2.0, "elevation ${noon.elevationDeg}")
        assertTrue(abs(noon.azimuthDeg - 180.0) < 6.0, "azimuth ${noon.azimuthDeg}")
        val night = PhotoSpots.sun(here, Instant.parse("2026-06-21T23:00:00Z").toEpochMilli())
        assertTrue(night.elevationDeg < -6)
        assertTrue(PhotoSpots.lightHint(night, 0.0).startsWith("after dark"))
        val evening = PhotoSpots.Sun(azimuthDeg = 290.0, elevationDeg = 5.0)
        assertTrue(PhotoSpots.lightHint(evening, 110.0).startsWith("golden hour, the sun is behind you"))
        assertTrue("backlight" in PhotoSpots.lightHint(evening, 280.0))
        assertTrue("side light" in PhotoSpots.lightHint(evening, 200.0))
    }

    @Test
    fun detourMinutesAndList() {
        val d = driving()
        val off = place("s", Geo.destination(Geo.destination(here, 0.0, 3_000.0), 90.0, 1_500.0), relevance = 0.9, name = "Abbey")
        assertEquals(4, Detours.minutes(d, off.point)) // 3 km there and back at 50 km/h ≈ 3.6 min
        assertEquals("about 4 min detour", Detours.label(4))
        assertEquals("right by the road", Detours.label(1))
        val far = place("x", Geo.destination(here, 90.0, 20_000.0), relevance = 0.9)
        val list = Detours.ahead(listOf(rc(off, d, RoadTripKind.WORTH_A_STOP), rc(far, d, RoadTripKind.WORTH_A_STOP), rc(off.copy(id = "v"), d, RoadTripKind.VISIBLE)), d)
        assertEquals(listOf("s" to 4), list.map { it.first.place.id to it.second })
        assertTrue(Detours.ahead(list.map { it.first }, walking()).isEmpty(), "only while driving")
    }

    @Test
    fun programmeRotatesInAPhotoTipAtMostEveryFifteenMinutes() {
        val p = Programme()
        val w = walking()
        val spot = rc(place("c", Geo.destination(here, 90.0, 300.0), name = "Schloss Ort"), w)
        fun sit(now: Long) = Programme.Situation(
            nowMs = now, mode = TravelMode.WALKING, pacing = Pacing.BALANCED, minGapMs = 45_000, lastSpeechEndMs = now - 120_000,
            storyReady = false, ranked = emptyList(), recentTitles = emptyList(), onThisDayAvailable = false, dayKey = "09-23",
            photoSpot = spot,
        )
        val t0 = 10_000_000L
        assertEquals(Programme.Plan.Filler(SegmentFormat.PHOTO_TIP, spot), p.next(sit(t0)))
        p.onFillerAired(SegmentFormat.PHOTO_TIP, "c", t0, "09-23")
        p.onStoryAired()
        assertEquals(Programme.Plan.None, p.next(sit(t0 + 10 * 60_000)), "same spot is not suggested twice")
        // A different spot: not within 15 minutes of the last tip, but after that.
        val other = rc(place("w", Geo.destination(here, 180.0, 200.0), name = "Old Bridge"), w)
        fun sitOther(now: Long) = sit(now).copy(photoSpot = other)
        assertTrue(p.next(sitOther(t0 + 10 * 60_000)) !is Programme.Plan.Filler || (p.next(sitOther(t0 + 10 * 60_000)) as Programme.Plan.Filler).format != SegmentFormat.PHOTO_TIP)
        p.onStoryAired()
        assertEquals(Programme.Plan.Filler(SegmentFormat.PHOTO_TIP, other), p.next(sitOther(t0 + 16 * 60_000)))
        assertTrue(SegmentFormat.PHOTO_TIP.isFiller)
        assertTrue(RadioAgent.targetSeconds(TravelMode.DRIVING, SegmentFormat.PHOTO_TIP) <= 30)
    }

    @Test
    fun nonStopStillMakesPhotoStopsAlthoughAreaStoriesAreAlwaysAvailable() {
        val p = Programme()
        val w = walking()
        val spot = rc(place("c", Geo.destination(here, 90.0, 300.0), name = "Schloss Ort"), w)
        val facet = com.gpsradio.core.discovery.AreaFacet("Gmunden", com.gpsradio.core.discovery.AreaFacetKind.OVERVIEW, "Facts. ".repeat(40), angle = "water", title = "Lake")
        fun sit(now: Long, photo: RankedCandidate? = spot) = Programme.Situation(
            nowMs = now, mode = TravelMode.WALKING, pacing = Pacing.NONSTOP, minGapMs = 2_000, lastSpeechEndMs = now - 10_000,
            storyReady = false, ranked = emptyList(), recentTitles = emptyList(), onThisDayAvailable = false, dayKey = "09-23",
            photoSpot = photo, areaFacets = listOf(facet),
        )
        val t0 = 10_000_000L
        assertEquals(Programme.Plan.Filler(SegmentFormat.PHOTO_TIP, spot), p.next(sit(t0)))
        p.onFillerAired(SegmentFormat.PHOTO_TIP, "c", t0, "09-23")
        // Then the endless loop goes on; the next photo stop waits its 15 minutes.
        val other = rc(place("w", Geo.destination(here, 180.0, 200.0), name = "Old Bridge"), w)
        assertEquals(SegmentFormat.AREA, (p.next(sit(t0 + 60_000, other)) as Programme.Plan.Filler).format)
        assertEquals(Programme.Plan.Filler(SegmentFormat.PHOTO_TIP, other), p.next(sit(t0 + 16 * 60_000, other)))
    }

    @Test
    fun photoTipPromptCarriesLightAndSafetyRules() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive("Photo tip: the castle from the pier.")},"annotations":[]}]}]}"""))
        server.start()
        try {
            val agent = RadioAgent(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() }, structuredNarration = false)
            val loc = walking(Instant.parse("2026-09-23T17:00:00Z").toEpochMilli())
            val c = EditorialRanker().rank(
                listOf(place("wiki:en:1", Geo.destination(here, 90.0, 250.0), name = "Schloss Ort").copy(extract = "Schloss Ort stands on an island in the lake.")),
                EditorialRanker.Context(loc, mapOf(Topic.HISTORY to 1.0), HeardHistory(), loc.timestampMs),
            ).single()
            val seg = agent.narrate(NarrationRequest(c, loc, "en-US", setOf(Topic.HISTORY), emptyList(), format = SegmentFormat.PHOTO_TIP))
            assertEquals("Photo tip: the castle from the pier.", seg.text)
            val body = server.takeRequest().body.readUtf8()
            assertTrue("format\\\":\\\"photo_tip" in body && "\\\"light\\\":" in body, body.take(600))
            val instr = RadioAgent.narrationInstructions("en-US", HostStyle.ENTERTAINING)
            assertTrue("photo_tip" in instr && "never suggest taking photos while driving" in instr)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    // ---- session: a worth-a-stop story ends with a detour offer; yes opens navigation ----------

    private class Fake(val list: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        var hist: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun narrate(req: NarrationRequest) =
            Segment("${req.candidate.place.name} is a lovely abbey. Want me to navigate there?", req.candidate.place.id, req.candidate.place.name, emptyList())
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.drive(s: RadioSession, seconds: Int, speed: Float = 20f) {
        for (t in 0 until seconds) {
            val p = Geo.destination(here, 0.0, speed * t.toDouble())
            s.onLocation(LocationSample(p.lat, p.lon, 5f, 1_000_000 + t * 1_000L, speed, 0f))
            advanceTimeBy(1_000); runCurrent()
        }
    }

    @Test
    fun worthAStopStoryOffersADetourAndYesOpensNavigation() = runTest {
        val abbey = place("abbey", Geo.destination(Geo.destination(here, 0.0, 4_000.0), 90.0, 1_200.0), relevance = 0.95, name = "Lambach Abbey")
            .copy(category = "historic: monastery", extract = "Lambach Abbey is a Benedictine monastery founded in 1056. ".repeat(4))
        val f = Fake(listOf(abbey))
        val navigated = mutableListOf<String>()
        val s = RadioSession(
            places = f, narrator = f, speech = f, audio = AudioOutput { delay(3_000) }, historyStore = f,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY), askAboutTrip = false) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            onNavigate = { navigated += it.id },
        )
        try {
            s.start(); runCurrent()
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            var t = 0
            while (s.state.value.pendingOfferKind != OfferKind.DETOUR && t < 90) { drive(s, 1); t++ }
            assertEquals(OfferKind.DETOUR, s.state.value.pendingOfferKind, "transcript: ${s.state.value.transcript.map { it.text }}")
            assertEquals("Lambach Abbey", s.state.value.pendingOffer)
            s.answerOffer(true); runCurrent()
            assertEquals(listOf("abbey"), navigated)
            assertNull(s.state.value.pendingOffer)
            assertTrue(s.state.value.transcript.any { "Directions to Lambach Abbey" in it.text })
        } finally {
            s.stop(); runCurrent()
        }
    }
}

/** Spec A §47: on a non-stop drive, detour stories and photo stops still happen between the regular stories. */
class DrivingExtrasTest {
    private val here = GeoPoint(47.80, 13.70)

    private class Radio(val places: List<PlaceCandidate>) : com.gpsradio.core.discovery.PlacesProvider, com.gpsradio.core.ai.Narrator,
        com.gpsradio.core.session.SpeechService, com.gpsradio.core.session.AudioOutput, com.gpsradio.core.session.HistoryStore {
        val told = mutableListOf<Pair<String, String>>() // place id to "format/roadTrip"
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest): com.gpsradio.core.ai.Segment {
            told += req.candidate.place.id to "${req.format}/${req.roadTrip}"
            return com.gpsradio.core.ai.Segment("Story ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: com.gpsradio.core.ai.ConversationRequest, onSearching: suspend () -> Unit) =
            com.gpsradio.core.ai.ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) { kotlinx.coroutines.delay(20_000) }
        override fun load(): String? = null
        override fun save(serialized: String) {}
    }

    @Test
    fun aNonStopDriveMakesADetourStoryAndAPhotoStop() = kotlinx.coroutines.test.runTest {
        val road = (1..12).map { i -> place("r$i", Geo.destination(here, 3.0, i * 700.0), relevance = 0.6, name = "Roadside $i") }
        val castle = place("castle", Geo.destination(Geo.destination(here, 0.0, 3_000.0), 90.0, 1_200.0), relevance = 0.95, name = "Burg Wildenstein")
        val view = place("view", Geo.destination(Geo.destination(here, 0.0, 5_000.0), 90.0, 400.0), name = "Grünberg lookout")
            .copy(category = "tourism: viewpoint")
        val r = Radio(road + castle + view)
        val s = com.gpsradio.core.session.RadioSession(
            places = r, narrator = r, speech = r, audio = r, historyStore = r,
            config = { com.gpsradio.core.session.SessionConfig("ru-RU", setOf(Topic.HISTORY), pacing = Pacing.NONSTOP) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            teaserGapMs = Long.MAX_VALUE,
        )
        try {
            s.start(); runCurrent()
            var t = 0L
            while (t < 6 * 60_000L) {
                val p = Geo.destination(here, 0.0, 15.0 * t / 1000)
                s.onLocation(com.gpsradio.core.model.LocationSample(p.lat, p.lon, 5f, 1_000_000 + t, 15f, bearingDeg = 0f)); runCurrent()
                advanceTimeBy(2_000); runCurrent()
                t += 2_000
            }
        } finally {
            s.stop(); runCurrent()
        }
        assertTrue(r.told.any { it.second.endsWith("/WORTH_A_STOP") }, "a detour story: ${r.told}")
        assertTrue(r.told.any { it.first == "view" && it.second.startsWith("PHOTO_TIP") }, "a photo stop at the lookout: ${r.told}")
    }
}
