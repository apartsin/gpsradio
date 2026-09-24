package com.gpsradio.core

import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.RealtimeProtocol
import com.gpsradio.core.discovery.AngleScope
import com.gpsradio.core.discovery.AngleScout
import com.gpsradio.core.discovery.AngleTarget
import com.gpsradio.core.discovery.StoryAngle
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The requests and prompts behind spec A §36–§39: mic closed, models, research gate, craft rules, steering. */
class RecentChangesTest {
    private val here = GeoPoint(47.918, 13.799)
    private val walking = LocationContext(here, 8f, 0, 1.3, 0.0, TravelMode.WALKING)
    private val castle = place("wiki:en:1", Geo.destination(here, 0.0, 150.0), name = "Schloss Ort")
    private val ranked = RankedCandidate(castle, 150.0, 0.0, 3.0, ScoreBreakdown(0.8, 1.0, 1.0, 0.8, 1.0, 0.9, 0.0, 0.0))

    private fun responses(text: String) =
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}]}"""

    private fun <T> withServer(vararg bodies: String, block: suspend (MockWebServer, OpenAiClient) -> T): T {
        val server = MockWebServer()
        bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
        server.start()
        try {
            return runBlocking { block(server, OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString())) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun storiesUseTheFullModelAndSayWhenTheListenerCantReply() {
        val story = """{"text":"Schloss Ort sits on its own island.","basis":"documented"}"""
        withServer(responses(story), responses(story)) { server, openAi ->
            val agent = RadioAgent(openAi, { ModelConfig() })
            agent.narrate(NarrationRequest(ranked, walking, "ru-RU", setOf(Topic.HISTORY), emptyList(), canReply = false))
            val closed = server.takeRequest().body.readUtf8()
            assertTrue("\"model\":\"gpt-5.1\"" in closed, "stories on gpt-5.1")
            assertTrue("\"effort\":\"none\"" in closed, "no deliberation: fast")
            // The per-request context (escaped JSON inside the input), not the instructions that describe the rule.
            val flag = "listener_can_reply\\\":false"
            assertTrue(flag in closed, "mic closed is told to the narrator")
            agent.narrate(NarrationRequest(ranked, walking, "ru-RU", setOf(Topic.HISTORY), emptyList()))
            assertFalse(flag in server.takeRequest().body.readUtf8(), "mic open: nothing extra")
        }
    }

    @Test
    fun researchUsesTheFastModelWithWebSearchAndTheInterestGate() {
        val facts = "The Traunsee is the deepest lake in Austria at 191 metres; its char was served at the imperial court. ".repeat(2)
        val good = """{"found":true,"title":"The deep lake","facts":${JsonPrimitive(facts)},"interest":5}"""
        val dull = """{"found":true,"title":"The town hall","facts":${JsonPrimitive(facts)},"interest":3}"""
        withServer(responses(good), responses(dull)) { server, openAi ->
            val scout = AngleScout(openAi, { ModelConfig() })
            val area = AreaLabel("Gmunden", "Upper Austria", "AT")
            val f = scout.research(AngleTarget(AngleScope.TOWN, "Gmunden", StoryAngle.WATER), area, here, listOf("Schloss Ort"))
            assertNotNull(f)
            assertEquals("The deep lake", f.title)
            assertEquals("water", f.angle)
            assertEquals("Gmunden", f.area)
            val body = server.takeRequest().body.readUtf8()
            assertTrue("\"model\":\"gpt-4.1-mini\"" in body, "research on the fast model")
            assertTrue("web_search" in body)
            assertTrue("\"interest\"" in body, "the schema asks for an interest score")
            assertTrue("Schloss Ort" in body, "already told is passed on")
            // Only 4–5/5 is told.
            assertNull(scout.research(AngleTarget(AngleScope.TOWN, "Gmunden", StoryAngle.CRAFTS), area, here, emptyList()))
        }
    }

    @Test
    fun aListenerRequestIsResearchedAsTheirOwnWords() {
        val facts = "Char and whitefish live in the Traunsee; fishing rights go back to the 14th century. ".repeat(2)
        withServer(responses("""{"found":true,"title":"Fish of the lake","facts":${JsonPrimitive(facts)},"interest":4}""")) { server, openAi ->
            val f = AngleScout(openAi, { ModelConfig() })
                .research(AngleTarget(AngleScope.TOWN, "Gmunden", null, custom = "the fish in the lake"), null, here, emptyList())
            assertEquals("request", f?.angle)
            val body = server.takeRequest().body.readUtf8()
            assertTrue("listener_request" in body && "the fish in the lake" in body)
        }
    }

    @Test
    fun narrationPromptHasTheCraftRulesAndTheAngleCatalogue() {
        val p = RadioAgent.narrationInstructions("ru-RU")
        assertTrue("ENGAGING, DENSE, CLEAR, FUN" in p && "3 to 5 sentences" in p && "SHORT" in p)
        assertFalse("which is wild, if you" in p, "no filler reactions")
        for (banned in listOf("rich history", "nestled", "charming", "testament to", "boasts")) assertTrue(banned in p, banned)
        assertTrue("Pick the angle" in p && "legends" in p && "film and TV" in p && "street names" in p)
        assertTrue("listener_can_reply" in p, "the mic-closed rule")
        assertTrue("opening hours, prices, times and distances: say those exactly" in p)
        assertEquals(50, StoryAngle.promptList().lines().size)
        assertTrue(StoryAngle.promptList().lines().first().contains(StoryAngle.entries.first { it.tier == 1 }.label))
    }

    @Test
    fun liveVoiceCanSteerAndAnswersAtOnce() {
        val req = ConversationRequest("", "ru-RU", walking, AreaLabel("Gmunden", "Upper Austria", "AT"), ranked, emptyList(), emptyList(), emptyList(), null)
        val live = RadioAgent.liveInstructions(req)
        assertTrue("Answer at once" in live)
        assertTrue("tell_about" in live && "steer" in live && "request" in live)
        val control = RealtimeProtocol.tools.first { it.name == "radio_control" }
        assertTrue("steer" in control.description && "tell_about" in control.description)
        assertTrue("request" in control.parameters.toString(), "steer carries the listener's wish")
    }

    @Test
    fun defaultsAreTheFullModelsForTalkAndTheFastOneForResearch() {
        val m = ModelConfig()
        assertEquals("gpt-5.1", m.narrationModel)
        assertEquals("gpt-4.1", m.conversationModel)
        assertEquals("gpt-4.1-mini", m.researchModel)
        assertEquals("gpt-realtime", m.realtimeModel)
    }
}

/** Spec A §53: a newer story model never breaks an account without it; stories are short. */
class StoryModelTest {
    @Test
    fun anUnavailableModelFallsBackOnceAndIsRemembered() = kotlinx.coroutines.runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"The model `gpt-5.1` does not exist or you do not have access to it.","code":"model_not_found"}}"""))
        val ok = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hi","annotations":[]}]}]}"""
        server.enqueue(MockResponse().setBody(ok))
        server.enqueue(MockResponse().setBody(ok))
        server.start()
        try {
            val client = OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString())
            val req = OpenAiClient.ResponseRequest("gpt-5.1", "i", listOf(OpenAiClient.Message("user", "x")))
            assertEquals("Hi", client.respond(req).text)
            assertTrue("\"model\":\"gpt-5.1\"" in server.takeRequest().body.readUtf8())
            val second = server.takeRequest().body.readUtf8()
            assertTrue("\"model\":\"gpt-5\"" in second && "\"effort\":\"minimal\"" in second, second)
            client.respond(req)
            assertTrue("\"model\":\"gpt-5\"" in server.takeRequest().body.readUtf8(), "remembered: no second 404")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun storiesAreShort() {
        assertEquals(25, RadioAgent.targetSeconds(com.gpsradio.core.model.TravelMode.WALKING))
        assertEquals(20, RadioAgent.targetSeconds(com.gpsradio.core.model.TravelMode.DRIVING))
        assertEquals(25, RadioAgent.targetSeconds(com.gpsradio.core.model.TravelMode.WALKING, com.gpsradio.core.ai.SegmentFormat.AREA))
    }

    @Test
    fun anotherStoryIsASkipAndTheHostNeverAsksWhatToPlay() {
        for (p in listOf("Расскажи другую историю", "Другая история", "Ещё историю!", "Tell me something else", "surprise me")) {
            assertEquals(com.gpsradio.core.ai.ConversationAction.SKIP, com.gpsradio.core.session.RadioSession.localCommand(p), p)
        }
        // "Tell me more" continues the current story; it is not a skip.
        assertNull(com.gpsradio.core.session.RadioSession.localCommand("Расскажи ещё"))
        val live = RadioAgent.liveInstructions(
            ConversationRequest("", "ru-RU", null, null, null, emptyList(), emptyList(), emptyList(), null),
        )
        assertTrue("Never ask the listener what they'd like to hear" in live)
    }
}
