package com.gpsradio.core

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.OpenAiSpeech
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live, real-time drive: a simulated car drives the B145 along Traunsee (Gmunden → Traunkirchen) at
 * ~70 km/h with real Wikipedia/OSM discovery, real OpenAI narration and TTS. Checks that stories are
 * produced autonomously and that every story starts while its place is not yet behind the car.
 * Skipped without OPENAI_API_KEY; takes ~4 minutes.
 */
class LiveDriveSimulationTest {
    private val key: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

    private val route = listOf(
        GeoPoint(47.9186, 13.7996), GeoPoint(47.9050, 13.7975), GeoPoint(47.8905, 13.7930),
        GeoPoint(47.8760, 13.7890), GeoPoint(47.8620, 13.7855), GeoPoint(47.8480, 13.7880),
        GeoPoint(47.8410, 13.7930),
    )

    /** Position along the route after travelling [metres]. */
    private fun along(metres: Double): Pair<GeoPoint, Double> {
        var left = metres
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val seg = Geo.distanceM(a, b)
            val bearing = Geo.bearingDeg(a, b)
            if (left <= seg) return Geo.destination(a, bearing, left) to bearing
            left -= seg
        }
        val last = route.last()
        return last to Geo.bearingDeg(route[route.size - 2], last)
    }

    @Test
    fun storiesFlowAndStayInSyncWhileDriving() = runBlocking {
        assumeTrue(key != null, "OPENAI_API_KEY not set; live drive skipped")
        val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        val ua = "GpsRadio-CI/0.1 (https://github.com/apartsin/gpsradio)"
        val openAi = OpenAiClient(http, { key!! })
        val models = ModelConfig()
        val agent = RadioAgent(openAi, { models })
        val speed = 19.0 // m/s ≈ 70 km/h
        val startMs = System.currentTimeMillis()
        fun carNow(): Pair<GeoPoint, Double> = along(speed * (System.currentTimeMillis() - startMs) / 1000.0)

        data class Played(val place: String, val placePoint: GeoPoint, val car: GeoPoint, val heading: Double)
        val played = Collections.synchronizedList(mutableListOf<Played>())
        var lastReq: NarrationRequest? = null
        val narrator = object : Narrator by agent {
            override suspend fun narrate(req: NarrationRequest) = agent.narrate(req).also { lastReq = req }
        }
        val session = RadioSession(
            places = DiscoveryService(WikipediaClient(http, ua), OverpassClient(http, ua)),
            narrator = narrator,
            speech = OpenAiSpeech(openAi, { models }),
            audio = AudioOutput { _ ->
                val (car, heading) = carNow()
                lastReq?.let { played += Played(it.candidate.place.name, it.candidate.place.point, car, heading) }
                delay(8_000) // pretend playback (shortened so more stories fit in the drive)
            },
            historyStore = object : HistoryStore {
                override fun load(): String? = null
                override fun save(serialized: String) = Unit
            },
            config = { SessionConfig("en-US", setOf(Topic.HISTORY, Topic.NATURE, Topic.ARCHITECTURE), askAboutTrip = false) },
        )
        session.start()
        session.setModeOverride(TravelMode.DRIVING)
        val totalMetres = (0 until route.size - 1).sumOf { Geo.distanceM(route[it], route[it + 1]) }
        while (speed * (System.currentTimeMillis() - startMs) / 1000.0 < totalMetres) {
            val (p, heading) = carNow()
            session.onLocation(LocationSample(p.lat, p.lon, 5f, System.currentTimeMillis(), speed.toFloat(), heading.toFloat()))
            delay(1_000)
        }
        session.stop()
        delay(500)

        println("DRIVE: ${played.size} stories in ${(System.currentTimeMillis() - startMs) / 1000} s")
        val outOfSync = played.filter { p ->
            val behind = Geo.angleDiff(Geo.bearingDeg(p.car, p.placePoint), p.heading) > 110
            val d = Geo.distanceM(p.car, p.placePoint)
            println("DRIVE story: ${p.place} at ${d.toInt()} m, ${if (behind) "BEHIND" else "ahead/side"}")
            behind && d > 250
        }
        assertTrue(played.size >= 2, "expected at least 2 stories during the drive, got ${played.size}")
        assertTrue(outOfSync.isEmpty(), "stories started after passing their place: ${outOfSync.map { it.place }}")
    }
}
