package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.FillerRequest
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.AngleResearch
import com.gpsradio.core.discovery.AngleTarget
import com.gpsradio.core.discovery.AreaFacet
import com.gpsradio.core.discovery.AreaFacetKind
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.Topic
import com.gpsradio.core.session.AreaLabeler
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
import kotlin.test.assertTrue

/**
 * Non-stop is a continuous stream (spec A §38): writing a story takes 4 s and voicing it 2 s, yet the next segment
 * starts ~2 s after the last one ends, because it was prepared while the last one played.
 */
class ContinuousStreamTest {
    private val here = GeoPoint(47.918, 13.799)
    private val area = AreaLabel("Gmunden", "Upper Austria", "AT")

    private class Radio(val places: List<PlaceCandidate>, private val now: () -> Long) :
        PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore, AngleResearch {
        /** (start, end) of every clip played. */
        val clips = mutableListOf<Pair<Long, Long>>()
        val played = mutableListOf<String>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun narrate(req: NarrationRequest): Segment {
            delay(4_000)
            return Segment("Story ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun narrateFiller(req: FillerRequest): Segment {
            delay(4_000)
            return Segment("Area ${req.areaFacet?.title}", null, "Area", emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle): ByteArray {
            delay(2_000)
            return text.toByteArray()
        }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) {
            val start = now()
            played += String(audio)
            delay(25_000)
            clips += start to now()
        }
        override fun load(): String? = null
        override fun save(serialized: String) {}
        override suspend fun research(target: AngleTarget, area: AreaLabel?, point: GeoPoint?, alreadyTold: List<String>): AreaFacet? {
            delay(3_000)
            return AreaFacet(target.scopeName, AreaFacetKind.OVERVIEW, "Facts. ".repeat(20), angle = target.angle?.key, title = target.angle?.key)
        }
    }

    private fun TestScope.session(r: Radio) = RadioSession(
        places = r, narrator = r, speech = r, audio = r, historyStore = r,
        config = { SessionConfig("ru-RU", setOf(Topic.HISTORY), pacing = Pacing.NONSTOP) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        areaLabeler = AreaLabeler { _ -> area },
        angleResearch = r,
    )

    /** Silences between clips, after the first one (which has nothing to be prepared during). */
    private fun gaps(r: Radio): List<Long> = r.clips.zipWithNext { a, b -> b.first - a.second }.drop(1)

    @Test
    fun walkingPlaceStoriesFollowEachOtherWithoutDeadAir() = runTest {
        val places = (1..12).map { place("p$it", Geo.destination(here, it * 30.0, 80.0 + it * 15), name = "P$it") }
        val r = Radio(places) { testScheduler.currentTime }
        val s = session(r)
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(8 * 60_000L); runCurrent()
        } finally {
            s.stop(); runCurrent()
        }
        val g = gaps(r)
        assertTrue(r.clips.size >= 10, "clips: ${r.clips.size}")
        // Without preparing ahead each gap would be ≥ 6 s (4 s writing + 2 s voice); with it, about the 2 s pause.
        assertTrue(g.max() <= 3_500, "gaps (ms): $g; played: ${r.played}")
    }

    @Test
    fun researchedAreaStoriesAlsoFlowWithoutDeadAir() = runTest {
        // One place only: after it, the radio lives on researched angles, prepared while the previous one plays.
        val r = Radio(listOf(place("castle", Geo.destination(here, 0.0, 150.0), name = "Castle"))) { testScheduler.currentTime }
        val s = session(r)
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(12 * 60_000L); runCurrent()
        } finally {
            s.stop(); runCurrent()
        }
        val areaGaps = r.clips.zip(r.played).zipWithNext { (a, _), (b, text) -> if (text.startsWith("Area")) b.first - a.second else null }
            .filterNotNull().drop(2) // the first researched ones can't be ready yet: nothing had run out
        assertTrue(r.played.count { it.startsWith("Area") } >= 12, "area stories: ${r.played.count { it.startsWith("Area") }}")
        assertTrue(areaGaps.isNotEmpty() && areaGaps.max() <= 3_500, "area gaps (ms): $areaGaps")
    }

    @Test
    fun drivingPreparesTheNextPlaceAheadWhileTheCurrentOnePlays() = runTest {
        // A straight road north at 20 m/s with places spread along it ahead.
        val places = (1..30).map { place("r$it", Geo.destination(here, 2.0, it * 900.0), name = "R$it") }
        val r = Radio(places) { testScheduler.currentTime }
        val s = session(r)
        try {
            s.start(); runCurrent()
            var t = 0L
            while (t < 8 * 60_000L) {
                val p = Geo.destination(here, 0.0, 20.0 * t / 1000)
                s.onLocation(LocationSample(p.lat, p.lon, 5f, 1_000_000 + t, 20f, bearingDeg = 0f)); runCurrent()
                advanceTimeBy(2_000); runCurrent()
                t += 2_000
            }
        } finally {
            s.stop(); runCurrent()
        }
        val g = gaps(r)
        assertTrue(r.clips.size >= 6, "clips: ${r.clips.size}")
        // A 4 s driving safety gap, plus GPS/tick slack; without preparing ahead it would be ≥ 10 s.
        assertTrue(g.sorted()[g.size / 2] <= 7_000, "median gap should be short; gaps (ms): $g")
    }
}
