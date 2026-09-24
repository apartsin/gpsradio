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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every transport control (Start, Stop, Pause, Resume/Back to radio, Skip = next, Repeat, Nearby?, "Tell me about")
 * in every state it can be pressed in, including rapid presses. Invariants: never two clips at once, nothing
 * plays while paused or stopped, and the state shown on the buttons always matches what is (not) playing.
 */
class ControlsTest {
    private val here = GeoPoint(47.61, 13.78)

    private class Radio(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore {
        val narrated = mutableListOf<String>()
        val played = mutableListOf<String>()
        var playing = 0
        var maxConcurrent = 0
        var asked = 0
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrated += req.candidate.place.id
            return Segment("Story about ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
            asked++
            return ConversationReply("Here is an answer.")
        }
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) {
            played += String(audio)
            playing++
            maxConcurrent = maxOf(maxConcurrent, playing)
            try { delay(20_000) } finally { playing-- }
        }
        override fun load(): String? = null
        override fun save(serialized: String) {}
    }

    private fun places() = listOf(
        place("castle", Geo.destination(here, 0.0, 150.0), name = "Castle"),
        place("tower", Geo.destination(here, 90.0, 200.0), name = "Tower"),
        place("church", Geo.destination(here, 180.0, 250.0), name = "Church"),
        place("bridge", Geo.destination(here, 270.0, 300.0), name = "Bridge"),
    )

    private fun TestScope.session(r: Radio) = RadioSession(
        places = r, narrator = r, speech = r, audio = r, historyStore = r,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY)) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        teaserGapMs = Long.MAX_VALUE,
    )

    private fun TestScope.onAir(r: Radio, body: TestScope.(RadioSession) -> Unit) {
        val s = session(r)
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            body(s)
        } finally {
            s.stop(); runCurrent()
        }
    }

    private fun TestScope.waitFor(s: RadioSession, state: RadioState, maxMs: Long = 120_000) {
        var t = 0L
        while (s.state.value.radioState != state && t < maxMs) { advanceTimeBy(1_000); runCurrent(); t += 1_000 }
        assertEquals(state, s.state.value.radioState, "waited ${t / 1000}s")
    }

    @Test
    fun skipMovesToTheNextStoryAndNeverReplaysTheSkippedOne() = runTest {
        val r = Radio(places())
        onAir(r) { s ->
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            val first = s.state.value.nowPlaying!!.entityId
            s.skip(); runCurrent()
            // Skip stops the story at once, and "next" means now: the next story starts without the pacing gap.
            assertTrue(r.playing <= 1 && r.maxConcurrent == 1, "skip stops the audio at once")
            assertTrue(s.state.value.nowPlaying?.entityId != first)
            waitFor(s, RadioState.NARRATING, maxMs = 5_000)
            val second = s.state.value.nowPlaying!!.entityId
            assertTrue(second != first, "skip = next: a different story")
            // Skip again, twice quickly: still exactly one story at a time, and the next one comes.
            s.skip(); s.skip(); runCurrent()
            waitFor(s, RadioState.NARRATING)
            assertTrue(s.state.value.nowPlaying!!.entityId !in setOf(first, second))
            advanceTimeBy(600_000); runCurrent()
            assertEquals(1, r.played.count { it == "Story about ${first!!.replaceFirstChar { c -> c.uppercase() }}" })
            assertEquals(1, r.maxConcurrent)
        }
    }

    @Test
    fun pauseSilencesEverythingAndResumeContinues() = runTest {
        val r = Radio(places())
        onAir(r) { s ->
            val interrupted = s.state.value.nowPlaying!!.entityId
            s.pause(); runCurrent()
            assertEquals(RadioState.PAUSED, s.state.value.radioState)
            assertEquals(0, r.playing)
            val playedBefore = r.played.size
            // New fixes and a long wait: nothing airs while paused.
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_100_000, 0f)); runCurrent()
            advanceTimeBy(300_000); runCurrent()
            assertEquals(playedBefore, r.played.size)
            assertEquals(RadioState.PAUSED, s.state.value.radioState)
            // Skip while paused stays paused (it only drops the current story).
            s.skip(); runCurrent()
            assertEquals(RadioState.PAUSED, s.state.value.radioState)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(playedBefore, r.played.size)
            // Resume: stories come again; the interrupted one wasn't marked heard.
            s.resume(); runCurrent()
            waitFor(s, RadioState.NARRATING)
            advanceTimeBy(900_000); runCurrent()
            assertTrue(interrupted in r.narrated)
            assertEquals(1, r.maxConcurrent)
        }
    }

    @Test
    fun pausePressedTwiceAndResumeWhilePlayingAreHarmless() = runTest {
        val r = Radio(places())
        onAir(r) { s ->
            // Resume while a story plays does nothing (no restart, no second clip).
            val n = r.played.size
            s.resume(); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            assertEquals(n, r.played.size)
            s.pause(); s.pause(); runCurrent()
            assertEquals(RadioState.PAUSED, s.state.value.radioState)
            s.resume(); s.resume(); runCurrent()
            waitFor(s, RadioState.NARRATING)
            assertEquals(1, r.maxConcurrent)
        }
    }

    @Test
    fun repeatReplaysTheLastClipAndKeepsPause() = runTest {
        val r = Radio(places())
        onAir(r) { s ->
            waitFor(s, RadioState.RADIO)
            val last = r.played.last()
            s.repeat(); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            assertEquals(last, r.played.last())
            // Repeat interrupts itself cleanly: pressing it twice plays one clip at a time.
            s.repeat(); runCurrent()
            assertEquals(1, r.playing)
            s.pause(); runCurrent()
            assertEquals(0, r.playing)
            // Repeat while paused: the clip plays, and the radio is still paused afterwards.
            s.repeat(); runCurrent()
            assertEquals(1, r.playing)
            advanceTimeBy(25_000); runCurrent()
            assertEquals(RadioState.PAUSED, s.state.value.radioState)
            assertEquals(1, r.maxConcurrent)
        }
    }

    @Test
    fun stopSilencesEverythingAndStartComesBack() = runTest {
        val r = Radio(places())
        val s = session(r)
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(RadioState.NARRATING, s.state.value.radioState)
            s.stop(); runCurrent()
            assertEquals(RadioState.IDLE, s.state.value.radioState)
            assertEquals(0, r.playing)
            // After Stop every other control is inert: no audio, still off air.
            val n = r.played.size
            s.skip(); s.resume(); s.repeat(); s.pause(); runCurrent()
            advanceTimeBy(300_000); runCurrent()
            assertEquals(RadioState.IDLE, s.state.value.radioState)
            assertEquals(n, r.played.size)
            // Start again: back on air.
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, testScheduler.currentTime + 1_000_000, 0f)); runCurrent()
            waitFor(s, RadioState.NARRATING)
            assertEquals(1, r.maxConcurrent)
        } finally {
            s.stop(); runCurrent()
        }
    }

    @Test
    fun nearbyAndBackToRadio() = runTest {
        val r = Radio(places())
        onAir(r) { s ->
            s.whatsNearby(); runCurrent()
            assertEquals(RadioState.CONVERSING, s.state.value.radioState)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(1, r.asked)
            // "Back to radio" (the primary button while conversing) returns to the programme.
            s.resume(); runCurrent()
            assertFalse(s.state.value.radioState == RadioState.CONVERSING)
            waitFor(s, RadioState.NARRATING)
            assertEquals(1, r.maxConcurrent)
        }
    }

    @Test
    fun tellAboutPlaysThatPlaceNow() = runTest {
        val r = Radio(places())
        onAir(r) { s ->
            val other = places().map { it.id }.first { it != s.state.value.nowPlaying?.entityId }
            s.tellAbout(other); runCurrent()
            waitFor(s, RadioState.NARRATING, maxMs = 10_000)
            assertEquals(other, s.state.value.nowPlaying?.entityId)
            assertEquals(1, r.maxConcurrent)
        }
    }

    @Test
    fun mashingEveryButtonNeverOverlapsOrWedges() = runTest {
        // Enough places that 15 instant skips don't simply use them all up.
        val r = Radio(places() + (1..30).map { place("p$it", Geo.destination(here, it * 12.0, 120.0 + it * 10), name = "Place $it") })
        onAir(r) { s ->
            val presses = listOf<() -> Unit>(
                { s.skip() }, { s.pause() }, { s.repeat() }, { s.resume() }, { s.skip() }, { s.whatsNearby() },
                { s.resume() }, { s.repeat() }, { s.skip() }, { s.pause() }, { s.resume() },
            )
            for (round in 0 until 5) {
                presses.forEachIndexed { i, press ->
                    press()
                    if (i % 3 == round % 3) runCurrent()
                    advanceTimeBy(300L * (i % 4)); runCurrent()
                }
            }
            runCurrent()
            assertEquals(1, r.maxConcurrent, "never two clips at once")
            // After all that, the radio still works: it ends up back on air.
            s.resume(); runCurrent()
            waitFor(s, RadioState.NARRATING, maxMs = 300_000)
        }
    }

    @Test
    fun spokenControlsAreRecognizedInEveryLanguageOnTheDevice() {
        val cases = mapOf(
            ConversationAction.SKIP to listOf(
                "Skip", "next!", "OK, skip it please", "Дальше", "ну, давай дальше", "Следующий.", "пропусти, пожалуйста",
                "הבא", "Weiter", "siguiente", "Suivant",
            ),
            ConversationAction.RESUME_RADIO to listOf(
                "Back to the radio", "go on", "Continue.", "Вернись к радио", "Продолжай!", "ладно, продолжай", "назад к радио",
                "חזור לרדיו", "Zurück zum Radio", "sigue", "reprends",
            ),
            ConversationAction.PAUSE to listOf(
                "Stop", "pause", "be quiet", "Стоп", "Хватит!", "помолчи", "пауза, пожалуйста", "עצור", "Stopp", "pausa", "arrête",
            ),
        )
        for ((action, phrases) in cases) for (p in phrases) {
            assertEquals(action, RadioSession.localCommand(p), "'$p'")
        }
        // Real questions are not commands; they go to the model.
        for (q in listOf("What's next to the castle?", "Стоп, а что это за башня?", "Tell me more", "Когда это построили?")) {
            assertEquals(null, RadioSession.localCommand(q), "'$q'")
        }
    }

    @Test
    fun spokenCommandsDriveTheRadio() = runTest {
        val r = Radio(places())
        onAir(r) { s ->
            val first = s.state.value.nowPlaying!!.entityId
            // "Следующий": skips to a different story, without asking the model.
            s.ask("Следующий"); runCurrent()
            waitFor(s, RadioState.NARRATING)
            assertTrue(s.state.value.nowPlaying!!.entityId != first)
            // "Хватит": paused, silent.
            s.ask("Хватит!"); runCurrent()
            assertEquals(RadioState.PAUSED, s.state.value.radioState)
            assertEquals(0, r.playing)
            // "Вернись к радио": stories again.
            s.ask("Вернись к радио"); runCurrent()
            waitFor(s, RadioState.NARRATING)
            // A real question, then "back to the radio" from the conversation.
            s.ask("Когда это построили?"); runCurrent()
            assertEquals(RadioState.CONVERSING, s.state.value.radioState)
            advanceTimeBy(1_000); runCurrent()
            s.ask("Back to the radio"); runCurrent()
            waitFor(s, RadioState.NARRATING)
            assertEquals(1, r.asked, "only the real question reached the model")
            assertEquals(1, r.maxConcurrent)
        }
    }
}

/** Spec A §59: the listener waiting on "next" hears "just a moment" (in their language) and sees it's searching. */
class WaitFeedbackTest {
    private val here = GeoPoint(47.61, 13.78)

    private class Slow(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore {
        val played = mutableListOf<String>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest): Segment {
            delay(15_000) // a slow writer
            return Segment("История ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) { played += String(audio); delay(2_000) }
        override fun load(): String? = null
        override fun save(serialized: String) {}
    }

    @Test
    fun waitingForTheFirstStorySaysJustAMomentAndShowsSearching() = runTest {
        val r = Slow(listOf(place("castle", Geo.destination(here, 0.0, 150.0), name = "Castle")))
        val s = RadioSession(
            places = r, narrator = r, speech = r, audio = r, historyStore = r,
            config = { SessionConfig("ru-RU", setOf(Topic.HISTORY)) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            teaserGapMs = Long.MAX_VALUE,
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(4_000); runCurrent()
            assertTrue(s.state.value.waiting, "the UI shows it's searching")
            advanceTimeBy(14_000); runCurrent()
            val cue = com.gpsradio.core.lang.Notices.text(com.gpsradio.core.lang.Notice.WAIT_1, "ru-RU")
            assertEquals(cue, r.played.first(), "a spoken 'just a moment' in Russian: ${r.played}")
            assertTrue(r.played.any { it.startsWith("История") }, "then the story: ${r.played}")
            assertTrue(r.played.count { it == cue } == 1, "not repeated like a loop: ${r.played}")
        } finally {
            s.stop(); runCurrent()
        }
    }
}
