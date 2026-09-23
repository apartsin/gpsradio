package com.gpsradio.core

import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.QuotaErrors
import com.gpsradio.core.ai.RealtimeEvent
import com.gpsradio.core.ai.RealtimeProtocol
import kotlinx.coroutines.runBlocking
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

class QuotaTest {
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
        builtIn: Boolean = false,
    ) = RadioSession(
        places = world, narrator = primary, speech = primary, audio = world, historyStore = world,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY), previewMode = preview, liveVoice = true, usingBuiltInKey = builtIn) },
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


    private val quotaBody = """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","param":null,"code":"insufficient_quota"}}"""
    private fun quotaError() = OpenAiException(429, OpenAiClient.friendlyError(429, quotaBody))

    @Test
    fun quotaErrorsAreToldApartFromRateLimits() {
        val quota = quotaError()
        assertTrue(quota.isQuotaExhausted)
        assertTrue(quota.message!!.startsWith(QuotaErrors.MESSAGE))
        val rate = OpenAiException(429, OpenAiClient.friendlyError(429, """{"error":{"message":"Rate limit reached for gpt-4o-mini","type":"requests","code":"rate_limit_exceeded"}}"""))
        assertFalse(rate.isQuotaExhausted)
        assertTrue(rate.message!!.startsWith("OpenAI rate limit reached"))
        // A body cut off mid-JSON (logs keep 300 chars) is still recognised.
        assertTrue(QuotaErrors.matches(OpenAiClient.friendlyError(429, quotaBody.take(60))))
        assertFalse(QuotaErrors.matches(OpenAiClient.friendlyError(500, "oops")))
        assertFalse(QuotaErrors.matches(null))
    }

    @Test
    fun openAiClientReportsQuotaFromTheHttpResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody(quotaBody))
        server.start()
        try {
            val client = OpenAiClient(OkHttpClient(), apiKey = { "sk-test" }, baseUrl = server.url("/v1").toString().trimEnd('/'))
            val e = assertFailsWith<OpenAiException> {
                client.respond(OpenAiClient.ResponseRequest("gpt-4o-mini", "x", listOf(OpenAiClient.Message("user", "hi"))))
            }
            assertEquals(429, e.status)
            assertTrue(e.isQuotaExhausted, e.message)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun realtimeQuotaFailuresBecomeErrors() {
        val failed = """{"type":"response.done","response":{"status":"failed","status_details":{"type":"failed","error":{"type":"insufficient_quota","code":"insufficient_quota","message":"You exceeded your current quota"}}}}"""
        val e = RealtimeProtocol.parse(failed)
        assertTrue(e is RealtimeEvent.Error && QuotaErrors.matches(e.message), "$e")
        val err = RealtimeProtocol.parse("""{"type":"error","error":{"type":"insufficient_quota","code":"insufficient_quota","message":"You exceeded your current quota"}}""")
        assertTrue(err is RealtimeEvent.Error && err.message.startsWith(QuotaErrors.MESSAGE), "$err")
        assertEquals(RealtimeEvent.ResponseDone, RealtimeProtocol.parse("""{"type":"response.done","response":{"status":"completed"}}"""))
        val other = RealtimeProtocol.parse("""{"type":"error","error":{"type":"invalid_request_error","message":"Cancellation failed"}}""")
        assertTrue(other is RealtimeEvent.Error && !QuotaErrors.matches(other.message))
    }

    @Test
    fun outOfCreditIsShownSpokenOnceAndClearsWhenCreditReturns() = runTest {
        val world = World((0 until 10).map { i -> castle("p$i", Geo.destination(here, i * 36.0, 120.0 + i * 15)).copy(name = "Place $i") })
        val primary = Primary(error = quotaError())
        val device = DeviceVoice()
        val s = session(world, primary, device)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            val st = s.state.value
            assertTrue(st.quotaExhausted)
            assertEquals(RadioSession.QUOTA_OWN_KEY, st.status!!.text)
            assertEquals(StatusLevel.ERROR, st.status!!.level)
            assertTrue(st.status!!.needsKey)
            assertEquals("Add key", st.status!!.actionLabel)
            assertEquals(1, st.transcript.count { it.text == RadioSession.QUOTA_OWN_KEY })
            // The radio keeps going on the device and says why, once.
            assertTrue(world.played.first().startsWith("DEVICE:" + RadioSession.quotaSpoken("en-US")), world.played.first())
            advanceTimeBy(120_000); runCurrent()
            assertTrue(world.played.size >= 2, "${world.played}")
            assertEquals(1, world.played.count { RadioSession.quotaSpoken("en-US") in it })
            // Still out of credit: the status is not replaced by the generic "unreachable" note.
            assertEquals(RadioSession.QUOTA_OWN_KEY, s.state.value.status!!.text)
            // Credit is back: the next probe succeeds and the warning goes away.
            primary.error = null
            advanceTimeBy(400_000); runCurrent()
            assertFalse(s.state.value.quotaExhausted)
            assertTrue(s.state.value.status?.text != RadioSession.QUOTA_OWN_KEY)
        }
    }

    @Test
    fun builtInKeyOutOfCreditAsksForTheListenersOwnKey() = runTest {
        val world = World(listOf(castle("a", Geo.destination(here, 0.0, 150.0))))
        val s = session(world, Primary(error = quotaError()), DeviceVoice(), builtIn = true)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(RadioSession.QUOTA_BUILT_IN, s.state.value.status!!.text)
            assertTrue("own OpenAI key" in RadioSession.QUOTA_BUILT_IN)
        }
    }

    @Test
    fun questionsWhileOutOfCreditExplainWhyAndAKeyChangeRetriesAtOnce() = runTest {
        val world = World(emptyList())
        val primary = Primary(error = quotaError())
        val s = session(world, primary, DeviceVoice())
        running(s) {
            s.onLocation(fix()); runCurrent()
            s.ask("What's that tower?"); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertTrue(s.state.value.quotaExhausted)
            assertEquals(RadioSession.QUOTA_OWN_KEY, s.state.value.status!!.text)
            assertTrue(s.state.value.transcript.none { "Couldn't answer" in it.text })
            s.onApiKeyChanged(); runCurrent()
            assertFalse(s.state.value.quotaExhausted)
            assertNull(s.state.value.status)
        }
    }

    @Test
    fun spokenNoticeIsLocalised() {
        assertTrue(RadioSession.quotaSpoken("ru-RU").contains("OpenAI"))
        assertTrue(RadioSession.quotaSpoken("ru-RU") != RadioSession.quotaSpoken("en-US"))
        assertEquals(RadioSession.quotaSpoken("en-GB"), RadioSession.quotaSpoken("xx"))
    }
}
