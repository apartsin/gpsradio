package com.gpsradio.core

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.Topic
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

class RadioSessionTest {
    private val here = GeoPoint(47.61, 13.78)

    private class Fakes(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore {
        val narrated = mutableListOf<String>()
        val asked = mutableListOf<ConversationRequest>()
        var reply = ConversationReply("It is documented history, not legend.")
        var stored: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrated += req.candidate.place.id
            return Segment("Story about ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest): ConversationReply { asked += req; return reply }
        override suspend fun synthesize(text: String, language: String) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String) = String(audio)
        override suspend fun play(audio: ByteArray) = delay(20_000)
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    private fun TestScope.session(f: Fakes): RadioSession = RadioSession(
        places = f, narrator = f, speech = f, audio = f, historyStore = f,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY)) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.withSession(f: Fakes, body: TestScope.(RadioSession) -> Unit) {
        val s = session(f)
        try {
            s.start(); runCurrent()
            body(s)
        } finally {
            s.stop(); runCurrent()
        }
    }

    private fun fix(t: Long) = LocationSample(here.lat, here.lon, 5f, t + 1_000_000, 0f)

    @Test
    fun narratesBestCandidateOnceThenStaysSilent() = runTest {
        val f = Fakes(listOf(place("castle", Geo.destination(here, 0.0, 150.0))))
        withSession(f) { s ->
        s.onLocation(fix(0)); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(RadioState.NARRATING, s.state.value.radioState)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(listOf("castle"), f.narrated)
        assertEquals(RadioState.RADIO, s.state.value.radioState)
        assertTrue(f.stored!!.contains("castle"))
        }
    }

    @Test
    fun interruptionAnswersInContextThenReturnsToRadio() = runTest {
        val f = Fakes(listOf(place("castle", Geo.destination(here, 0.0, 150.0)), place("tower", Geo.destination(here, 90.0, 200.0))))
        withSession(f) { s ->
        s.onLocation(fix(0)); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        val first = f.narrated.single()
        s.ask("Is that actually true?"); runCurrent()
        assertEquals(RadioState.CONVERSING, s.state.value.radioState)
        assertEquals(first, f.asked.single().active?.place?.id)
        f.reply = ConversationReply("Back to the radio.", ConversationAction.RESUME_RADIO)
        advanceTimeBy(30_000); runCurrent()
        s.ask("ok, go on with the stories please"); runCurrent()
        advanceTimeBy(25_000); runCurrent()
        // Back in radio mode: the scheduler moves on to the next story rather than staying in conversation.
        assertTrue(s.state.value.radioState != RadioState.CONVERSING)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(listOf("castle", "tower"), f.narrated.sorted())
        assertTrue(s.state.value.transcript.any { it.speaker == Speaker.USER })
        }
    }

    @Test
    fun skipStopsSegmentAndDoesNotReplayIt() = runTest {
        val f = Fakes(listOf(place("castle", Geo.destination(here, 0.0, 150.0))))
        withSession(f) { s ->
        s.onLocation(fix(0)); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        s.skip(); runCurrent()
        assertEquals(RadioState.RADIO, s.state.value.radioState)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, f.narrated.size)
        }
    }
}
