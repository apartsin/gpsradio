package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.StoryBasis
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.lang.SourceLines
import com.gpsradio.core.lang.SourceNames
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.SourceRef
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.Topic
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec A §45: "where's that from?" is answered at once on the device; "is that true?" goes to the model. */
class SpokenSourcesTest {
    private val here = GeoPoint(47.918, 13.799)

    private class Radio(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore {
        var asked = 0
        val played = mutableListOf<String>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest) = Segment(
            "История про ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name,
            listOf(SourceRef(req.candidate.place.name, "https://de.wikipedia.org/wiki/Schloss_Ort")), basis = StoryBasis.MIXED,
        )
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
            asked++
            return ConversationReply("Проверю.")
        }
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) {
            played += String(audio)
            delay(20_000)
        }
        override fun load(): String? = null
        override fun save(serialized: String) {}
    }

    @Test
    fun whereIsThatFromIsAnsweredOnTheDeviceInTheSessionLanguage() = runTest {
        val r = Radio(listOf(place("castle", Geo.destination(here, 0.0, 150.0), name = "Schloss Ort")))
        val s = RadioSession(
            places = r, narrator = r, speech = r, audio = r, historyStore = r,
            config = { SessionConfig("ru-RU", setOf(Topic.HISTORY)) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            teaserGapMs = Long.MAX_VALUE,
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            s.ask("Откуда это?"); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            val line = s.state.value.transcript.last { it.speaker == Speaker.RADIO }
            assertTrue("Википедия" in line.text && "легенда" in line.text, line.text)
            assertTrue(line.sources.isNotEmpty(), "the links are in the transcript")
            assertTrue(r.played.any { "Википедия" in it }, "spoken")
            assertEquals(0, r.asked, "no model call")
        } finally {
            s.stop(); runCurrent()
        }
    }

    @Test
    fun linesNameSourcesLikeAPresenterNeverAUrl() {
        val story = Segment("…", "x", "X", listOf(
            SourceRef("A", "https://en.wikipedia.org/wiki/A"),
            SourceRef("B", "https://www.openstreetmap.org/node/1"),
            SourceRef("C", "https://www.visit-gmunden.at/see"),
        ), basis = StoryBasis.DOCUMENTED)
        val en = SourceLines.spoken("en-US", story)
        assertEquals("That was from Wikipedia, OpenStreetMap and visit-gmunden.at. The links are in the transcript.", en)
        assertFalse("http" in en)
        assertTrue(SourceLines.spoken("ru-RU", null).startsWith("Для последней истории"))
        assertEquals("OpenStreetMap", SourceNames.of(null, "osm"))
    }

    @Test
    fun onlyTheWordsAreLocalAndThePromptsKnowTheRule() {
        for (q in listOf("Sources?", "Where's that from?", "Откуда это?", "источники", "Woher ist das?")) assertTrue(RadioSession.isSourcesQuestion(q), q)
        for (q in listOf("Is that true?", "Это правда?", "Откуда здесь замок?")) assertFalse(RadioSession.isSourcesQuestion(q), q)
        assertTrue("Where's that from?" in RadioAgent.conversationInstructions("ru-RU", searchAvailable = true))
    }
}

/** Spec A §45: every Commons photo shows its author and licence. */
class PhotoCreditsTest {
    @Test
    fun commonsFileNamesComeFromThumbnailAndOriginalUrls() {
        assertEquals("Schloss Ort Gmunden.jpg", com.gpsradio.core.discovery.WikipediaClient.fileTitle(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Schloss_Ort_Gmunden.jpg/800px-Schloss_Ort_Gmunden.jpg"))
        assertEquals("Traunsee.jpg", com.gpsradio.core.discovery.WikipediaClient.fileTitle(
            "https://upload.wikimedia.org/wikipedia/commons/c/cd/Traunsee.jpg"))
        assertEquals(null, com.gpsradio.core.discovery.WikipediaClient.fileTitle("https://example.com/a.jpg"))
        assertEquals("Jane Doe · CC BY-SA 4.0 · Wikimedia Commons",
            com.gpsradio.core.discovery.WikipediaClient.credit("<a href=\"//commons.wikimedia.org/wiki/User:Jane\">Jane  Doe</a>", "CC BY-SA 4.0"))
        assertEquals(null, com.gpsradio.core.discovery.WikipediaClient.credit(" ", null))
    }

    @Test
    fun creditsAreFetchedByFileAndKeyedByUrl() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
            """{"query":{"pages":[{"title":"File:Traunsee.jpg","imageinfo":[{"extmetadata":{"Artist":{"value":"<span>Max Muster</span>"},"LicenseShortName":{"value":"CC BY 3.0"}}}]}]}}"""))
        server.start()
        try {
            val wiki = com.gpsradio.core.discovery.WikipediaClient(okhttp3.OkHttpClient(), "ua", { server.url("/w/api.php") })
            val url = "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cd/Traunsee.jpg/800px-Traunsee.jpg"
            assertEquals(mapOf(url to "Max Muster · CC BY 3.0 · Wikimedia Commons"), wiki.photoCredits(listOf(url, "https://example.com/x.jpg")))
            val q = server.takeRequest().requestUrl!!
            assertEquals("File:Traunsee.jpg", q.queryParameter("titles"))
            assertEquals("extmetadata", q.queryParameter("iiprop"))
        } finally {
            server.shutdown()
        }
    }
}
