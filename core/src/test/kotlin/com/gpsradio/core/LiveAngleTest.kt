package com.gpsradio.core

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.discovery.AngleScope
import com.gpsradio.core.discovery.AngleScout
import com.gpsradio.core.discovery.AngleTarget
import com.gpsradio.core.discovery.StoryAngle
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** Live: the angle scout finds a specific, local, sourced item (web search). Skipped without a key. */
class LiveAngleTest {
    private val key: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

    @Test
    fun researchesALocalAngleWithSources() = runBlocking {
        assumeTrue(key != null, "OPENAI_API_KEY not set; live test skipped")
        val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val scout = AngleScout(OpenAiClient(http, { key!! }), { ModelConfig() })
        val area = AreaLabel("Gmunden", "Upper Austria", "AT")
        val point = GeoPoint(47.918, 13.799)
        for (angle in listOf(StoryAngle.WATER, StoryAngle.CRAFTS)) {
            val f = scout.research(AngleTarget(AngleScope.TOWN, "Gmunden", angle), area, point, emptyList())
            println("ANGLE ${angle.key}: ${f?.title} | ${f?.facts?.take(300)} | ${f?.url}")
            assertTrue(f != null && f.facts.length >= 80, "found something for ${angle.key}")
        }
        val steer = scout.research(AngleTarget(AngleScope.TOWN, "Gmunden", null, custom = "the fish in the lake"), area, point, emptyList())
        println("STEER: ${steer?.title} | ${steer?.facts?.take(300)}")
        assertTrue(steer != null, "a listener's steer is researched")
    }
}
