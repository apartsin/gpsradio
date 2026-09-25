package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.LocalNarrator
import com.gpsradio.core.ai.LocalWriter
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings → Providers (spec A §70): stories, answers and voice each come from the chosen provider. */
class ProviderChoiceTest {
    private val here = GeoPoint(47.61, 13.78)

    private class OpenAi(var error: Exception? = null) : Narrator, SpeechService {
        val calls = mutableListOf<String>()
        override suspend fun narrate(req: NarrationRequest): Segment {
            calls += "narrate"
            error?.let { throw it }
            return Segment("AI story", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
            calls += "converse"
            error?.let { throw it }
            return ConversationReply("AI answer")
        }
        override suspend fun webAnswer(question: String, language: String, area: AreaLabel?) = ""
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray {
            calls += "synthesize"
            error?.let { throw it }
            return "AI:$text".toByteArray()
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = "hi"
    }

    private class Phone : SpeechService {
        val spoken = mutableListOf<String>()
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray {
            spoken += text
            return "PHONE:$text".toByteArray()
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
    }

    private class Gemma : LocalWriter {
        override val name = "Gemma"
        val prompts = mutableListOf<String>()
        override suspend fun available() = true
        override suspend fun write(prompt: String): String {
            prompts += prompt
            return if ("Listener:" in prompt) "It was built in the 11th century." else "Ort Castle stands on Lake Traun and is almost a thousand years old."
        }
    }

    private class World(val places: List<PlaceCandidate>) : PlacesProvider, HistoryStore, AudioOutput {
        val played = mutableListOf<String>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override fun load(): String? = null
        override fun save(serialized: String) {}
        override suspend fun play(audio: ByteArray) { played += String(audio); delay(20_000) }
    }

    private val world = World(listOf(
        PlaceCandidate(
            "a", "Ort Castle", "castle", Geo.destination(here, 0.0, 150.0), "wikipedia:en", 0.85, 0.8, setOf(Topic.HISTORY),
            extract = "Ort Castle is a castle on Lake Traun. It dates from the 11th century.",
        ),
    ))

    private fun TestScope.session(openAi: OpenAi, phone: Phone, gemma: Gemma, cfg: SessionConfig, online: Boolean = true) = RadioSession(
        places = world, narrator = openAi, speech = openAi, audio = world, historyStore = world,
        config = { cfg },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        fallbackNarrator = LocalNarrator(gemma),
        fallbackSpeech = phone,
        isOnline = { online },
    )

    private fun fix() = LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)

    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try { s.start(); runCurrent(); body() } finally { s.stop(); runCurrent() }
    }

    @Test
    fun storiesFromThePhoneModelByChoiceKeepTheChosenVoiceAndNoNotice() = runTest {
        val openAi = OpenAi(); val phone = Phone(); val gemma = Gemma()
        val s = session(openAi, phone, gemma, SessionConfig("en-US", setOf(Topic.HISTORY), storiesOnDevice = true))
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            val text = s.state.value.nowPlaying!!.text
            assertEquals("Ort Castle stands on Lake Traun and is almost a thousand years old.", text, "no 'OpenAI is unavailable' notice")
            assertTrue("narrate" !in openAi.calls, openAi.calls.toString())
            // Online, so the chosen voice (the speech port) reads it.
            assertTrue(world.played.first().startsWith("AI:"), world.played.toString())
            assertTrue(s.state.value.status?.text != RadioSession.DEGRADED_NOTE)
        }
    }

    @Test
    fun questionsAnsweredOnThePhoneByChoice() = runTest {
        val openAi = OpenAi(); val phone = Phone(); val gemma = Gemma()
        val s = session(openAi, phone, gemma, SessionConfig("en-US", setOf(Topic.HISTORY), assistantOnDevice = true))
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            s.ask("How old is it?"); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertTrue("converse" !in openAi.calls, openAi.calls.toString())
            assertTrue(gemma.prompts.any { "Listener: How old is it?" in it && "Lake Traun" in it })
        }
    }

    @Test
    fun offlineTheQuestionIsAnsweredOnThePhoneInsteadOfRefused() = runTest {
        val openAi = OpenAi(); val phone = Phone(); val gemma = Gemma()
        val s = session(openAi, phone, gemma, SessionConfig("en-US", setOf(Topic.HISTORY)), online = false)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            s.ask("How old is it?"); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertTrue(openAi.calls.isEmpty(), openAi.calls.toString())
            assertTrue("It was built in the 11th century." in phone.spoken, phone.spoken.toString())
            assertTrue(s.state.value.status?.text != RadioSession.OFFLINE_QUESTIONS)
        }
    }

    @Test
    fun outOfCreditWithAModelOnThePhoneCarriesOnCalmlyInRussian() = runTest {
        val quota = com.gpsradio.core.ai.OpenAiException(
            429,
            com.gpsradio.core.ai.OpenAiClient.friendlyError(
                429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""",
            ),
        )
        val openAi = OpenAi(error = quota); val phone = Phone(); val gemma = Gemma()
        val s = session(openAi, phone, gemma, SessionConfig("ru-RU", setOf(Topic.HISTORY), usingBuiltInKey = true, localModelReady = true))
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            val status = s.state.value.status!!
            assertEquals(com.gpsradio.core.lang.Notices.text(com.gpsradio.core.lang.Notice.QUOTA_MODEL, "ru-RU"), status.text)
            assertEquals(com.gpsradio.core.session.StatusLevel.INFO, status.level)
            assertTrue(!status.needsKey, "no 'add a key' error while the phone's model tells the stories")
            val story = s.state.value.nowPlaying!!.text
            assertTrue(story.startsWith("Небольшое объявление: закончился кредит OpenAI"), story)
            assertTrue(story.endsWith("Ort Castle stands on Lake Traun and is almost a thousand years old."), story)
            // Questions go to the phone's model too, not to an "add a key" refusal.
            s.ask("Сколько ему лет?"); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertTrue(gemma.prompts.any { "Listener: Сколько ему лет?" in it })
        }
    }
}
