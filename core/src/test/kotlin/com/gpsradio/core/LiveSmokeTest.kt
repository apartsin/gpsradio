package com.gpsradio.core

import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.OpenAiSpeech
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real services, end to end: Wikipedia/OSM discovery → ranking → OpenAI narration → answer → TTS.
 * Skipped unless OPENAI_API_KEY is set (CI reads it from a repository secret; never commit a key).
 * Catches API/model drift that fakes cannot.
 */
class LiveSmokeTest {
    private val key: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

    @Test
    fun realDiscoveryNarrationAnswerAndSpeech() = runBlocking {
        assumeTrue(key != null, "OPENAI_API_KEY not set; live smoke test skipped")
        val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        val ua = "GpsRadio-CI/0.1 (https://github.com/apartsin/gpsradio)"
        val here = GeoPoint(47.9180, 13.7990) // Gmunden, Austria: lake, castle, well documented
        val places = DiscoveryService(WikipediaClient(http, ua), OverpassClient(http, ua)).discover(here, 1500, "en")
        assertTrue(places.size >= 3, "expected nearby places, got ${places.size}")

        val loc = LocationContext(here, 10f, System.currentTimeMillis(), 1.2, null, TravelMode.WALKING)
        val ranked = EditorialRanker().rank(places, EditorialRanker.Context(loc, mapOf(Topic.HISTORY to 1.0), HeardHistory(), System.currentTimeMillis()))
        val top = ranked.first()

        val models = ModelConfig()
        val openAi = OpenAiClient(http, { key!! })
        val agent = RadioAgent(openAi, { models })
        val segment = agent.narrate(NarrationRequest(top, loc, "en-US", setOf(Topic.HISTORY), emptyList()))
        println("STORY about ${top.place.name}: ${segment.text}")
        assertTrue(segment.text.split(" ").size in 20..200, "narration length out of range")
        println("BASIS: ${segment.basis}")
        assertTrue(segment.basis != null, "structured narration should report its claim basis")

        val reply = agent.converse(
            ConversationRequest("Tell me more about that.", "en-US", loc, null, top, ranked.drop(1).take(5), listOf(top.place.name), emptyList(), null),
        )
        println("ANSWER: ${reply.reply}")
        assertTrue(reply.reply.isNotBlank())

        val audio = OpenAiSpeech(openAi, { models }).synthesize(segment.text.take(200), "en-US")
        assertTrue(audio.size > 1_000, "expected MP3 audio bytes")
    }

    /** The natural-voice path: our session.update must be accepted and the model must answer in audio. */
    @Test
    fun realtimeVoiceSessionSpeaks() = runBlocking {
        assumeTrue(key != null, "OPENAI_API_KEY not set; live smoke test skipped")
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val conn = com.gpsradio.core.ai.RealtimeClient(http, { key!! }).connect(ModelConfig().realtimeModel)
        var audioBytes = 0
        var transcript: String? = null
        val errors = mutableListOf<String>()
        // Handle events until the first spoken response completes (or the socket closes / 60 s pass).
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            conn.events.first { e ->
                when (e) {
                    com.gpsradio.core.ai.RealtimeEvent.SessionReady -> {
                        conn.send(
                            com.gpsradio.core.ai.RealtimeProtocol.sessionUpdate(
                                "You are a friendly radio host. Keep it to one short sentence.",
                                ModelConfig().ttsVoice, ModelConfig().transcriptionModel, com.gpsradio.core.ai.RealtimeProtocol.tools,
                            ),
                        )
                        conn.send(com.gpsradio.core.ai.RealtimeProtocol.responseCreate("Say hello to the listener in Gmunden."))
                    }
                    is com.gpsradio.core.ai.RealtimeEvent.AudioDelta -> audioBytes += e.pcm.size
                    is com.gpsradio.core.ai.RealtimeEvent.AssistantTranscript -> transcript = e.text
                    is com.gpsradio.core.ai.RealtimeEvent.Error -> errors += e.message
                    else -> Unit
                }
                (e == com.gpsradio.core.ai.RealtimeEvent.ResponseDone && audioBytes > 0) || e is com.gpsradio.core.ai.RealtimeEvent.Closed
            }
        }
        conn.close()
        println("REALTIME: ${audioBytes} bytes of audio; transcript: $transcript; errors: $errors")
        assertTrue(errors.isEmpty(), "realtime errors: $errors")
        assertTrue(audioBytes > 10_000, "expected spoken audio from the realtime model")
    }
}
