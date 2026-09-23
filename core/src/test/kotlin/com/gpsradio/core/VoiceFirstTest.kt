package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.lang.Notice
import com.gpsradio.core.lang.Notices
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Voice is the main channel (spec A §32): what the listener must know is said, not only shown. */
class VoiceFirstTest {
    private val here = GeoPoint(47.61, 13.78)

    @Test
    fun noticesExistInEveryHandWrittenLanguageAndFallBackToEnglish() {
        Notice.entries.forEach { n ->
            Notices.languages.forEach { lang -> assertTrue(Notices.text(n, lang).isNotBlank(), "$n/$lang") }
        }
        assertTrue(Notices.text(Notice.ANSWER_FAILED, "ru-RU").contains("Извините"))
        assertTrue(Notices.text(Notice.ANSWER_FAILED, "he-IL").any { it in '\u0590'..'\u05FF' })
        assertEquals(Notices.text(Notice.ANSWER_FAILED, "en"), Notices.text(Notice.ANSWER_FAILED, "ja-JP"))
    }

    private class Fake(val list: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore {
        val played = mutableListOf<String>()
        var hist: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun narrate(req: NarrationRequest) = Segment("STORY", req.candidate.place.id, req.candidate.place.name, emptyList())
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply = throw IOException("timeout")
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private fun TestScope.session(f: Fake, online: Boolean = true, lang: String = "ru-RU") = RadioSession(
        places = f, narrator = f, speech = f, historyStore = f,
        audio = AudioOutput { bytes -> f.played += String(bytes); delay(2_000) },
        config = { SessionConfig(lang, setOf(Topic.HISTORY), askAboutTrip = false) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        fallbackSpeech = object : SpeechService {
            override suspend fun synthesize(text: String, language: String, style: HostStyle) = "DEVICE:$text".toByteArray()
            override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        },
        isOnline = { online },
    )

    @Test
    fun aFailedAnswerIsSpokenInTheListenersLanguage() = runTest {
        val f = Fake(emptyList())
        val s = session(f)
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            s.ask("Какая высота у башни?"); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertTrue(f.played.any { it == Notices.text(Notice.ANSWER_FAILED, "ru-RU") }, "${f.played}")
        } finally {
            s.stop(); runCurrent()
        }
    }

    @Test
    fun offlineQuestionsAndTourProblemsAreSpokenWithThePhoneVoice() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 200.0))))
        val s = session(f, online = false, lang = "en-US")
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            s.ask("How old is it?"); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertTrue(f.played.any { it == "DEVICE:" + Notices.text(Notice.QUESTIONS_OFFLINE, "en-US") }, "${f.played}")
            s.startTour(30); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertTrue(f.played.any { it.endsWith(Notices.text(Notice.TOUR_TOO_FEW_SIGHTS, "en-US")) }, "${f.played}")
        } finally {
            s.stop(); runCurrent()
        }
    }
}
