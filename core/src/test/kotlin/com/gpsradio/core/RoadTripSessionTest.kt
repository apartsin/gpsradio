package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.InterestModel
import com.gpsradio.core.editorial.InterestStore
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.location.Corridor
import com.gpsradio.core.model.ActivityType
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RoadTripKind
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

/** Session-level behaviour of the road-trip corridor, driving pacing, implicit interests and activity priors. */
class RoadTripSessionTest {
    private val here = GeoPoint(47.61, 13.78)

    private class Fake(val list: List<PlaceCandidate>, val playMs: Long = 10_000) :
        PlacesProvider, Narrator, SpeechService, HistoryStore, InterestStore, AudioOutput {
        val discoverCalls = mutableListOf<Pair<GeoPoint, Int>>()
        val narrations = mutableListOf<NarrationRequest>()
        /** Virtual time at which each story started playing. */
        val aired = mutableListOf<Pair<Long, String>>()
        var now: () -> Long = { 0L }
        var reply = ConversationReply("Yes, that is documented.")
        var hist: String? = null
        var interests: String? = null
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String): List<PlaceCandidate> {
            discoverCalls += center to radiusM
            return list
        }
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrations += req
            return Segment("STORY ${req.candidate.place.id}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = reply
        override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle) = "Where are we heading?"
        override suspend fun synthesize(text: String, language: String, style: HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = String(audio)
        override suspend fun play(audio: ByteArray) {
            val text = String(audio)
            if (text.startsWith("STORY ")) aired += now() to text.removePrefix("STORY ")
            delay(playMs)
        }
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
        val interestStore = object : InterestStore {
            override fun load() = interests
            override fun save(serialized: String) { interests = serialized }
        }
    }

    private fun TestScope.session(f: Fake): RadioSession {
        f.now = { testScheduler.currentTime }
        return RadioSession(
            places = f, narrator = f, speech = f, audio = f, historyStore = f, interestStore = f.interestStore,
            config = { SessionConfig("en-US", setOf(Topic.HISTORY)) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try { s.start(); runCurrent(); body() } finally { s.stop(); runCurrent() }
    }

    private fun TestScope.fixAt(point: GeoPoint, speed: Float, bearing: Float? = 0f) =
        LocationSample(point.lat, point.lon, 5f, testScheduler.currentTime + 1_000_000, speed, bearing)

    private fun learned(f: Fake, topic: Topic): Double =
        InterestModel().apply { restore(f.interests) }.weight(topic, 1_000_000)

    // ---- driving corridor -----------------------------------------------------------------------

    @Test
    fun drivingDiscoversCorridorCellsAheadAndFetchesOnlyNewOnes() = runTest {
        val f = Fake(listOf(place("castle", Geo.destination(here, 0.0, 4_000.0))))
        val s = session(f)
        running(s) {
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            s.onLocation(fixAt(here, 25f)); runCurrent()
            assertEquals(3, f.discoverCalls.size)
            f.discoverCalls.zip(listOf(2_000.0, 6_000.0, 10_000.0)).forEach { (call, ahead) ->
                assertEquals(ahead, Geo.distanceM(here, call.first), 5.0)
                assertEquals(0.0, Geo.angleDiff(Geo.bearingDeg(here, call.first), 0.0), 0.5)
                assertEquals(Corridor.DEFAULT_CELL_RADIUS_M, call.second)
            }
            // 3.1 km further north: only the new far cell is fetched; the others come from the corridor cache.
            advanceTimeBy(5_000); runCurrent()
            s.onLocation(fixAt(Geo.destination(here, 0.0, 3_100.0), 25f)); runCurrent()
            assertEquals(4, f.discoverCalls.size)
            assertEquals(13_100.0, Geo.distanceM(here, f.discoverCalls.last().first), 5.0)
        }
    }

    @Test
    fun walkingStillUsesASingleCircle() = runTest {
        val f = Fake(listOf(place("castle", Geo.destination(here, 0.0, 150.0))))
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here, 1.3f)); runCurrent()
            assertEquals(1, f.discoverCalls.size)
            assertEquals(1_500, f.discoverCalls.single().second)
        }
    }

    // ---- driving pacing ---------------------------------------------------------------------------

    @Test
    fun drivingStoriesAreAtLeast90SecondsApartAndCarryTheRoadTripFlag() = runTest {
        val f = Fake(
            listOf(
                place("a", Geo.destination(here, 0.0, 400.0)),
                place("b", Geo.destination(here, 10.0, 600.0)),
                place("c", Geo.destination(here, 350.0, 800.0)),
            ),
        )
        val s = session(f)
        running(s) {
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            // Steady driving (position held so the places stay ahead).
            repeat(150) {
                s.onLocation(fixAt(here, 25f)); runCurrent()
                advanceTimeBy(4_000); runCurrent()
            }
            assertTrue(f.aired.size >= 2, "aired: ${f.aired}")
            // Each story plays 10 s; the next one waits at least 90 s after it ended.
            f.aired.zipWithNext().forEach { (x, y) -> assertTrue(y.first - x.first >= 100_000, "gap ${y.first - x.first}") }
            // Castles right by the road, with plenty of material: worth a stop.
            assertTrue(f.narrations.all { it.roadTrip == RoadTripKind.WORTH_A_STOP })
        }
    }

    @Test
    fun storiesWaitWhileSpeedChangesSharply() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 400.0))))
        val s = session(f)
        running(s) {
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            // Steady until the trip question has been asked and the driving gap has almost passed...
            repeat(20) { s.onLocation(fixAt(here, 25f)); runCurrent(); advanceTimeBy(4_000); runCurrent() }
            // ...then a long junction/roundabout sequence: braking and accelerating.
            repeat(20) { i ->
                s.onLocation(fixAt(here, if (i % 2 == 0) 6f else 20f)); runCurrent()
                advanceTimeBy(4_000); runCurrent()
            }
            assertTrue(f.aired.isEmpty(), "no story during the junction")
            val calmFrom = testScheduler.currentTime
            repeat(15) { s.onLocation(fixAt(here, 20f)); runCurrent(); advanceTimeBy(4_000); runCurrent() }
            assertEquals(listOf("a"), f.aired.map { it.second })
            // Only after the maneuver window (20 s) has cleared.
            assertTrue(f.aired.single().first - calmFrom >= 16_000)
        }
    }

    // ---- implicit personalization ----------------------------------------------------------------

    @Test
    fun storyHeardToTheEndNudgesItsTopicsUp() = runTest {
        val f = Fake(listOf(place("castle", Geo.destination(here, 0.0, 150.0))))
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here, 0f, null)); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals(listOf("castle"), f.aired.map { it.second })
            assertEquals(InterestModel.Signal.COMPLETED.delta, learned(f, Topic.HISTORY), 1e-3)
        }
    }

    @Test
    fun earlySkipIsAStrongNegativeButALateSkipIsNot() = runTest {
        val f = Fake(listOf(place("castle", Geo.destination(here, 0.0, 150.0), topics = setOf(Topic.WAR))), playMs = 30_000)
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here, 0f, null)); runCurrent()
            advanceTimeBy(3_000); runCurrent()
            s.skip(); runCurrent()
            assertEquals(InterestModel.Signal.EARLY_SKIP.delta, learned(f, Topic.WAR), 1e-3)
        }

        val g = Fake(listOf(place("tower", Geo.destination(here, 0.0, 150.0), topics = setOf(Topic.NATURE))), playMs = 30_000)
        val s2 = session(g)
        running(s2) {
            s2.onLocation(fixAt(here, 0f, null)); runCurrent()
            advanceTimeBy(12_000); runCurrent()
            s2.skip(); runCurrent()
            assertEquals(0.0, learned(g, Topic.NATURE), 1e-9)
        }
    }

    @Test
    fun followUpQuestionAboutTheStoryIsAStrongPositive() = runTest {
        val f = Fake(listOf(place("castle", Geo.destination(here, 0.0, 150.0), topics = setOf(Topic.LEGENDS))), playMs = 30_000)
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here, 0f, null)); runCurrent()
            advanceTimeBy(20_000); runCurrent()
            s.ask("Wait, is that legend really true?"); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(InterestModel.Signal.FOLLOW_UP.delta, learned(f, Topic.LEGENDS), 1e-3)
            // Asking again about the same story does not pile up.
            s.ask("And who told it first?"); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(InterestModel.Signal.FOLLOW_UP.delta, learned(f, Topic.LEGENDS), 1e-3)
        }
    }

    @Test
    fun learnedInterestsPersistAndShapeTheNextSession() = runTest {
        val f = Fake(
            listOf(
                place("war", Geo.destination(here, 0.0, 150.0), topics = setOf(Topic.WAR)),
                place("legend", Geo.destination(here, 0.0, 160.0), topics = setOf(Topic.LEGENDS)),
            ),
        )
        f.interests = InterestModel().apply {
            repeat(3) { record(InterestModel.Signal.EARLY_SKIP, setOf(Topic.WAR), 1_000_000) }
            repeat(3) { record(InterestModel.Signal.FOLLOW_UP, setOf(Topic.LEGENDS), 1_000_000) }
        }.serialize(1_000_000)
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here, 0f, null)); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            // The closer war story loses to the legend the listener keeps asking about.
            assertEquals("legend", f.aired.first().second)
            assertTrue(s.state.value.nearby.first { it.place.id == "war" }.breakdown.interest < 0.2)
        }
    }

    // ---- activity recognition -------------------------------------------------------------------

    @Test
    fun activityPriorFromThePlatformSelectsCycling() = runTest {
        val f = Fake(emptyList())
        val s = session(f)
        running(s) {
            s.onLocation(fixAt(here, 1.3f)); runCurrent()
            assertEquals(TravelMode.WALKING, s.state.value.location?.travelMode)
            s.onActivity(ActivityType.ON_BICYCLE); runCurrent()
            var p = here
            repeat(4) {
                advanceTimeBy(5_000); runCurrent()
                p = Geo.destination(p, 0.0, 25.0)
                s.onLocation(fixAt(p, 3.2f)); runCurrent()
            }
            assertEquals(TravelMode.CYCLING, s.state.value.location?.travelMode)
        }
    }
}
