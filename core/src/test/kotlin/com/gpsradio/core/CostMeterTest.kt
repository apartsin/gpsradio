package com.gpsradio.core

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RealtimeProtocol
import com.gpsradio.core.cost.CostMeter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Estimated spend and the daily cap (spec A §41). */
class CostMeterTest {
    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun pricesTokensSearchesSpeechAndRealtime() {
        var now = 1_758_600_000_000L // a fixed day
        val m = CostMeter(clock = { now }, zone = { ZoneOffset.UTC })
        // gpt-4.1: 3,000 input (1,000 cached), 400 output: (2000·2 + 1000·0.5 + 400·8) / 1e6.
        m.recordResponse("gpt-4.1", 3_000, 1_000, 400, 0, CostMeter.Kind.STORIES)
        near((2_000 * 2.0 + 1_000 * 0.5 + 400 * 8.0) / 1e6, m.todayUsd())
        // gpt-4.1-mini with one web search.
        val before = m.todayUsd()
        m.recordResponse("gpt-4.1-mini", 1_000, 0, 200, 1, CostMeter.Kind.RESEARCH)
        near((1_000 * 0.4 + 200 * 1.6) / 1e6 + 0.025, m.todayUsd() - before)
        // A minute of story voice (~900 characters) ≈ $0.015.
        val b2 = m.todayUsd()
        m.recordSpeech(900)
        near(0.015, m.todayUsd() - b2)
        // Realtime response.done usage.
        val usage = Json.parseToJsonElement(
            """{"input_token_details":{"text_tokens":2000,"cached_tokens":1500,"audio_tokens":300},"output_token_details":{"text_tokens":50,"audio_tokens":600}}""",
        ).jsonObject
        val b3 = m.todayUsd()
        m.recordRealtime(usage)
        near((500 * 4.0 + 1_500 * 0.4 + 300 * 32.0 + 50 * 16.0 + 600 * 64.0) / 1e6, m.todayUsd() - b3)
        assertEquals(setOf(CostMeter.Kind.STORIES, CostMeter.Kind.RESEARCH, CostMeter.Kind.VOICE, CostMeter.Kind.LIVE), m.totals.value.todayByKind.keys)
        // A new day starts at zero; the session total keeps counting.
        val session = m.totals.value.session
        now += 24L * 3600 * 1000
        m.recordSpeech(90)
        near(0.0015, m.todayUsd())
        near(session + 0.0015, m.totals.value.session)
    }

    @Test
    fun survivesRestartsAndPricesUnknownModelsHigh() {
        var saved: String? = null
        val now = 1_758_600_000_000L
        val a = CostMeter(clock = { now }, zone = { ZoneOffset.UTC }, onChange = { saved = it })
        a.recordResponse("gpt-9-turbo", 1_000_000, 0, 0, 0, CostMeter.Kind.STORIES)
        near(2.0, a.todayUsd()) // unknown → priced like the full model, never under-estimated
        val b = CostMeter(clock = { now }, zone = { ZoneOffset.UTC })
        b.restore(saved)
        near(2.0, b.todayUsd())
        near(2.0, b.totals.value.today)
    }

    @Test
    fun theOpenAiClientReportsWhatEachCallCost() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"status":"completed","output":[{"type":"web_search_call","status":"completed"},{"type":"message","content":[{"type":"output_text","text":"ok","annotations":[]}]}],
                   "usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":0},"output_tokens":100}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("MP3"))
        server.start()
        try {
            val m = CostMeter()
            val client = OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString(), meter = m)
            val r = client.respond(OpenAiClient.ResponseRequest(ModelConfig().researchModel, "x", listOf(OpenAiClient.Message("user", "q")), webSearch = true))
            assertEquals(1, r.webSearches)
            assertEquals(100, r.outputTokens)
            near((1_000 * 0.4 + 100 * 1.6) / 1e6 + 0.025, m.todayUsd())
            assertTrue((m.totals.value.todayByKind[CostMeter.Kind.RESEARCH] ?: 0.0) > 0.0)
            client.speech("x".repeat(900), "gpt-4o-mini-tts", "coral")
            assertTrue((m.totals.value.todayByKind[CostMeter.Kind.VOICE] ?: 0.0) > 0.0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun realtimeUsageIsReadFromResponseDone() {
        val raw = """{"type":"response.done","response":{"status":"completed","usage":{"total_tokens":10,"input_token_details":{"text_tokens":5},"output_token_details":{"audio_tokens":5}}}}"""
        val u = RealtimeProtocol.usage(raw)!!
        assertTrue("output_token_details" in u)
        assertEquals(null, RealtimeProtocol.usage("""{"type":"response.created"}"""))
    }
}
