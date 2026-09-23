package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
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

/** The host may ask about the listener's taste (once), but never quizzes their knowledge. */
class PreferenceQuestionTest {
    private val here = GeoPoint(47.61, 13.78)

    private class Fake(val list: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        val lines = mutableListOf<HostLine>()
        val formats = mutableListOf<SegmentFormat>()
        var hist: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun narrate(req: NarrationRequest): Segment {
            formats += req.format
            return Segment("STORY ${req.candidate.place.id}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle): String { lines += kind; return kind.fallback }
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.run(ask: Boolean, body: (Fake, RadioSession) -> Unit) {
        val f = Fake((0 until 8).map { i -> place("p$i", Geo.destination(here, i * 45.0, 100.0 + i * 10)).copy(extract = "Facts about place $i. ".repeat(20)) })
        val s = RadioSession(
            places = f, narrator = f, speech = f, audio = AudioOutput { delay(5_000) }, historyStore = f,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY), askPreferences = ask) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            teaserGapMs = Long.MAX_VALUE,
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            var t = 0
            while (t++ < 900) { advanceTimeBy(1_000); runCurrent() }
            body(f, s)
        } finally {
            s.stop(); runCurrent()
        }
    }

    @Test
    fun asksOnceAboutTasteAfterAFewStoriesAndNeverQuizzes() = runTest {
        run(ask = true) { f, s ->
            assertEquals(listOf(HostLine.PREFERENCE_QUESTION), f.lines)
            assertTrue(s.state.value.transcript.any { it.text == HostLine.PREFERENCE_QUESTION.fallback })
            assertTrue(f.formats.count { it == SegmentFormat.STORY } >= 4)
            assertTrue(SegmentFormat.QUIZ !in f.formats)
            assertTrue(s.state.value.radioState != RadioState.IDLE)
        }
    }

    @Test
    fun noQuestionWhenDisabled() = runTest {
        run(ask = false) { f, _ -> assertTrue(f.lines.isEmpty()) }
    }
}
