package com.gpsradio.core

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.RealtimeConnection
import com.gpsradio.core.ai.RealtimeEvent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.journal.JournalStore
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.Topic
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.HistoryStore
import com.gpsradio.core.session.LiveConversation
import com.gpsradio.core.session.LiveState
import com.gpsradio.core.session.PcmAudio
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import com.gpsradio.core.session.Sting
import com.gpsradio.core.session.StingPlayer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TourSessionTest {
    private val here = GeoPoint(47.61, 13.78)
    private val rich = "The castle was built on a rock in the lake, and its gate still shows the old coat of arms. ".repeat(8)

    /** Places, narrator, speech, stings and audio in one fake; [log] records what was heard, in order. */
    private class Fake(val list: List<PlaceCandidate>) :
        PlacesProvider, Narrator, SpeechService, HistoryStore, StingPlayer, AudioOutput, JournalStore {
        val log = mutableListOf<String>()
        val narrations = mutableListOf<Pair<String, SegmentFormat>>()
        val lines = mutableListOf<Pair<HostLine, String>>()
        val asked = mutableListOf<ConversationRequest>()
        var reply = ConversationReply("ok")
        var hist: String? = null
        var journalData: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrations += req.candidate.place.id to req.format
            return Segment("${req.format} ${req.candidate.place.id}. More.", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply { asked += req; return reply }
        override suspend fun hostLine(kind: HostLine, draft: String, language: String, style: HostStyle): String {
            lines += kind to draft
            return draft
        }
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
        override suspend fun play(sting: Sting) { log += "sting:$sting"; delay(600) }
        override suspend fun play(audio: ByteArray) { log += "play:" + String(audio); delay(10_000) }
        fun kinds() = lines.map { it.first }
    }

    private class JournalFile : JournalStore {
        var data: String? = null
        override fun load() = data
        override fun save(serialized: String) { data = serialized }
    }

    private fun TestScope.session(
        f: Fake,
        effects: Boolean = true,
        journal: JournalStore? = null,
        liveFactory: ((com.gpsradio.core.session.LiveHost, kotlinx.coroutines.CoroutineScope) -> LiveConversation)? = null,
    ) = RadioSession(
        places = f, narrator = f, speech = f, audio = f, historyStore = f,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY), soundEffects = effects, liveVoice = liveFactory != null) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        stings = f,
        journalStore = journal,
        liveFactory = liveFactory,
    )

    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try { s.start(); runCurrent(); body() } finally { s.stop(); runCurrent() }
    }

    private fun TestScope.advanceUntil(maxMs: Long = 600_000, cond: () -> Boolean) {
        var t = 0L
        while (!cond()) {
            check(t < maxMs) { "condition not reached within ${maxMs / 1000} s" }
            advanceTimeBy(1_000); runCurrent()
            t += 1_000
        }
    }

    private fun TestScope.fixAt(p: GeoPoint) = LocationSample(p.lat, p.lon, 5f, testScheduler.currentTime + 1_000_000, 0f)

    private fun TestScope.walkTo(s: RadioSession, p: GeoPoint) {
        advanceTimeBy(1_000)
        s.onLocation(fixAt(p)); runCurrent()
    }

    private fun threeSights() = listOf(
        place("a", Geo.destination(here, 0.0, 120.0), name = "Castle").copy(extract = rich),
        place("b", Geo.destination(here, 90.0, 150.0), name = "Church"),
        place("c", Geo.destination(here, 200.0, 130.0), name = "Bridge"),
    )

    @Test
    fun walkingTourIntroArrivalChapterDirectionsAndEnd() = runTest {
        val f = Fake(threeSights())
        val journal = JournalFile()
        val s = session(f, journal = journal)
        running(s) {
            s.onLocation(fixAt(here)); runCurrent()
            s.startTour(30); runCurrent()
            val tour = assertNotNull(s.state.value.tour)
            assertEquals(3, tour.stops.size)
            assertEquals(0, tour.nextIndex)
            assertEquals(30, tour.minutes)
            assertEquals(HostLine.TOUR_INTRO to TourTextDrafts.introPrefix(tour.stops.map { it.name }), f.lines.single().let { it.first to it.second.substringBefore(" First stop") })
            assertTrue("First stop: ${tour.stops[0].name}, about" in f.lines.single().second)

            // Away from the stops nothing else airs: the regular scheduler is held during the tour.
            val before = f.narrations.size
            advanceTimeBy(120_000); runCurrent()
            assertEquals(before, f.narrations.size)
            assertEquals(RadioState.RADIO, s.state.value.radioState)

            tour.stops.forEachIndexed { i, stop ->
                advanceTimeBy(15_000); runCurrent() // let the previous line finish
                walkTo(s, Geo.destination(stop.point, 45.0, 25.0)) // within ~40 m counts as arrived
                assertEquals(i + 1, s.state.value.tour?.nextIndex ?: tour.stops.size, "advanced past stop $i")
                if (i < tour.stops.lastIndex) {
                    advanceUntil { f.kinds().count { it == HostLine.TOUR_NEXT } == i + 1 }
                    assertEquals("Next stop: ${tour.stops[i + 1].name}", f.lines.last().second.substringBefore(","))
                } else {
                    advanceUntil { HostLine.TOUR_END in f.kinds() }
                }
            }
            assertNull(s.state.value.tour)
            assertTrue(f.lines.last().second.startsWith("That was the last stop"))
            assertTrue("Your starting point is" in f.lines.last().second)

            // Each stop's story is told on arrival; the rich one gets the "standing in front of it" chapter.
            val tourStories = f.narrations.drop(before)
            assertEquals(tour.stops.map { it.id to SegmentFormat.STORY }, tourStories.filter { it.second == SegmentFormat.STORY })
            assertEquals(listOf("a" to SegmentFormat.ARRIVAL), tourStories.filter { it.second == SegmentFormat.ARRIVAL })

            // A station sting right before each story, none before the arrival chapter.
            tour.stops.forEach { stop ->
                val at = f.log.indexOf("play:STORY ${stop.id}. More.")
                assertTrue(at > 0)
                assertEquals("sting:STATION", f.log[at - 1])
            }
            val chapter = f.log.indexOf("play:ARRIVAL a. More.")
            assertEquals("play:STORY a. More.", f.log[chapter - 1])

            // Journal: every stop, persisted.
            assertEquals(tour.stops.map { it.id }.toSet(), s.state.value.journal.map { it.placeId }.toSet())
            assertTrue(journal.data!!.contains("\"firstSentence\":\"STORY a.\""))

            // After the tour the regular radio returns.
            advanceTimeBy(600_000); runCurrent()
            assertEquals(RadioState.RADIO, s.state.value.radioState)
        }
    }

    @Test
    fun walkingStraightToALaterStopSkipsAheadAndSkipKeepsDirections() = runTest {
        val f = Fake(threeSights())
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here)); runCurrent()
            s.startTour(30); runCurrent()
            val tour = s.state.value.tour!!
            advanceTimeBy(15_000); runCurrent()
            val n = f.narrations.size
            walkTo(s, tour.stops[1].point)
            assertEquals(2, s.state.value.tour!!.nextIndex)
            assertEquals(tour.stops[1].id to SegmentFormat.STORY, f.narrations.drop(n).first())
            // Skipping the story still leads on to the next stop.
            s.skip(); runCurrent()
            advanceUntil { HostLine.TOUR_NEXT in f.kinds() }
            assertTrue(f.lines.last().second.startsWith("Next stop: ${tour.stops[2].name}"))
            // End the tour from the UI.
            s.endTour(); runCurrent()
            assertNull(s.state.value.tour)
        }
    }

    @Test
    fun voiceStartsTourAndContextMentionsIt() = runTest {
        val f = Fake(threeSights())
        f.reply = ConversationReply("Let's go!", ConversationAction.START_TOUR, tourMinutes = 30)
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here)); runCurrent()
            s.ask("Give me 30 minutes"); runCurrent()
            advanceUntil { s.state.value.tour != null }
            assertEquals(30, s.state.value.tour!!.minutes)
            // The answer chime came right before the spoken answer.
            val answer = f.log.indexOf("play:Let's go!")
            assertEquals("sting:ANSWER", f.log[answer - 1])
            advanceUntil { HostLine.TOUR_INTRO in f.kinds() }

            f.reply = ConversationReply("It's the castle.")
            advanceTimeBy(15_000); runCurrent()
            s.ask("What's next?"); runCurrent()
            val tourLine = assertNotNull(f.asked.last().tour)
            assertTrue(tourLine.startsWith("stop 1 of 3, next: "), tourLine)

            f.reply = ConversationReply("Tour's over.", ConversationAction.END_TOUR)
            advanceTimeBy(15_000); runCurrent()
            s.ask("Stop the tour"); runCurrent()
            advanceUntil { s.state.value.tour == null }
            // Back to normal programming: regular stories air again.
            val before = f.narrations.size
            advanceUntil { f.narrations.size > before }
        }
    }

    @Test
    fun noTourWithoutEnoughSights() = runTest {
        val f = Fake(listOf(place("far", Geo.destination(here, 0.0, 1_400.0))))
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here)); runCurrent()
            s.startTour(15); runCurrent()
            assertNull(s.state.value.tour)
            assertTrue(s.state.value.status!!.text.startsWith("Not enough sights nearby"))
            assertTrue(f.lines.isEmpty())
        }
    }

    @Test
    fun soundEffectsCanBeTurnedOff() = runTest {
        val f = Fake(threeSights())
        val s = session(f, effects = false)
        running(s) {
            s.onLocation(fixAt(here)); runCurrent()
            advanceUntil { f.log.any { it.startsWith("play:STORY") } }
            f.reply = ConversationReply("Sure.")
            s.ask("Is that true?"); runCurrent()
            advanceUntil { "play:Sure." in f.log }
            assertTrue(f.log.none { it.startsWith("sting:") })
        }
    }

    @Test
    fun regularStoryGetsStationStingAndLandsInThePersistedJournal() = runTest {
        val file = JournalFile()
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 120.0), name = "Castle")))
        val s1 = session(f, journal = file)
        running(s1) {
            s1.onLocation(fixAt(here)); runCurrent()
            advanceUntil { s1.state.value.journal.isNotEmpty() }
            assertEquals(listOf("sting:STATION", "play:STORY a. More."), f.log.take(2))
            val e = s1.state.value.journal.single()
            assertEquals("Castle", e.name)
            assertEquals("STORY a.", e.firstSentence)
            assertEquals(Geo.destination(here, 0.0, 120.0), e.point)
        }
        // A new session restores it, and "Tell me again" re-narrates a place that is still around.
        val f2 = Fake(listOf(place("a", Geo.destination(here, 0.0, 120.0), name = "Castle")))
        val s2 = session(f2, journal = file)
        running(s2) {
            assertEquals(listOf("a"), s2.state.value.journal.map { it.placeId })
            s2.onLocation(fixAt(here)); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            s2.skip(); runCurrent()
            val told = f2.narrations.size
            s2.retell("a", "Castle"); runCurrent()
            assertEquals("a" to SegmentFormat.STORY, f2.narrations.last())
            assertTrue(f2.narrations.size > told)
            // A place no longer nearby is asked about instead.
            s2.retell("gone", "Old Mill"); runCurrent()
            advanceTimeBy(100); runCurrent()
            assertTrue(f2.asked.last().utterance.contains("Old Mill"))
        }
    }

    // ---- live voice ------------------------------------------------------------------------

    private class FakeConnection : RealtimeConnection {
        val server = Channel<RealtimeEvent>(Channel.UNLIMITED)
        var closed = false
        override val events: Flow<RealtimeEvent> = server.receiveAsFlow()
        override fun send(event: JsonObject): Boolean = true
        override fun close() { closed = true; server.close() }
    }

    private class FakePcm : PcmAudio {
        override fun startCapture(onChunk: (ByteArray) -> Unit) = true
        override fun stopCapture() {}
        override fun play(pcm: ByteArray) {}
        override fun stopPlayback() {}
    }

    @Test
    fun liveListeningBlipAndVoiceToolStartsTour() = runTest {
        val f = Fake(threeSights())
        val conns = mutableListOf<FakeConnection>()
        val s = session(f, liveFactory = { host, scope ->
            LiveConversation({ FakeConnection().also { conns += it } }, FakePcm(), host, scope, idleTimeoutMs = 20_000, clock = { testScheduler.currentTime })
        })
        running(s) {
            s.onLocation(fixAt(here)); runCurrent()
            s.toggleLive(); runCurrent()
            val c = conns.single()
            c.server.trySend(RealtimeEvent.SessionReady); runCurrent()
            assertEquals(LiveState.LISTENING, s.state.value.live)
            assertEquals(1, f.log.count { it == "sting:LISTENING" })
            // Later turns do not blip again.
            c.server.trySend(RealtimeEvent.SpeechStarted)
            c.server.trySend(RealtimeEvent.SpeechStopped); runCurrent()
            c.server.trySend(RealtimeEvent.FunctionCall("t1", "radio_control", """{"action":"start_tour","minutes":30}""")); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertTrue(c.closed)
            assertEquals(30, s.state.value.tour?.minutes)
            assertEquals(1, f.log.count { it == "sting:LISTENING" })
            advanceUntil { HostLine.TOUR_INTRO in f.kinds() }
        }
    }
}

/** Expected intro wording, kept next to the tests that use it. */
private object TourTextDrafts {
    fun introPrefix(names: List<String>) =
        "Here's a 30-minute loop with ${names.size} stops: ${names.dropLast(1).joinToString(", ")} and ${names.last()}."
}
