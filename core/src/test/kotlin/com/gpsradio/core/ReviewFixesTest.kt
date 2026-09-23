package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.OpenAiException
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.discovery.TopicClassifier
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.Topic
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewFixesTest {
    private val here = GeoPoint(47.61, 13.78)

    private class Scripted(
        var places: () -> List<PlaceCandidate>,
        var narrateError: Exception? = null,
    ) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        var discoverCalls = 0
        val narrated = mutableListOf<String>()
        var stored: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String): List<PlaceCandidate> {
            discoverCalls++
            return places()
        }
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrated += req.candidate.place.id
            narrateError?.let { throw it }
            return Segment("Story", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    private fun TestScope.session(f: Scripted, audio: AudioOutput = AudioOutput { delay(20_000) }) = RadioSession(
        places = f, narrator = f, speech = f, audio = audio, historyStore = f,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY)) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Always stops the session so a failed assertion cannot leave the scheduler loop running forever. */
    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try {
            s.start(); runCurrent()
            body()
        } finally {
            s.stop(); runCurrent()
        }
    }

    private fun fix() = LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)

    @Test
    fun transientErrorsBackOffInsteadOfBlacklistingCandidates() = runTest {
        val f = Scripted({ listOf(place("a", Geo.destination(here, 0.0, 100.0)), place("b", Geo.destination(here, 90.0, 120.0))) })
        f.narrateError = OpenAiException(401, "OpenAI rejected the API key")
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, f.narrated.size)
            // Within the 15 s backoff nothing else is tried.
            advanceTimeBy(4_000); runCurrent()
            assertEquals(1, f.narrated.size)
            // Once the key works, the same best candidate is still available (not blacklisted).
            f.narrateError = null
            advanceTimeBy(30_000); runCurrent()
            assertEquals("a", f.narrated[1])
        }
    }

    @Test
    fun stationaryUserGetsDiscoveryRetryWithoutNewFixes() = runTest {
        var fail = true
        val f = Scripted({ if (fail) throw IOException("offline") else listOf(place("a", Geo.destination(here, 0.0, 100.0))) })
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            assertTrue(s.state.value.status!!.text.startsWith("Couldn't load"))
            fail = false
            // No further GPS fixes arrive; the scheduler retries on its own about a minute later.
            advanceTimeBy(90_000); runCurrent()
            assertTrue(f.discoverCalls >= 2)
            assertEquals(listOf("a"), f.narrated.distinct())
        }
    }

    @Test
    fun pauseDuringStoryDoesNotMarkItHeard() = runTest {
        val f = Scripted({ listOf(place("a", Geo.destination(here, 0.0, 100.0))) })
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(4_000); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            s.pause(); runCurrent()
            s.resume(); runCurrent()
            advanceTimeBy(90_000); runCurrent()
            assertEquals(listOf("a", "a"), f.narrated)
        }
    }

    @Test
    fun keywordsMatchWholeWords() {
        assertFalse(Topic.WAR in TopicClassifier.fromText("An award-winning software company"))
        assertTrue(Topic.WAR in TopicClassifier.fromText("Site of a battle in the Thirty Years' War"))
        assertTrue(Topic.ARCHITECTURE in TopicClassifier.fromText("designed by the architect Otto Wagner"))
    }

    @Test
    fun webSearchOnlyWhenModelAsksForIt() = runTest {
        val server = MockWebServer()
        fun reply(json: String) = MockResponse().setBody(
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${kotlinx.serialization.json.JsonPrimitive(json)},"annotations":[]}]}]}""",
        )
        val base = """"action":"none","language":null,"persist_language":false,"theme":null,"entity_id":null,"remember":[],"forget":[]"""
        server.enqueue(reply("""{"reply":"It is 130 m long.",$base,"needs_search":false}"""))
        server.enqueue(reply("""{"reply":"Let me check.",$base,"needs_search":true}"""))
        server.enqueue(reply("""{"reply":"It opens at nine today.",$base,"needs_search":false}"""))
        server.start()
        val agent = RadioAgent(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() })
        fun req(q: String) = ConversationRequest(q, "en-US", null, null, null, emptyList(), emptyList(), emptyList(), null)

        assertEquals("It is 130 m long.", agent.converse(req("How long is the bridge?")).reply)
        assertFalse(server.takeRequest().body.readUtf8().contains("web_search"))

        var searching = false
        assertEquals("It opens at nine today.", agent.converse(req("Is it open now?")) { searching = true }.reply)
        assertTrue(searching)
        assertFalse(server.takeRequest().body.readUtf8().contains("web_search"))
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("\"web_search\""))
        // Static rules stay in instructions; per-turn context travels as a developer message.
        assertTrue(second.contains("\"role\":\"developer\""))
        server.shutdown()
    }
}
