package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
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

/** With the mic closed the listener can't answer, so the radio asks nothing (spec A §36). */
class MicClosedTest {
    private val here = GeoPoint(47.61, 13.78)

    private class Radio(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore {
        val requests = mutableListOf<NarrationRequest>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest): Segment {
            requests += req
            val text = if (req.format == SegmentFormat.TEASER) "A castle with a secret. Want the full story?" else "Story about ${req.candidate.place.name}."
            return Segment(text, req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) = delay(10_000)
        override fun load(): String? = null
        override fun save(serialized: String) {}
    }

    // Long facts: every place is eligible for a "want the full story?" teaser.
    private fun rich(id: String, bearing: Double, d: Double) =
        place(id, Geo.destination(here, bearing, d), name = id).copy(extract = "Facts about $id. ".repeat(80))

    private fun TestScope.run(canReply: Boolean): Radio {
        val r = Radio((1..6).map { rich("p$it", it * 60.0, 100.0 + it * 20) })
        val s = RadioSession(
            places = r, narrator = r, speech = r, audio = r, historyStore = r,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY), askPreferences = true, canReply = canReply) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            teaserGapMs = 0,
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            repeat(40) {
                advanceTimeBy(15_000); runCurrent()
                if (!canReply) {
                    assertEquals(null, s.state.value.pendingOffer, "no offer while the mic is closed")
                    assertTrue(s.state.value.radioState != RadioState.CONVERSING, "never waits for an answer")
                }
            }
        } finally {
            s.stop(); runCurrent()
        }
        return r
    }

    @Test
    fun micClosedMeansNoTeasersOffersOrQuestions() = runTest {
        val r = run(canReply = false)
        assertTrue(r.requests.size >= 4, "stories still air: ${r.requests.size}")
        assertTrue(r.requests.none { it.format == SegmentFormat.TEASER }, "no teasers")
        assertTrue(r.requests.all { !it.canReply || it.format != SegmentFormat.STORY }, "stories are told without offers")
    }

    @Test
    fun micOpenStillOffersTheFullStory() = runTest {
        val r = run(canReply = true)
        assertTrue(r.requests.any { it.format == SegmentFormat.TEASER }, "teasers when the listener can answer")
    }
}
