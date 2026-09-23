package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.StoryBasis
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.StoryReason
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustTest {
    private val here = GeoPoint(47.61, 13.78)

    // ---- claim basis ---------------------------------------------------------------------------

    @Test
    fun parsesStructuredNarrationAndFallsBackToPlainText() {
        assertEquals("The castle rose in 1080." to StoryBasis.DOCUMENTED, RadioAgent.parseNarration("""{"text":"The castle rose in **1080**.","basis":"documented"}"""))
        assertEquals(StoryBasis.LEGEND, RadioAgent.parseNarration("""{"text":"A ghost walks here.","basis":"LEGEND"}""").second)
        assertEquals(StoryBasis.MIXED, RadioAgent.parseNarration(""" {"text":"x","basis":"mixed"} """).second)
        // Unknown basis: keep the text, drop the label.
        assertEquals("x" to null, RadioAgent.parseNarration("""{"text":"x","basis":"rumour"}"""))
        // Plain text (model ignored the format, or structured output was unavailable).
        assertEquals("Just a story." to null, RadioAgent.parseNarration("Just a *story*."))
        // JSON without text, or broken JSON, is read as-is rather than lost.
        assertEquals(null, RadioAgent.parseNarration("""{"basis":"legend"}""").second)
        assertEquals("{broken", RadioAgent.parseNarration("{broken").first)
        assertEquals("Includes legend", StoryBasis.MIXED.label)
        assertEquals("Documented", StoryBasis.DOCUMENTED.label)
    }

    private fun candidate() = RankedCandidate(
        PlaceCandidate("wiki:en:1", "Ort Castle", "castle", here, "wikipedia:en", 0.9, 0.8, setOf(Topic.HISTORY), extract = "A castle.", url = "https://example.org/ort"),
        200.0, 0.0, 3.0, ScoreBreakdown(0.8, 1.0, 1.0, 0.6, 0.5, 0.9, 0.0, 0.0),
    )

    private fun narrationRequest() =
        NarrationRequest(candidate(), LocationContext(here, 5f, 0, 1.0, null, TravelMode.WALKING), "en-US", setOf(Topic.HISTORY), emptyList())

    private fun responses(text: String) = MockResponse().setBody(
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}]}""",
    )

    @Test
    fun narrationRequestsBasisAndReturnsIt() = runTest {
        val server = MockWebServer()
        server.enqueue(responses("""{"text":"Ort Castle has stood on its island since 1080.","basis":"documented"}"""))
        server.start()
        val agent = RadioAgent(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() })
        val seg = agent.narrate(narrationRequest())
        val body = server.takeRequest().body.readUtf8()
        server.shutdown()
        assertTrue(body.contains("\"radio_story\""))
        assertTrue(body.contains("\"basis\""))
        assertEquals("Ort Castle has stood on its island since 1080.", seg.text)
        assertEquals(StoryBasis.DOCUMENTED, seg.basis)
        assertEquals("https://example.org/ort", seg.sources.single().url)
    }

    @Test
    fun narrationRetriesAsPlainTextWhenStructuredOutputIsRejected() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid schema for response_format"}}"""))
        server.enqueue(responses("A plain story."))
        server.start()
        val agent = RadioAgent(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() })
        val seg = agent.narrate(narrationRequest())
        server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("radio_story"))
        server.shutdown()
        assertEquals("A plain story.", seg.text)
        assertNull(seg.basis)
    }

    @Test
    fun plainNarrationModeSendsNoSchema() = runTest {
        val server = MockWebServer()
        server.enqueue(responses("A plain story."))
        server.start()
        val agent = RadioAgent(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() }, structuredNarration = false)
        assertEquals("A plain story.", agent.narrate(narrationRequest()).text)
        assertFalse(server.takeRequest().body.readUtf8().contains("json_schema"))
        server.shutdown()
    }

    // ---- why this story? -----------------------------------------------------------------------

    private fun ranked(d: Double, topics: Set<Topic> = setOf(Topic.HISTORY), b: ScoreBreakdown = ScoreBreakdown(0.8, 1.0, 1.0, 0.6, 0.5, 0.9, 0.0, 0.0)) =
        RankedCandidate(PlaceCandidate("p", "P", "castle", here, "wikipedia:en", 0.9, 0.8, topics), d, 0.0, 3.0, b)

    @Test
    fun reasonExplainsDistanceInterestAndQuality() {
        assertEquals("Close by (200 m) · matches your interest in history · well documented", StoryReason.of(ranked(210.0), setOf(Topic.HISTORY)))
        assertEquals("Right here · well documented", StoryReason.of(ranked(30.0), setOf(Topic.FOOD)))
        assertEquals(
            "Just ahead (450 m) · fits your architecture theme · well documented",
            StoryReason.of(ranked(460.0, setOf(Topic.HISTORY, Topic.ARCHITECTURE), ScoreBreakdown(0.8, 1.0, 1.0, 0.6, 0.95, 0.9, 0.0, 0.0)), setOf(Topic.HISTORY), theme = Topic.ARCHITECTURE),
        )
        assertEquals(
            "2.4 km away · a rich story",
            StoryReason.of(ranked(2_400.0, emptySet(), ScoreBreakdown(0.8, 0.4, 1.0, 0.2, 0.5, 0.6, 0.0, 0.0)), setOf(Topic.HISTORY)),
        )
        assertEquals(
            "Ahead (3.0 km) · matches your interest in nature · came up earlier",
            StoryReason.of(ranked(3_000.0, setOf(Topic.NATURE), ScoreBreakdown(0.8, 1.0, 0.4, 0.2, 1.0, 0.9, 0.0, 0.0)), setOf(Topic.NATURE)),
        )
    }

    private class Fakes(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore {
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest) =
            Segment("Story", req.candidate.place.id, req.candidate.place.name, emptyList(), basis = StoryBasis.LEGEND)
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) = delay(20_000)
        override fun load(): String? = null
        override fun save(serialized: String) {}
    }

    @Test
    fun sessionExposesReasonAndBasisForTheStoryOnAir() = runTest {
        val f = Fakes(listOf(place("castle", Geo.destination(here, 0.0, 210.0))))
        val s = RadioSession(
            places = f, narrator = f, speech = f, audio = f, historyStore = f,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY)) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            assertEquals(StoryBasis.LEGEND, s.state.value.nowPlaying!!.basis)
            assertEquals("Close by (200 m) · matches your interest in history · well documented", s.state.value.nowPlayingReason)
            s.skip(); runCurrent()
            assertNull(s.state.value.nowPlayingReason)
        } finally {
            s.stop(); runCurrent()
        }
    }
}
