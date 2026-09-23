package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.RealtimeClient
import com.gpsradio.core.ai.RealtimeConnection
import com.gpsradio.core.ai.RealtimeEvent
import com.gpsradio.core.ai.RealtimeProtocol
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.Topic
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.LiveConversation
import com.gpsradio.core.session.LiveState
import com.gpsradio.core.session.PcmAudio
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveVoiceTest {
    private val here = GeoPoint(47.61, 13.78)

    /** Scripted Realtime connection: tests push server events and inspect what the client sent. */
    private class FakeConnection : RealtimeConnection {
        val server = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<JsonObject>()
        var closed = false
        override val events: Flow<RealtimeEvent> = server.receiveAsFlow()
        override fun send(event: JsonObject): Boolean { sent += event; return true }
        override fun close() { closed = true; server.close() }
        fun types() = sent.map { it["type"]!!.jsonPrimitive.content }
    }

    private class FakePcm : PcmAudio {
        var capturing = false
        var played = 0
        var flushed = 0
        var onChunk: ((ByteArray) -> Unit)? = null
        override fun startCapture(onChunk: (ByteArray) -> Unit): Boolean { capturing = true; this.onChunk = onChunk; return true }
        override fun stopCapture() { capturing = false }
        override fun play(pcm: ByteArray) { played += pcm.size }
        override fun stopPlayback() { flushed++ }
    }

    @Test
    fun parsesGaAndBetaEventNames() {
        val audio = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertIs<RealtimeEvent.SessionReady>(RealtimeProtocol.parse("""{"type":"session.created","session":{}}"""))
        assertEquals(3, (RealtimeProtocol.parse("""{"type":"response.output_audio.delta","delta":"$audio"}""") as RealtimeEvent.AudioDelta).pcm.size)
        assertEquals(3, (RealtimeProtocol.parse("""{"type":"response.audio.delta","delta":"$audio"}""") as RealtimeEvent.AudioDelta).pcm.size)
        assertEquals("Hi there", (RealtimeProtocol.parse("""{"type":"response.output_audio_transcript.done","transcript":" Hi there "}""") as RealtimeEvent.AssistantTranscript).text)
        assertEquals("tell me more", (RealtimeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"tell me more"}""") as RealtimeEvent.UserTranscript).text)
        val call = RealtimeProtocol.parse("""{"type":"response.function_call_arguments.done","call_id":"c1","name":"web_search","arguments":"{\"query\":\"x\"}"}""") as RealtimeEvent.FunctionCall
        assertEquals("web_search", call.name)
        assertEquals("bad key", (RealtimeProtocol.parse("""{"type":"error","error":{"message":"bad key"}}""") as RealtimeEvent.Error).message)
        assertNull(RealtimeProtocol.parse("""{"type":"rate_limits.updated"}"""))
        assertNull(RealtimeProtocol.parse("not json"))
    }

    @Test
    fun sessionUpdateConfiguresVoiceVadAndTools() {
        val u = RealtimeProtocol.sessionUpdate("be nice", "coral", "gpt-4o-mini-transcribe", RealtimeProtocol.tools)
        val session = u["session"]!!.jsonObject
        assertEquals("realtime", session["type"]!!.jsonPrimitive.content)
        val audio = session["audio"]!!.jsonObject
        assertEquals("coral", audio["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals("server_vad", audio["input"]!!.jsonObject["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(24000, audio["input"]!!.jsonObject["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        val toolNames = RealtimeProtocol.tools.map { it.name }
        assertEquals(listOf("web_search", "radio_control", "remember", "set_trip"), toolNames)
    }

    @Test
    fun realWebSocketCarriesAuthAndEvents() = runBlocking {
        val server = MockWebServer()
        val fromClient = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"session.created","session":{}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                fromClient += text
                webSocket.send("""{"type":"response.output_audio_transcript.done","transcript":"Hello!"}""")
            }
        }))
        server.start()
        val client = RealtimeClient(OkHttpClient(), { "sk-test" }, server.url("/v1/realtime").toString().replace("http", "ws"))
        val conn = client.connect("gpt-realtime")
        withTimeout(10_000) {
            assertIs<RealtimeEvent.SessionReady>(conn.events.first())
            conn.send(RealtimeProtocol.responseCreate("Say hi"))
            assertEquals(RealtimeEvent.AssistantTranscript("Hello!"), conn.events.first())
        }
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertTrue(req.path!!.contains("model=gpt-realtime"))
        assertTrue(fromClient.poll(5, TimeUnit.SECONDS)!!.contains("response.create"))
        conn.close()
        // MockWebServer can wait on open WebSocket streams at shutdown; that is not what this test checks.
        runCatching { server.shutdown() }
    }

    // ---- session integration ---------------------------------------------------------------

    private class Radio(val list: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        val narrations = mutableListOf<Pair<String, SegmentFormat>>()
        val searches = mutableListOf<String>()
        var hist: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrations += req.candidate.place.id to req.format
            return Segment("${req.format} ${req.candidate.place.id}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("classic")
        override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle) = "line"
        override suspend fun webAnswer(question: String, language: String, area: AreaLabel?): String { searches += question; return "It opens at nine." }
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.session(r: Radio, conns: MutableList<FakeConnection>, pcm: FakePcm) = RadioSession(
        places = r, narrator = r, speech = r, audio = AudioOutput { delay(5_000) }, historyStore = r,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY), liveVoice = true, voice = "coral") },
        liveFactory = { host, scope ->
            LiveConversation({ FakeConnection().also { conns += it } }, pcm, host, scope, idleTimeoutMs = 20_000, clock = { testScheduler.currentTime })
        },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        teaserGapMs = 0,
    )

    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try { s.start(); runCurrent(); body() } finally { s.stop(); runCurrent() }
    }

    @Test
    fun tapMicOpensLiveConversationWithToolsAndBargeIn() = runTest {
        val r = Radio(listOf(place("a", Geo.destination(here, 0.0, 100.0))))
        val conns = mutableListOf<FakeConnection>()
        val pcm = FakePcm()
        val s = session(r, conns, pcm)
        running(s) {
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            s.toggleLive(); runCurrent()
            val c = conns.single()
            assertEquals(LiveState.CONNECTING, s.state.value.live)
            c.server.trySend(RealtimeEvent.SessionReady); runCurrent()
            assertTrue(pcm.capturing)
            assertEquals(LiveState.LISTENING, s.state.value.live)
            val update = c.sent.first { it["type"]!!.jsonPrimitive.content == "session.update" }
            assertTrue("Context (JSON)" in update["session"]!!.jsonObject["instructions"]!!.jsonPrimitive.content)
            // Mic audio is streamed to the model.
            pcm.onChunk!!(ByteArray(480))
            assertTrue("input_audio_buffer.append" in c.types())

            // The listener asks something current; the model uses web search via our tool.
            c.server.trySend(RealtimeEvent.UserTranscript("Is the castle open now?"))
            c.server.trySend(RealtimeEvent.FunctionCall("call1", "web_search", """{"query":"castle opening hours"}""")); runCurrent()
            assertEquals(listOf("castle opening hours"), r.searches)
            assertTrue("function_call_output" in c.sent.last { it["type"]!!.jsonPrimitive.content == "conversation.item.create" }.toString())
            assertEquals("response.create", c.types().last())

            // The host answers in audio; the listener interrupts (barge-in) and playback is flushed.
            c.server.trySend(RealtimeEvent.AudioDelta(ByteArray(960)))
            c.server.trySend(RealtimeEvent.AssistantTranscript("It opens at nine.")); runCurrent()
            assertEquals(LiveState.ASSISTANT_SPEAKING, s.state.value.live)
            c.server.trySend(RealtimeEvent.SpeechStarted); runCurrent()
            assertTrue(pcm.flushed >= 1)
            assertTrue(s.state.value.transcript.any { it.speaker == Speaker.USER && it.text == "Is the castle open now?" })
            assertTrue(s.state.value.transcript.any { it.speaker == Speaker.RADIO && it.text == "It opens at nine." })

            // "Back to the radio" closes the live session and resumes programming.
            c.server.trySend(RealtimeEvent.FunctionCall("call2", "radio_control", """{"action":"resume_radio"}""")); runCurrent()
            assertTrue(c.closed)
            assertNull(s.state.value.live)
            assertEquals(false, pcm.capturing)
            assertTrue(s.state.value.radioState != RadioState.CONVERSING)
        }
    }

    @Test
    fun teaserOpensHandsFreeListeningAndSpokenYesTellsTheStory() = runTest {
        val rich = "The castle was built on a rock in the lake. ".repeat(30)
        val r = Radio(
            listOf(
                place("a", Geo.destination(here, 0.0, 100.0)),
                place("b", Geo.destination(here, 90.0, 110.0)),
                place("c", Geo.destination(here, 180.0, 120.0)).copy(extract = rich),
            ),
        )
        val conns = mutableListOf<FakeConnection>()
        val pcm = FakePcm()
        val s = session(r, conns, pcm)
        running(s) {
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            var t = 0
            while (conns.isEmpty() && t < 600) { advanceTimeBy(1_000); runCurrent(); t++ }
            assertEquals("c" to SegmentFormat.TEASER, r.narrations.last())
            val c = conns.single()
            c.server.trySend(RealtimeEvent.SessionReady); runCurrent()
            assertTrue("pending_offer" in c.sent.first()["session"]!!.jsonObject["instructions"]!!.jsonPrimitive.content)
            c.server.trySend(RealtimeEvent.UserTranscript("yes please"))
            c.server.trySend(RealtimeEvent.FunctionCall("x", "radio_control", """{"action":"accept_offer"}""")); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals("c" to SegmentFormat.STORY, r.narrations.last())
            assertTrue(c.closed)
            assertNull(s.state.value.pendingOffer)
        }
    }

    @Test
    fun liveToolsRememberAndSetTrip() = runTest {
        val r = Radio(listOf(place("a", Geo.destination(here, 0.0, 100.0))))
        val conns = mutableListOf<FakeConnection>()
        val s = session(r, conns, FakePcm())
        running(s) {
            s.toggleLive(); runCurrent()
            val c = conns.single()
            c.server.trySend(RealtimeEvent.SessionReady)
            c.server.trySend(RealtimeEvent.FunctionCall("1", "remember", """{"category":"like","text":"Loves castles","topic":"architecture"}"""))
            c.server.trySend(RealtimeEvent.FunctionCall("2", "set_trip", """{"summary":"Road trip to Salzburg"}""")); runCurrent()
            assertEquals(listOf("Loves castles"), s.state.value.memory.map { it.text })
            assertEquals("Road trip to Salzburg", s.state.value.tripContext)
            // Silence ends the conversation on its own.
            advanceTimeBy(30_000); runCurrent()
            assertNull(s.state.value.live)
            assertTrue(c.closed)
        }
    }

    @Test
    fun radioControlsCloseAnOpenLiveConversation() = runTest {
        val r = Radio(listOf(place("a", Geo.destination(here, 0.0, 100.0))))
        val conns = mutableListOf<FakeConnection>()
        val pcm = FakePcm()
        val s = session(r, conns, pcm)
        running(s) {
            for ((name, control) in listOf<Pair<String, () -> Unit>>("resume" to { s.resume() }, "skip" to { s.skip() }, "pause" to { s.pause() }, "repeat" to { s.repeat() })) {
                s.toggleLive(); runCurrent()
                val c = conns.last()
                c.server.trySend(RealtimeEvent.SessionReady); runCurrent()
                assertTrue(pcm.capturing)
                control(); runCurrent()
                assertTrue(c.closed, "$name must close the live session")
                assertEquals(false, pcm.capturing)
                assertNull(s.state.value.live)
                s.resume(); runCurrent()
            }
        }
    }

    @Test
    fun typedQuestionWhileHostSpeaksCancelsTheActiveResponse() = runTest {
        val r = Radio(listOf(place("a", Geo.destination(here, 0.0, 100.0))))
        val conns = mutableListOf<FakeConnection>()
        val pcm = FakePcm()
        val s = session(r, conns, pcm)
        running(s) {
            s.toggleLive(); runCurrent()
            val c = conns.single()
            c.server.trySend(RealtimeEvent.SessionReady)
            c.server.trySend(RealtimeEvent.AudioDelta(ByteArray(480))); runCurrent()
            s.ask("Actually, what about the lake?"); runCurrent()
            val types = c.types()
            val cancel = types.lastIndexOf("response.cancel")
            assertTrue(cancel >= 0 && cancel < types.lastIndexOf("conversation.item.create"), types.toString())
            assertEquals("response.create", types.last())
            // Leftover audio of the cancelled answer is not played.
            val before = pcm.played
            c.server.trySend(RealtimeEvent.AudioDelta(ByteArray(480))); runCurrent()
            assertEquals(before, pcm.played)
        }
    }

    @Test
    fun setupErrorBeforeAnyAudioEndsLiveWithAMessage() = runTest {
        val r = Radio(listOf(place("a", Geo.destination(here, 0.0, 100.0))))
        val conns = mutableListOf<FakeConnection>()
        val s = session(r, conns, FakePcm())
        running(s) {
            s.toggleLive(); runCurrent()
            val c = conns.single()
            c.server.trySend(RealtimeEvent.SessionReady)
            c.server.trySend(RealtimeEvent.Error("Invalid value: 'onyx'. Supported values are: ...")); runCurrent()
            assertNull(s.state.value.live)
            assertTrue(c.closed)
            assertTrue(s.state.value.status!!.text.startsWith("Voice conversation unavailable"))
        }
    }

    @Test
    fun ttsOnlyVoicesFallBackForTheLiveModel() {
        assertEquals("coral", RealtimeProtocol.liveVoice("onyx"))
        assertEquals("marin", RealtimeProtocol.liveVoice("Marin"))
    }

    @Test
    fun backToRadioDropsAPendingOffer() = runTest {
        val rich = "The castle was built on a rock in the lake. ".repeat(30)
        val r = Radio(
            listOf(
                place("a", Geo.destination(here, 0.0, 100.0)),
                place("b", Geo.destination(here, 90.0, 110.0)),
                place("c", Geo.destination(here, 180.0, 120.0)).copy(extract = rich),
            ),
        )
        val conns = mutableListOf<FakeConnection>()
        val s = session(r, conns, FakePcm())
        running(s) {
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            var t = 0
            while (s.state.value.pendingOffer == null && t < 600) { advanceTimeBy(1_000); runCurrent(); t++ }
            assertEquals("c", s.state.value.pendingOffer)
            s.resume(); runCurrent()
            assertNull(s.state.value.pendingOffer)
            assertTrue(conns.all { it.closed })
        }
    }
}
