package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.LocalNarrator
import com.gpsradio.core.ai.LocalWriter
import com.gpsradio.core.ai.NarrationFallback
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.OpenAiException
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.lang.Notice
import com.gpsradio.core.lang.Notices
import com.gpsradio.core.model.AreaLabel
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The basic flows end to end (spec A §74), each with OpenAI working and on the phone's fallback (out of credit or
 * offline; with a local model or with plain notes; Russian listener with Russian or only English sources):
 * start → first story → the next ones follow by themselves (non-stop), Next, pause/resume, stop/start, a question,
 * credit running out and coming back, and a Russian listener among English-only places with no model.
 */
class BasicFlowsTest {
    private val here = GeoPoint(55.75, 37.61)

    enum class Backend { OPENAI, QUOTA, OFFLINE }
    enum class Local { MODEL, NOTES }

    data class Mode(val backend: Backend, val local: Local, val sources: String, val language: String = "ru-RU") {
        override fun toString() = "$backend/$local/$sources-sources/$language"
    }

    /** Every mode in which stories can be told (English sources need OpenAI or the model to translate them). */
    private val modes = listOf(
        Mode(Backend.OPENAI, Local.MODEL, "ru"),
        Mode(Backend.OPENAI, Local.NOTES, "en"),
        Mode(Backend.QUOTA, Local.MODEL, "ru"),
        Mode(Backend.QUOTA, Local.NOTES, "ru"),
        Mode(Backend.QUOTA, Local.MODEL, "en"),
        Mode(Backend.OFFLINE, Local.MODEL, "ru"),
        Mode(Backend.OFFLINE, Local.NOTES, "ru"),
        Mode(Backend.OFFLINE, Local.MODEL, "en"),
    )

    // ---- fakes ---------------------------------------------------------------------------------

    /** OpenAI stand-in (stories, answers, voice); logs every call. */
    private class OpenAi(var error: Exception? = null) : Narrator, SpeechService {
        val calls = mutableListOf<String>()
        override suspend fun narrate(req: NarrationRequest): Segment {
            calls += "narrate:${req.candidate.place.id}"
            error?.let { throw it }
            delay(2_000)
            return Segment("AI story about ${req.candidate.place.name}.", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
            calls += "converse"
            error?.let { throw it }
            return ConversationReply("AI answer.")
        }
        override suspend fun webAnswer(question: String, language: String, area: AreaLabel?) = ""
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray {
            calls += "synthesize"
            error?.let { throw it }
            return "AI:$text".toByteArray()
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        fun narrations() = calls.count { it.startsWith("narrate:") }
    }

    /** The phone's own voice. */
    private class Phone : SpeechService {
        val spoken = mutableListOf<String>()
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray {
            spoken += text
            return "PHONE:$text".toByteArray()
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
    }

    /** The phone's model: one prompt at a time (like LiteRT-LM behind its lock), and slow. Writes Russian. */
    private class Gemma(val writeMs: Long) : LocalWriter {
        override val name = "Gemma"
        private val lock = Mutex()
        val prompts = mutableListOf<String>()
        override suspend fun available() = true
        override suspend fun write(prompt: String): String = lock.withLock {
            prompts += prompt
            delay(writeMs)
            if ("Listener:" in prompt) {
                "Хороший вопрос: это место очень старое."
            } else {
                val name = Regex("about \"([^\"]+)\"").find(prompt)!!.groupValues[1]
                "Рассказ про $name: это старинное и очень интересное место, о нём стоит послушать."
            }
        }
    }

    private class World(val places: List<PlaceCandidate>, val clipMs: Long) : PlacesProvider, HistoryStore, AudioOutput {
        val played = mutableListOf<String>()
        var playing = 0
        var maxConcurrent = 0
        var stored: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
        override suspend fun play(audio: ByteArray) {
            played += String(audio)
            playing++
            maxConcurrent = maxOf(maxConcurrent, playing)
            try { delay(clipMs) } finally { playing-- }
        }
    }

    private val ruNames = listOf(
        "Музей Альфа", "Музей Бета", "Музей Гамма", "Музей Дельта", "Музей Каппа", "Музей Сигма", "Музей Омега", "Музей Лямбда",
        "Дом Альфа", "Дом Бета", "Дом Гамма", "Дом Дельта", "Дом Каппа", "Дом Сигма", "Дом Омега", "Дом Лямбда",
        "Сад Альфа", "Сад Бета", "Сад Гамма", "Сад Дельта",
    )
    private val enNames = listOf(
        "Alpha Museum", "Beta Museum", "Gamma Museum", "Delta Museum", "Kappa Museum", "Sigma Museum", "Omega Museum", "Lambda Museum",
        "Alpha House", "Beta House", "Gamma House", "Delta House", "Kappa House", "Sigma House", "Omega House", "Lambda House",
        "Alpha Garden", "Beta Garden", "Gamma Garden", "Delta Garden",
    )

    private fun places(sources: String, count: Int): List<PlaceCandidate> = (0 until count).map { i ->
        val ru = sources == "ru"
        val name = (if (ru) ruNames else enNames)[i]
        PlaceCandidate(
            id = "p$i", name = name, category = "museum",
            point = Geo.destination(here, i * 47.0 % 360, 100.0 + (i % 8) * 25 + (i / 8) * 60),
            source = "wikipedia:$sources", sourceConfidence = 0.85, baseRelevance = 0.8, topics = setOf(Topic.HISTORY),
            extract = if (ru) "$name — старинный музей в центре города. Он основан в девятнадцатом веке. В нём хранится большая коллекция."
            else "$name is an old museum in the city centre. It was founded in the 19th century. It holds a large collection.",
            url = "https://$sources.wikipedia.org/wiki/P$i",
        )
    }

    private val quota = OpenAiException(
        429,
        OpenAiClient.friendlyError(429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}"""),
    )

    /** One session and its world, set up for [mode]. */
    private inner class Rig(
        val mode: Mode,
        placeCount: Int = 6,
        clipMs: Long = 20_000,
        writeMs: Long = 3_000,
        scope: TestScope,
    ) {
        val world = World(places(mode.sources, placeCount), clipMs)
        val openAi = OpenAi(
            error = when (mode.backend) {
                Backend.OPENAI -> null
                Backend.QUOTA -> quota
                Backend.OFFLINE -> IOException("offline")
            },
        )
        val phone = Phone()
        val gemma = Gemma(writeMs)
        var online = mode.backend != Backend.OFFLINE
        val session = RadioSession(
            places = world, narrator = openAi, speech = openAi, audio = world, historyStore = world,
            config = { SessionConfig(mode.language, setOf(Topic.HISTORY), pacing = Pacing.NONSTOP, localModelReady = mode.local == Local.MODEL) },
            clock = { scope.testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            fallbackNarrator = if (mode.local == Local.MODEL) LocalNarrator(gemma) else NarrationFallback(),
            fallbackSpeech = phone,
            isOnline = { online },
        )

        /** Place ids of the stories that went on air, in order (a clip is a story when it names a place). */
        fun stories(): List<String> = world.played.mapNotNull { clip -> world.places.firstOrNull { it.name in clip }?.id }

        /** The clips that were stories. */
        fun storyClips(): List<String> = world.played.filter { clip -> world.places.any { it.name in clip } }

        fun state() = session.state.value
    }

    private fun TestScope.fix() = LocationSample(here.lat, here.lon, 5f, testScheduler.currentTime + 1_000_000, 0f)

    private fun TestScope.running(rig: Rig, body: TestScope.(Rig) -> Unit) {
        try {
            rig.session.start(); runCurrent()
            rig.session.onLocation(fix()); runCurrent()
            body(rig)
        } finally {
            rig.session.stop(); runCurrent()
        }
    }

    /** Advances in half-second steps until [cond] holds; fails with [what] after [maxMs]. */
    private fun TestScope.waitUntil(what: String, maxMs: Long, cond: () -> Boolean): Long {
        var t = 0L
        while (!cond() && t < maxMs) { advanceTimeBy(500); runCurrent(); t += 500 }
        assertTrue(cond(), "$what (waited ${t / 1000} s)")
        return t
    }

    private fun TestScope.waitForStory(rig: Rig, maxMs: Long = 30_000, what: String = "a story plays", except: Set<String?> = emptySet()) =
        waitUntil("${rig.mode}: $what", maxMs) {
            rig.state().radioState == RadioState.NARRATING && rig.state().nowPlaying?.entityId?.let { it !in except } == true
        }

    /** Where the stories must come from in [mode]: OpenAI's voice, or the phone's. */
    private fun assertVoice(rig: Rig, clips: List<String>) {
        val prefix = if (rig.mode.backend == Backend.OPENAI) "AI:" else "PHONE:"
        assertTrue(clips.all { it.startsWith(prefix) }, "${rig.mode}: stories in the expected voice: $clips")
        if (rig.mode.backend != Backend.OPENAI) {
            // Everything the listener hears is in their language (spec A §32).
            assertTrue(clips.all { LocalNarrator.fitsLanguage(it.removePrefix("PHONE:"), rig.mode.language) }, "${rig.mode}: in Russian: $clips")
        }
    }

    /** OpenAI isn't hammered while it can't be used: never offline, and out of credit only the first try (and probes). */
    private fun assertOpenAiCalls(rig: Rig, stories: Int) {
        when (rig.mode.backend) {
            Backend.OPENAI -> assertTrue(rig.openAi.narrations() >= stories, "${rig.mode}: ${rig.openAi.calls}")
            Backend.QUOTA -> assertTrue(rig.openAi.narrations() <= 1, "${rig.mode}: out of credit, OpenAI is not tried for every story: ${rig.openAi.calls}")
            Backend.OFFLINE -> assertTrue(rig.openAi.calls.isEmpty(), "${rig.mode}: ${rig.openAi.calls}")
        }
    }

    // ---- flows ---------------------------------------------------------------------------------

    @Test
    fun firstStoryPlaysAndTheNextOnesFollowByThemselvesWithoutRepeats() = runTest {
        for (mode in modes) {
            running(Rig(mode, scope = this)) { rig ->
                waitForStory(rig, what = "the first story plays")
                assertNotNull(rig.state().nowPlaying)
                // Non-stop: when a story ends the next one follows by itself, several in a row.
                waitUntil("${rig.mode}: stories keep coming: ${rig.world.played}", 150_000) { rig.stories().distinct().size >= 5 }
                val stories = rig.stories()
                assertEquals(stories.distinct(), stories, "${rig.mode}: no story twice: ${rig.world.played}")
                assertVoice(rig, rig.storyClips())
                assertOpenAiCalls(rig, stories.size)
                assertEquals(1, rig.world.maxConcurrent, "${rig.mode}: one clip at a time")
            }
        }
    }

    @Test
    fun nextDuringAStoryStartsADifferentOnePromptly() = runTest {
        for (mode in modes) {
            // A slow phone model (8 s a story): Next while it's still writing the next story ahead must not wait twice.
            running(Rig(mode, writeMs = 8_000, scope = this)) { rig ->
                waitForStory(rig)
                val first = rig.state().nowPlaying!!.entityId
                advanceTimeBy(3_000); runCurrent()
                rig.session.skip(); runCurrent()
                assertTrue(rig.state().nowPlaying?.entityId != first, "${rig.mode}: the skipped story is off the screen")
                val took = waitForStory(rig, maxMs = 10_000, what = "Next starts another story promptly", except = setOf(first))
                assertTrue(took <= 10_000)
                // "Next story…" goes as soon as the story is on air.
                assertTrue(rig.state().status?.text != Notices.text(Notice.NEXT_STORY, mode.language), "${rig.mode}: ${rig.state().status}")
                assertFalse(rig.state().waiting, "${rig.mode}: no 'searching' while the story plays")
                val second = rig.state().nowPlaying!!.entityId
                // And again: Next twice quickly still brings exactly one new story.
                advanceTimeBy(2_000); runCurrent()
                rig.session.skip(); rig.session.skip(); runCurrent()
                waitForStory(rig, maxMs = 20_000, what = "a third story after a double Next", except = setOf(first, second))
                // The rest play on, and the skipped ones never come back.
                advanceTimeBy(120_000); runCurrent()
                val stories = rig.stories()
                assertEquals(1, stories.count { it == first }, "${rig.mode}: ${rig.world.played}")
                assertEquals(1, stories.count { it == second }, "${rig.mode}: ${rig.world.played}")
                assertEquals(1, rig.world.maxConcurrent, "${rig.mode}: one clip at a time")
                assertTrue(rig.state().status?.text != Notices.text(Notice.NEXT_STORY, mode.language), "${rig.mode}: 'Next story…' cleared")
            }
        }
    }

    @Test
    fun pauseResumeAndStopStart() = runTest {
        for (mode in modes) {
            running(Rig(mode, scope = this)) { rig ->
                val s = rig.session
                waitForStory(rig)
                s.pause(); runCurrent()
                assertEquals(RadioState.PAUSED, rig.state().radioState, "${rig.mode}")
                assertEquals(0, rig.world.playing, "${rig.mode}: pause silences at once")
                val n = rig.world.played.size
                advanceTimeBy(90_000); runCurrent()
                assertEquals(n, rig.world.played.size, "${rig.mode}: nothing plays while paused: ${rig.world.played.drop(n)}")
                s.resume(); runCurrent()
                waitForStory(rig, maxMs = 20_000, what = "resume brings the stories back")
                s.stop(); runCurrent()
                assertEquals(RadioState.IDLE, rig.state().radioState)
                assertEquals(null, rig.state().nowPlaying)
                assertEquals(0, rig.world.playing, "${rig.mode}: stop silences at once")
                val m = rig.world.played.size
                advanceTimeBy(90_000); runCurrent()
                assertEquals(m, rig.world.played.size, "${rig.mode}: nothing plays while stopped")
                s.start(); runCurrent()
                s.onLocation(fix()); runCurrent()
                waitForStory(rig, maxMs = 20_000, what = "start brings the stories back")
                assertEquals(1, rig.world.maxConcurrent, "${rig.mode}: one clip at a time")
            }
        }
    }

    @Test
    fun aQuestionIsAnsweredThenTheRadioCarriesOn() = runTest {
        for (mode in modes + listOf(Mode(Backend.QUOTA, Local.NOTES, "ru"), Mode(Backend.OFFLINE, Local.NOTES, "ru"))) {
            running(Rig(mode, scope = this)) { rig ->
                waitForStory(rig)
                val before = rig.stories().toSet()
                val n = rig.world.played.size
                rig.session.ask("Сколько лет этому месту?"); runCurrent()
                // The story stops for the question (whatever plays now is the reply, not the story).
                assertTrue(rig.world.playing == 0 || rig.world.played.size > n, "${rig.mode}: the story stops for the question")
                assertEquals(1, rig.world.maxConcurrent, "${rig.mode}: one clip at a time")
                waitUntil("${rig.mode}: something is said back", 60_000) { rig.world.played.size > n }
                val reply = rig.world.played[n]
                val expected = when {
                    mode.backend == Backend.OPENAI -> "AI:AI answer."
                    mode.local == Local.MODEL -> "PHONE:Хороший вопрос: это место очень старое."
                    // No model: out of credit says so; offline says questions need the internet.
                    mode.backend == Backend.QUOTA -> "PHONE:" + RadioSession.quotaSpoken(mode.language)
                    else -> "PHONE:" + Notices.text(Notice.QUESTIONS_OFFLINE, mode.language)
                }
                assertEquals(expected, reply, "${rig.mode}: ${rig.world.played.drop(n)}")
                if (mode.backend == Backend.OFFLINE || mode.local == Local.MODEL && mode.backend != Backend.OPENAI) {
                    assertTrue("converse" !in rig.openAi.calls, "${rig.mode}: ${rig.openAi.calls}")
                }
                // Then the radio carries on with a new story by itself.
                waitForStory(rig, maxMs = 120_000, what = "the radio carries on after the question", except = before)
            }
        }
    }

    @Test
    fun creditRunsOutMidSessionStoriesGoOnOnThePhoneAndOpenAiComesBackAfterATopUp() = runTest {
        for (local in Local.entries) {
            val mode = Mode(Backend.OPENAI, local, "ru")
            running(Rig(mode, placeCount = 20, clipMs = 60_000, scope = this)) { rig ->
                waitUntil("$mode: OpenAI stories first", 150_000) { rig.stories().size >= 2 }
                assertTrue(rig.storyClips().all { it.startsWith("AI:") })
                // The credit runs out.
                rig.openAi.error = quota
                val callsAtQuota = rig.openAi.narrations()
                val storiesAtQuota = rig.stories().size
                advanceTimeBy(240_000); runCurrent()
                assertTrue(rig.state().quotaExhausted, "$mode")
                val onPhone = rig.storyClips().drop(storiesAtQuota).filter { it.startsWith("PHONE:") }
                assertTrue(onPhone.size >= 2, "$mode: stories go on with the phone: ${rig.world.played}")
                assertTrue(rig.openAi.narrations() - callsAtQuota <= 2, "$mode: OpenAI isn't tried for every story: ${rig.openAi.calls}")
                // Said once, in Russian, that the credit ran out.
                val notice = if (local == Local.MODEL) Notices.text(Notice.QUOTA_MODEL, "ru-RU") else RadioSession.quotaSpoken("ru-RU")
                assertEquals(1, rig.world.played.count { notice in it }, "$mode: ${rig.world.played}")
                // The listener tops up: within a probe interval OpenAI tells the stories again.
                rig.openAi.error = null
                val storiesAtTopUp = rig.stories().size
                waitUntil("$mode: OpenAI back after the top-up: ${rig.world.played}", RadioSession.QUOTA_PROBE_MS + 180_000) {
                    rig.storyClips().drop(storiesAtTopUp).any { it.startsWith("AI:") }
                }
                assertFalse(rig.state().quotaExhausted, "$mode")
                val stories = rig.stories()
                assertEquals(stories.distinct(), stories, "$mode: no story twice: ${rig.world.played}")
            }
        }
    }

    @Test
    fun russianListenerAmongEnglishOnlyPlacesWithoutAModelIsToldWhyItsQuietAndStoriesResumeLater() = runTest {
        for (backend in listOf(Backend.QUOTA, Backend.OFFLINE)) {
            val mode = Mode(backend, Local.NOTES, "en")
            running(Rig(mode, scope = this)) { rig ->
                advanceTimeBy(60_000); runCurrent()
                // Nothing in English is read to a Russian listener...
                assertTrue(rig.storyClips().isEmpty(), "$mode: ${rig.world.played}")
                assertTrue(rig.phone.spoken.all { LocalNarrator.fitsLanguage(it, "ru-RU") }, "$mode: ${rig.phone.spoken}")
                // ...but the radio doesn't just go silent: it says once, in Russian, why and what brings stories back.
                val said = rig.world.played.filter { RadioSession.noNotesSpoken("ru-RU") in it }
                assertEquals(1, said.size, "$mode: ${rig.world.played}")
                if (backend == Backend.QUOTA) assertTrue(said.single().contains("OpenAI"), said.single())
                assertEquals(RadioState.RADIO, rig.state().radioState, "$mode: not stuck 'preparing'")
                assertFalse(rig.state().waiting, "$mode: no endless 'searching'")
                // Next can't conjure a story: no second notice, and "Next story…" doesn't stay on screen forever.
                rig.session.skip(); runCurrent()
                advanceTimeBy(70_000); runCurrent()
                assertEquals(1, rig.world.played.count { RadioSession.noNotesSpoken("ru-RU") in it }, "$mode")
                assertTrue(rig.state().status?.text != Notices.text(Notice.NEXT_STORY, "ru-RU"), "$mode: ${rig.state().status}")
                assertTrue(rig.storyClips().isEmpty(), "$mode: ${rig.world.played}")
                // OpenAI comes back (online again / credit topped up): the English places are told in Russian by OpenAI.
                rig.openAi.error = null
                rig.online = true
                waitUntil("$mode: stories resume with OpenAI: ${rig.world.played}", RadioSession.QUOTA_PROBE_MS + 60_000) {
                    rig.storyClips().any { it.startsWith("AI:") }
                }
                waitUntil("$mode: and keep coming", 120_000) { rig.stories().distinct().size >= 3 }
                assertTrue(rig.state().status?.text != RadioSession.NO_NOTES_IN_LANGUAGE, "$mode: the note goes away")
            }
        }
    }
}
