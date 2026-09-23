package com.gpsradio.core

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.events.EventScout
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live: events today in a big city via real web search. Results vary by day, so this checks the
 * pipeline and the filters (never a specific event). Skipped without OPENAI_API_KEY.
 */
class LiveEventsTest {
    @Test
    fun findsOnlyVisitorWorthyLinkedEventsForToday() = runBlocking {
        val key = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
        assumeTrue(key != null, "OPENAI_API_KEY not set; live events test skipped")
        val http = OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
        val scout = EventScout(OpenAiClient(http, { key!! }), { ModelConfig() })
        val zone = ZoneId.of("Europe/Vienna")
        val now = System.currentTimeMillis()
        val events = scout.find(AreaLabel("Vienna", "Vienna", "AT"), GeoPoint(48.2082, 16.3738), now, zone, "en-US")
        println("LIVE EVENTS (${events.size}):")
        events.forEach { println("  ${EventScout.clock(it.startMs, zone)} [${it.category}] ${it.title} @ ${it.venue} — ${it.url}") }
        events.forEach { e ->
            assertTrue(EventScout.isRelevant(e), "irrelevant: $e")
            assertTrue(e.url.startsWith("http"), "no link: $e")
            assertTrue(e.startMs <= now + EventScout.WINDOW_HOURS * 3_600_000L, "outside the window: $e")
        }
    }
}
