package com.gpsradio.core

import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.FillerRequest
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.discovery.AngleResearch
import com.gpsradio.core.discovery.AngleScout
import com.gpsradio.core.discovery.AngleTarget
import com.gpsradio.core.discovery.AreaFacet
import com.gpsradio.core.discovery.AreaFacetKind
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.editorial.PlaceMentions
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec A §51: the photo shows what the host is talking about. */
class PhotoFollowsStoryTest {
    private val here = GeoPoint(47.918, 13.799)

    @Test
    fun mentionsMatchInflectedNamesAndNotFragments() {
        val castle = place("c", here, name = "Schloss Ort")
        val town = place("t", here, name = "Гмунден")
        val short = place("s", here, name = "Ort")
        assertEquals(castle, PlaceMentions.find("Oh, Schloss Ort sits on its own island.", listOf(town, castle, short)))
        assertEquals(town, PlaceMentions.find("В Гмундене делают керамику.", listOf(town, castle, short)))
        assertNull(PlaceMentions.find("The ort of the matter.", listOf(short)), "a 3-letter name alone is too weak")
        assertNull(PlaceMentions.find("Nothing about it here.", listOf(castle, town)))
    }

    @Test
    fun theResearcherNamesTheSubjectForItsPhoto() {
        assertTrue("\"subject\"" in AngleScout.schema.toString() && "Wikipedia article title" in AngleScout.INSTRUCTIONS)
        assertEquals("Traunsee", AngleScout.subject("""{"found":true,"title":"t","facts":"f","interest":5,"subject":" Traunsee "}"""))
        assertNull(AngleScout.subject("""{"found":true,"subject":""}"""))
    }

    private class Radio(val places: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, AudioOutput, HistoryStore, AngleResearch {
        val photoAsked = mutableListOf<String>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = places
        override suspend fun articlePhoto(lang: String, title: String): Triple<String, String, String>? {
            photoAsked += "$lang:$title"
            return if (title == "Traunsee") Triple("Traunsee", "https://upload.wikimedia.org/x/Traunsee.jpg", "https://en.wikipedia.org/wiki/Traunsee") else null
        }
        override suspend fun narrate(req: NarrationRequest) =
            Segment("Story ${req.candidate.place.name}", req.candidate.place.id, req.candidate.place.name, emptyList(), imageUrl = req.candidate.place.imageUrl)
        override suspend fun narrateFiller(req: FillerRequest) = Segment("Area story about the deep lake", null, "Area", emptyList())
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = ConversationReply("ok")
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = text.toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
        override suspend fun play(audio: ByteArray) { delay(20_000) }
        override fun load(): String? = null
        override fun save(serialized: String) {}
        override suspend fun research(target: AngleTarget, area: AreaLabel?, point: GeoPoint?, alreadyTold: List<String>) =
            AreaFacet(target.scopeName, AreaFacetKind.OVERVIEW, "Facts about the deep lake. ".repeat(10), angle = target.angle?.key, title = "The deep lake", subject = "Traunsee")
    }

    @Test
    fun anAreaStoryShowsItsSubjectNotThePreviousPlace() = runTest {
        val castle = place("castle", Geo.destination(here, 0.0, 150.0), name = "Castle").copy(imageUrl = "https://img/castle.jpg")
        val r = Radio(listOf(castle))
        val s = RadioSession(
            places = r, narrator = r, speech = r, audio = r, historyStore = r,
            config = { SessionConfig("ru-RU", setOf(Topic.HISTORY), pacing = Pacing.NONSTOP) },
            clock = { testScheduler.currentTime + 1_000_000 },
            dispatcher = StandardTestDispatcher(testScheduler),
            areaLabeler = AreaLabeler { _ -> AreaLabel("Gmunden", "Upper Austria", "AT") },
            angleResearch = r,
        )
        try {
            s.start(); runCurrent()
            s.onLocation(LocationSample(here.lat, here.lon, 5f, 1_000_000, 0f)); runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals("https://img/castle.jpg", s.state.value.focus?.imageUrl, "the place story shows the castle")
            var t = 0L
            while (s.state.value.nowPlaying?.text?.startsWith("Area") != true && t < 180_000) { advanceTimeBy(1_000); runCurrent(); t += 1_000 }
            runCurrent()
            val focus = s.state.value.focus!!
            assertEquals("https://upload.wikimedia.org/x/Traunsee.jpg", focus.imageUrl, "the area story shows its subject: ${r.photoAsked}")
            assertEquals("The deep lake", focus.name)
        } finally {
            s.stop(); runCurrent()
        }
    }
}
