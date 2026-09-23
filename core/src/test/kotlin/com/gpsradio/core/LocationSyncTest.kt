package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Played content must match where the listener is when the audio starts, especially at driving speed. */
class LocationSyncTest {
    private val start = GeoPoint(47.80, 13.70)

    private class Fake(val list: List<PlaceCandidate>, val narrateMs: Long) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        val requests = mutableListOf<NarrationRequest>()
        val played = mutableListOf<String>()
        var hist: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun narrate(req: NarrationRequest): Segment {
            requests += req
            delay(narrateMs)
            return Segment(req.candidate.place.id, req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.session(f: Fake) = RadioSession(
        places = f, narrator = f, speech = f,
        audio = AudioOutput { bytes -> f.played += String(bytes); delay(20_000) },
        historyStore = f,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY), askAboutTrip = false) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Drives north at [speed] m/s, one fix per second, for [seconds]. */
    private fun TestScope.drive(s: RadioSession, from: Long, seconds: Int, speed: Float = 25f) {
        for (i in 0 until seconds) {
            val t = from + i
            val p = Geo.destination(start, 0.0, speed * t.toDouble())
            s.onLocation(LocationSample(p.lat, p.lon, 5f, 1_000_000 + t * 1_000, speed, 0f))
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    @Test
    fun narrationDescribesThePlaceFromWhereTheListenerWillBe() = runTest {
        val castle = place("castle", Geo.destination(start, 0.0, 3_000.0))
        val f = Fake(listOf(castle), narrateMs = 3_000)
        val s = session(f)
        try {
            s.start(); runCurrent()
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            drive(s, 0, 12)
            val req = f.requests.first()
            val listenerNow = Geo.distanceM(start, castle.point) - 25.0 * 1
            // Projected ahead by the expected preparation time (~6 s at 25 m/s ≈ 150 m closer).
            assertTrue(req.candidate.distanceM < listenerNow - 100, "distance ${req.candidate.distanceM} should be projected ahead")
            assertEquals(req.candidate.distanceM, Geo.distanceM(req.location.point, castle.point), 1.0)
        } finally {
            s.stop(); runCurrent()
        }
    }

    @Test
    fun storyForAPlacePassedDuringPreparationIsDropped() = runTest {
        // 300 m ahead at 25 m/s: passed after ~12 s, but narration takes 20 s (within the 25 s timeout).
        val near = place("near", Geo.destination(start, 0.0, 300.0))
        val f = Fake(listOf(near), narrateMs = 20_000)
        val s = session(f)
        try {
            s.start(); runCurrent()
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            drive(s, 0, 60)
            assertEquals(1, f.requests.size, f.requests.map { "${it.candidate.place.id}@${it.location.timestampMs}" }.toString())
            assertTrue(f.played.isEmpty(), "a passed place must not be narrated: ${f.played}")
        } finally {
            s.stop(); runCurrent()
        }
    }

    @Test
    fun placeStillAheadIsPlayed() = runTest {
        val far = place("far", Geo.destination(start, 0.0, 5_000.0))
        val f = Fake(listOf(far), narrateMs = 5_000)
        val s = session(f)
        try {
            s.start(); runCurrent()
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            drive(s, 0, 30)
            assertEquals(listOf("far"), f.played)
        } finally {
            s.stop(); runCurrent()
        }
    }

    @Test
    fun drivingPromptAvoidsStaleDistances() {
        val p = RadioAgent.narrationInstructions("en-US")
        assertTrue("time_to_reach_s" in p && "coming up on your left" in p)
    }
}
