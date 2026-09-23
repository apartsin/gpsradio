package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationFallback
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiException
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.AreaCacheStore
import com.gpsradio.core.discovery.AreaDiskCache
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import com.gpsradio.core.session.StatusLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DegradedModeTest {
    private val here = GeoPoint(47.61, 13.78)

    /** OpenAI stand-in that records every call so tests can prove it was (not) used. */
    private class Primary(var error: Exception? = null, var speechError: Exception? = null) : Narrator, SpeechService {
        val calls = mutableListOf<String>()
        override suspend fun narrate(req: NarrationRequest): Segment {
            calls += "narrate:${req.candidate.place.id}"
            error?.let { throw it }
            return Segment("AI story about ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
            calls += "converse"
            error?.let { throw it }
            return ConversationReply("answer")
        }
        override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle): String { calls += "hostLine"; return kind.fallback }
        override suspend fun webAnswer(question: String, language: String, area: AreaLabel?): String { calls += "webAnswer"; return "" }
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray {
            calls += "synthesize"
            speechError?.let { throw it }
            return "AI:$text".toByteArray()
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?): String {
            calls += "transcribe"
            return "hello"
        }
    }

    /** On-device voice stand-in. */
    private class DeviceVoice : SpeechService {
        val spoken = mutableListOf<Pair<String, String>>()
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray {
            spoken += text to language
            return "DEVICE:$text".toByteArray()
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?): String =
            throw IOException("not supported")
    }

    private class World(val places: List<PlaceCandidate>) : PlacesProvider, HistoryStore, AudioOutput {
        val played = mutableListOf<String>()
        var stored: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
        override suspend fun play(audio: ByteArray) { played += String(audio); delay(20_000) }
    }

    private fun castle(id: String, at: GeoPoint, extract: String = "Ort Castle is a castle on Lake Traun. It dates from the 11th century. It is linked to the shore by a wooden bridge. It later became a TV set.") =
        PlaceCandidate(id, "Ort Castle", "castle", at, "wikipedia:en", 0.85, 0.8, setOf(Topic.HISTORY), extract = extract, url = "https://en.wikipedia.org/wiki/Ort_Castle")

    private fun TestScope.session(
        world: World,
        primary: Primary,
        device: DeviceVoice?,
        preview: Boolean = false,
        online: () -> Boolean = { true },
        mode: TravelMode? = null,
    ) = RadioSession(
        places = world, narrator = primary, speech = primary, audio = world, historyStore = world,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY), previewMode = preview, liveVoice = true) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        fallbackNarrator = device?.let { NarrationFallback() },
        fallbackSpeech = device,
        isOnline = online,
    ).also { s -> mode?.let { s.setModeOverride(it) } }

    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try {
            s.start(); runCurrent()
            body()
        } finally {
            s.stop(); runCurrent()
        }
    }

    private fun fix(t: Long = 0) = LocationSample(here.lat, here.lon, 5f, 1_000_000 + t, 0f)

    @Test
    fun authErrorFallsBackToOnDeviceNotesWithoutErrorSpam() = runTest {
        val world = World(listOf(castle("a", Geo.destination(here, 0.0, 320.0))))
        val primary = Primary(error = OpenAiException(401, "OpenAI rejected the API key"))
        val device = DeviceVoice()
        val s = session(world, primary, device)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            val text = s.state.value.nowPlaying!!.text
            assertTrue(text.startsWith("Quick note about Ort Castle, about 300 metres to the north:"), text)
            assertTrue(text.contains("It dates from the 11th century."))
            assertFalse(text.contains("TV set"), "only the first three sentences")
            assertTrue(world.played.single().startsWith("DEVICE:Quick note"))
            assertEquals(RadioSession.DEGRADED_NOTE, s.state.value.status!!.text)
            assertEquals(StatusLevel.INFO, s.state.value.status!!.level)
            assertTrue(s.state.value.transcript.none { it.speaker == Speaker.SYSTEM })
        }
    }

    @Test
    fun networkErrorFallsBackAndRestsPrimaryDuringBackoffThenProbesAgain() = runTest {
        val world = World(
            listOf(
                castle("a", Geo.destination(here, 0.0, 150.0)),
                castle("b", Geo.destination(here, 90.0, 200.0)).copy(name = "Tower"),
                castle("c", Geo.destination(here, 180.0, 250.0)).copy(name = "Chapel"),
            ),
        )
        val primary = Primary(error = IOException("Unable to resolve host"))
        val device = DeviceVoice()
        val s = session(world, primary, device)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            // One failed attempt; the prefetch of the next story goes straight to the device (primary resting).
            assertEquals(1, primary.calls.count { it.startsWith("narrate") })
            assertTrue(device.spoken.isNotEmpty())
            primary.error = null
            // After the 15 s backoff the next story probes OpenAI again and recovers.
            advanceTimeBy(200_000); runCurrent()
            assertTrue(primary.calls.count { it.startsWith("narrate") } >= 2)
            assertTrue(world.played.any { it.startsWith("AI:") }, world.played.toString())
            assertTrue(s.state.value.status?.text != RadioSession.DEGRADED_NOTE)
            assertTrue(s.state.value.transcript.none { it.speaker == Speaker.SYSTEM })
        }
    }

    @Test
    fun speechFailureVoicesTheGeneratedStoryOnDevice() = runTest {
        val world = World(listOf(castle("a", Geo.destination(here, 0.0, 150.0))))
        val primary = Primary(speechError = OpenAiException(503, "OpenAI error 503"))
        val device = DeviceVoice()
        val s = session(world, primary, device)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals("AI story about Ort Castle", s.state.value.nowPlaying!!.text)
            assertEquals("DEVICE:AI story about Ort Castle", world.played.single())
        }
    }

    @Test
    fun badRequestIsNotAnOutageAndDoesNotFallBack() = runTest {
        val world = World(listOf(castle("a", Geo.destination(here, 0.0, 150.0))))
        val primary = Primary(error = OpenAiException(400, "OpenAI error 400: bad model"))
        val device = DeviceVoice()
        val s = session(world, primary, device)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertTrue(device.spoken.isEmpty())
            assertEquals(StatusLevel.ERROR, s.state.value.status!!.level)
        }
    }

    @Test
    fun withoutFallbackTheSessionStillBacksOffAsBefore() = runTest {
        val world = World(listOf(castle("a", Geo.destination(here, 0.0, 150.0))))
        val primary = Primary(error = IOException("offline"))
        val s = session(world, primary, device = null)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, primary.calls.size)
            assertTrue(s.state.value.status!!.text.startsWith("Narration failed"))
        }
    }

    @Test
    fun keylessPreviewNeverCallsOpenAi() = runTest {
        val world = World(listOf(castle("a", Geo.destination(here, 0.0, 150.0)), castle("b", Geo.destination(here, 180.0, 250.0)).copy(name = "Chapel")))
        val primary = Primary()
        val device = DeviceVoice()
        val s = session(world, primary, device, preview = true, mode = TravelMode.DRIVING)
        running(s) {
            val status = s.state.value.status!!
            assertEquals(RadioSession.PREVIEW_NOTE, status.text)
            assertTrue(status.needsKey)
            assertEquals("Add key", status.actionLabel)
            assertFalse(s.liveVoiceEnabled)

            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            assertTrue(world.played.single().startsWith("DEVICE:"))

            s.ask("How old is it?"); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(RadioSession.PREVIEW_QUESTIONS, s.state.value.status!!.text)
            assertEquals("Add key", s.state.value.status!!.actionLabel)
            s.askAudio("x".toByteArray(), "u.m4a", "audio/mp4"); runCurrent()
            s.toggleLive(); runCurrent()
            // Local commands still work without a model.
            s.ask("skip"); runCurrent()
            advanceTimeBy(300_000); runCurrent()
            assertTrue(device.spoken.size >= 2, "keeps telling stories on the device")
            assertEquals(emptyList(), primary.calls, "no OpenAI call in preview mode")
            // The status keeps offering to add a key.
            assertEquals("Add key", s.state.value.status!!.actionLabel)
        }
    }

    @Test
    fun offlineGoesStraightToDeviceAndDeclinesQuestionsQuickly() = runTest {
        val world = World(listOf(castle("a", Geo.destination(here, 0.0, 150.0))))
        val primary = Primary()
        val device = DeviceVoice()
        val s = session(world, primary, device, online = { false })
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertTrue(world.played.single().startsWith("DEVICE:"))
            assertEquals(RadioSession.OFFLINE_NOTE, s.state.value.status!!.text)
            s.ask("What is that?"); runCurrent()
            assertEquals(RadioSession.OFFLINE_QUESTIONS, s.state.value.status!!.text)
            assertEquals(emptyList(), primary.calls)
        }
    }

    @Test
    fun offlineDiscoveryFailureIsAQuietNoteNotAnError() = runTest {
        val failing = object : PlacesProvider {
            var calls = 0
            override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String): List<PlaceCandidate> {
                calls++
                throw IOException("offline")
            }
        }
        val world = World(emptyList())
        val s = RadioSession(
            places = failing, narrator = Primary(), speech = Primary(), audio = world, historyStore = world,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY)) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            isOnline = { false },
        )
        running(s) {
            s.onLocation(fix()); runCurrent()
            assertEquals(RadioSession.OFFLINE_NO_PLACES, s.state.value.status!!.text)
            assertEquals(StatusLevel.INFO, s.state.value.status!!.level)
            advanceTimeBy(10 * 60_000L); runCurrent()
            assertTrue(s.state.value.transcript.none { it.speaker == Speaker.SYSTEM })
            // About one retry a minute, not a tight loop.
            assertTrue(failing.calls in 2..15, "calls=${failing.calls}")
        }
    }

    // ---- NarrationFallback ---------------------------------------------------------------------

    private fun ranked(p: PlaceCandidate, d: Double, bearing: Double = 0.0) =
        RankedCandidate(p, d, bearing, 3.0, ScoreBreakdown(0.8, 1.0, 1.0, 0.6, 0.5, 0.85, 0.0, 0.0))

    private fun request(c: RankedCandidate, lang: String = "en-US", heading: Double? = null, mode: TravelMode = TravelMode.WALKING) =
        NarrationRequest(c, LocationContext(here, 5f, 0, 1.0, heading, mode), lang, setOf(Topic.HISTORY), emptyList())

    @Test
    fun notesUseFirstSentencesAndSkipAsides() = runTest {
        val p = castle(
            "a", here,
            extract = "St. Mary's Church (German: Marienkirche; pronounced [maˈʁiːən]) is a church in the town. " +
                "It was built c. 1350 by Dr. Hans Müller. The tower is 72.5 m tall! Nobody knows why. Extra.",
        )
        val seg = NarrationFallback().narrate(request(ranked(p, 320.0, bearing = 10.0), heading = 0.0))
        assertEquals(
            "Quick note about Ort Castle, about 300 metres ahead: St. Mary's Church is a church in the town. " +
                "It was built c. 1350 by Dr. Hans Müller. The tower is 72.5 m tall!",
            seg.text,
        )
        assertNull(seg.language)
        assertNull(seg.basis)
        assertEquals("https://en.wikipedia.org/wiki/Ort_Castle", seg.sources.single().url)
    }

    @Test
    fun notesRightHereAndLongSentencesAreCapped() {
        val long = "word ".repeat(200).trim() + "."
        val p = castle("a", here, extract = long)
        val text = NarrationFallback.notes(p, maxSentences = 3, maxChars = 100)
        assertTrue(text.length <= 101 && text.endsWith("…"), text)
        assertEquals("Quick note about Ort Castle, right here:", NarrationFallback.leadIn(ranked(p, 20.0), TravelMode.WALKING, null, "en-GB"))
    }

    @Test
    fun foreignExtractIsReadInItsOwnLanguage() = runTest {
        val p = castle("a", here, extract = "Das Schloss Ort ist eine Burg im Traunsee. Es wurde im 11. Jahrhundert erbaut. Mehr.")
            .copy(source = "wikipedia:de", name = "Schloss Ort")
        val seg = NarrationFallback(maxSentences = 2).narrate(request(ranked(p, 200.0), lang = "en-US"))
        assertEquals("de", seg.language)
        assertEquals("Schloss Ort: Das Schloss Ort ist eine Burg im Traunsee. Es wurde im 11. Jahrhundert erbaut.", seg.text)
        // Same edition as the session: no language override.
        assertNull(NarrationFallback().narrate(request(ranked(p, 200.0), lang = "de-AT")).language)
    }

    @Test
    fun osmFactsBecomeShortPhrases() {
        val p = PlaceCandidate("osm:node/1", "Old Mill", "historic: mill", here, "openstreetmap", 0.5, 0.3, emptySet(),
            extract = "historic: watermill; heritage-listed; dates from: 1600")
        assertEquals("Historic watermill. Heritage-listed. Dates from 1600.", NarrationFallback.notes(p))
        assertEquals("A mill on the brook.", NarrationFallback.notes(p.copy(description = "A mill on the brook")))
    }

    @Test
    fun fallbackNarratorCannotConverse() = runTest {
        assertFailsWith<UnsupportedOperationException> {
            NarrationFallback().converse(ConversationRequest("hi", "en", null, null, null, emptyList(), emptyList(), emptyList(), null))
        }
    }

    // ---- area cache ----------------------------------------------------------------------------

    private class MemStore : AreaCacheStore {
        var data: String? = null
        var saves = 0
        override fun load() = data
        override fun save(serialized: String) { data = serialized; saves++ }
    }

    @Test
    fun areaCachePersistsAcrossInstancesAndFiltersByDistanceAndLanguage() {
        val store = MemStore()
        var now = 1_000L
        val near = castle("near", Geo.destination(here, 0.0, 300.0))
        val far = castle("far", Geo.destination(here, 0.0, 5_000.0))
        AreaDiskCache(store, { now }).put("k1", here, 1500, "en", listOf(near, far))
        assertEquals(1, store.saves)

        val reopened = AreaDiskCache(store, { now })
        assertEquals(listOf("near"), reopened.around(here, 1500, "en").map { it.id })
        assertEquals(near, reopened.around(here, 1500, "en").single())
        assertTrue(reopened.around(here, 1500, "de").isEmpty())
        // A nearby point finds cached places within its own radius; a far one finds nothing.
        assertEquals(listOf("near"), reopened.around(Geo.destination(here, 0.0, 1_000.0), 800, "en").map { it.id })
        assertTrue(reopened.around(Geo.destination(here, 0.0, 20_000.0), 1_500, "en").isEmpty())
    }

    @Test
    fun areaCacheExpiresAndStaysWithinLimits() {
        val store = MemStore()
        var now = 1_000L
        val cache = AreaDiskCache(store, { now }, ttlMs = 10_000, maxAreas = 3, maxExtractChars = 20)
        repeat(5) { i ->
            now += 1
            val center = Geo.destination(here, 90.0, i * 3_000.0)
            cache.put("k$i", center, 1500, "en", listOf(castle("p$i", center)))
        }
        assertEquals(3, cache.size)
        assertTrue(cache.around(here, 1500, "en").isEmpty(), "oldest areas were evicted")
        assertEquals(20, AreaDiskCache(store, { now }).around(Geo.destination(here, 90.0, 12_000.0), 1500, "en").single().extract!!.length)
        now += 20_000
        assertEquals(0, AreaDiskCache(store, { now }, ttlMs = 10_000).size)

        // Byte cap: older areas are dropped until the file fits.
        val small = MemStore()
        val capped = AreaDiskCache(small, { now }, maxBytes = 1_500)
        repeat(4) { i -> capped.put("k$i", Geo.destination(here, 90.0, i * 3_000.0), 1500, "en", listOf(castle("p$i", here))) }
        assertTrue(small.data!!.length <= 1_500)
        assertTrue(capped.size in 1..3)
        // Corrupt files are ignored rather than crashing.
        assertEquals(0, AreaDiskCache(MemStore().apply { data = "{not json" }, { now }).size)
    }

    private fun discoveryServer(): Pair<MockWebServer, () -> Int> {
        val server = MockWebServer()
        var hits = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits++
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/overpass") -> MockResponse().setBody("""{"elements":[]}""")
                    path.contains("list=geosearch") ->
                        MockResponse().setBody("""{"query":{"geosearch":[{"pageid":20,"title":"Ort Castle","lat":47.611,"lon":13.781,"dist":120}]}}""")
                    else -> MockResponse().setBody("""{"query":{"pages":[{"pageid":20,"title":"Ort Castle","extract":"Ort Castle is a castle on Lake Traun.","pageprops":{"wikibase_item":"Q1"}}]}}""")
                }
            }
        }
        server.start()
        return server to { hits }
    }

    @Test
    fun discoveryServesVisitedAreasOfflineFromDisk() = runTest {
        val (server, hits) = discoveryServer()
        val http = OkHttpClient()
        val wiki = WikipediaClient(http, "test") { lang -> server.url("/$lang/w/api.php") }
        val osm = OverpassClient(http, "test", server.url("/overpass").toString())
        val store = MemStore()
        val io = UnconfinedTestDispatcher(testScheduler)
        val online = DiscoveryService(wiki, osm, diskCache = AreaDiskCache(store), ioDispatcher = io)
        val first = online.discover(here, 1500, "en")
        assertEquals(listOf("Ort Castle"), first.map { it.name })
        assertTrue(store.data!!.contains("Ort Castle"))

        // Next app run, offline: served from disk without touching the network.
        val before = hits()
        var isOnline = false
        val offline = DiscoveryService(wiki, osm, diskCache = AreaDiskCache(store), isOnline = { isOnline }, ioDispatcher = io)
        assertEquals(first, offline.discover(Geo.destination(here, 0.0, 200.0), 1500, "en"))
        assertEquals(before, hits())
        // Somewhere never visited: the offline error surfaces.
        assertFailsWith<IOException> { offline.discover(Geo.destination(here, 0.0, 50_000.0), 1500, "en") }

        // Online but every source fails (e.g. captive portal): disk still helps.
        server.shutdown()
        isOnline = true
        val broken = DiscoveryService(wiki, osm, diskCache = AreaDiskCache(store), isOnline = { isOnline }, ioDispatcher = io)
        assertEquals(first, broken.discover(here, 1500, "en"))
    }
}
