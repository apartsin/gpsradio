package com.gpsradio.core

import com.gpsradio.core.model.Speaker
import java.io.IOException
import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.favorites.FavoritePlace
import com.gpsradio.core.favorites.Favorites
import com.gpsradio.core.favorites.FavoritesStore
import com.gpsradio.core.favorites.ShareText
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewRound2Test {
    private val here = GeoPoint(47.61, 13.78)
    private val longFacts = "The castle was built on a rock in the lake. ".repeat(30)

    private class Fake(val list: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore, FavoritesStore {
        val narrations = mutableListOf<Pair<String, SegmentFormat>>()
        val asked = mutableListOf<ConversationRequest>()
        val spoken = mutableListOf<String>()
        var reply = ConversationReply("ok")
        var hist: String? = null
        var favs: String? = null
        var styles = mutableListOf<HostStyle>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun gallery(place: PlaceCandidate) = listOf("https://img/1.jpg", "https://img/2.jpg")
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrations += req.candidate.place.id to req.format
            styles += req.style
            val text = if (req.format == SegmentFormat.TEASER) "TEASER ${req.candidate.place.id}" else "STORY ${req.candidate.place.id}"
            return Segment(text, req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply { asked += req; return reply }
        override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle) = "Where are we heading?"
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray { spoken += text; return text.toByteArray() }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = String(audio)
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.session(f: Fake, audio: AudioOutput = AudioOutput { delay(10_000) }) = RadioSession(
        places = f, narrator = f, speech = f, audio = audio, historyStore = f,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY)) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        teaserGapMs = 0,
    )

    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try { s.start(); runCurrent(); body() } finally { s.stop(); runCurrent() }
    }

    /** Advances virtual time in 1 s steps until [cond] holds (or fails after [maxMs]). */
    private fun TestScope.advanceUntil(maxMs: Long = 600_000, cond: () -> Boolean) {
        var t = 0L
        while (!cond()) {
            check(t < maxMs) { "condition not reached within ${maxMs / 1000} s" }
            advanceTimeBy(1_000); runCurrent()
            t += 1_000
        }
    }

    private fun fix(speed: Float = 0f, t: Long = 0) = LocationSample(here.lat, here.lon, 5f, 1_000_000 + t, speed, 0f)

    private fun rich(id: String, bearing: Double, d: Double) =
        place(id, Geo.destination(here, bearing, d)).copy(extract = longFacts)

    @Test
    fun retellClosesAPendingOfferBeforeTelling() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 100.0)), place("b", Geo.destination(here, 90.0, 110.0)), rich("c", 180.0, 120.0)))
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceUntil { s.state.value.pendingOffer != null }
            s.retell("a", "a"); runCurrent()
            assertNull(s.state.value.pendingOffer)
            advanceTimeBy(2_000); runCurrent()
            assertEquals("a" to SegmentFormat.STORY, f.narrations.last())
        }
    }

    @Test
    fun answerThatCannotBePlayedIsReportedNotCrashed() = runTest {
        val f = Fake(emptyList())
        f.reply = ConversationReply("The tower is 40 metres tall.")
        val s = session(f, audio = AudioOutput { bytes -> if (String(bytes).startsWith("The tower")) throw IOException("Audio is busy") else delay(1_000) })
        running(s) {
            s.onLocation(fix()); runCurrent()
            s.ask("How tall is the tower?"); runCurrent()
            advanceTimeBy(2_000); runCurrent()
            assertTrue(s.state.value.status!!.text.startsWith("Couldn't play the answer"), s.state.value.status!!.text)
            // The answer is still readable and the session keeps working.
            assertTrue(s.state.value.transcript.any { it.speaker == Speaker.RADIO && it.text.startsWith("The tower") })
            f.reply = ConversationReply("Ok.")
            s.ask("Thanks"); runCurrent()
            advanceTimeBy(2_000); runCurrent()
            assertEquals(2, f.asked.size)
        }
    }

    @Test
    fun walkingTourEndsWhenTheListenerStartsDriving() = runTest {
        val f = Fake((0 until 6).map { i -> rich("t$i", i * 60.0, 150.0 + i * 20) })
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            s.startTour(30); runCurrent()
            advanceUntil { s.state.value.tour != null }
            advanceTimeBy(15_000); runCurrent()
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            s.onLocation(fix(t = 16_000)); runCurrent()
            advanceUntil(60_000) { s.state.value.tour == null }
            assertTrue(s.state.value.transcript.any { it.text == RadioSession.TOUR_ABANDONED })
        }
    }
}
