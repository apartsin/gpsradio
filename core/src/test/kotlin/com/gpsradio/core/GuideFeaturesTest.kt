package com.gpsradio.core

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.ConversationReply
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.PlacesProvider
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.favorites.FavoritePlace
import com.gpsradio.core.favorites.Favorites
import com.gpsradio.core.favorites.FavoritesStore
import com.gpsradio.core.favorites.ShareText
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideFeaturesTest {
    private val here = GeoPoint(47.61, 13.78)
    private val longFacts = "The castle was built on a rock in the lake. ".repeat(30)

    private class Fake(val list: List<PlaceCandidate>) : PlacesProvider, Narrator, SpeechService, HistoryStore, FavoritesStore {
        val narrations = mutableListOf<Pair<String, SegmentFormat>>()
        val asked = mutableListOf<ConversationRequest>()
        val spoken = mutableListOf<String>()
        var reply = ConversationReply("ok")
        var hist: String? = null
        var favs: String? = null
        var styles = mutableListOf<HostStyle>()
        override suspend fun discover(center: GeoPoint, radiusM: Int, languageBase: String) = list
        override suspend fun gallery(place: PlaceCandidate) = listOf("https://img/1.jpg", "https://img/2.jpg")
        override suspend fun narrate(req: NarrationRequest): Segment {
            narrations += req.candidate.place.id to req.format
            styles += req.style
            val text = if (req.format == SegmentFormat.TEASER) "TEASER ${req.candidate.place.id}" else "STORY ${req.candidate.place.id}"
            return Segment(text, req.candidate.place.id, req.candidate.place.name, emptyList())
        }
        override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply { asked += req; return reply }
        override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle) = "Where are we heading?"
        override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray { spoken += text; return text.toByteArray() }
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = String(audio)
        override fun load() = hist
        override fun save(serialized: String) { hist = serialized }
    }

    private class FavFile : FavoritesStore {
        var data: String? = null
        override fun load() = data
        override fun save(serialized: String) { data = serialized }
    }

    private fun TestScope.session(f: Fake, favs: FavoritesStore? = null, style: HostStyle = HostStyle.ENTERTAINING) = RadioSession(
        places = f, narrator = f, speech = f, audio = AudioOutput { delay(10_000) }, historyStore = f, favoritesStore = favs,
        config = { SessionConfig("en-US", setOf(Topic.HISTORY), style) },
        clock = { testScheduler.currentTime + 1_000_000 },
        dispatcher = StandardTestDispatcher(testScheduler),
        teaserGapMs = 0,
    )

    private fun TestScope.running(s: RadioSession, body: TestScope.() -> Unit) {
        try { s.start(); runCurrent(); body() } finally { s.stop(); runCurrent() }
    }

    /** Advances virtual time in 1 s steps until [cond] holds (or fails after [maxMs]). */
    private fun TestScope.advanceUntil(maxMs: Long = 600_000, cond: () -> Boolean) {
        var t = 0L
        while (!cond()) {
            check(t < maxMs) { "condition not reached within ${maxMs / 1000} s" }
            advanceTimeBy(1_000); runCurrent()
            t += 1_000
        }
    }

    private fun fix(speed: Float = 0f, t: Long = 0) = LocationSample(here.lat, here.lon, 5f, 1_000_000 + t, speed, 0f)

    private fun rich(id: String, bearing: Double, d: Double) =
        place(id, Geo.destination(here, bearing, d)).copy(extract = longFacts)

    @Test
    fun richStoryIsOfferedAsTeaserAndToldAfterYes() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 100.0)), place("b", Geo.destination(here, 90.0, 110.0)), rich("c", 180.0, 120.0)))
        val s = session(f, style = HostStyle.KIDS)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceUntil { s.state.value.pendingOffer != null }
            // After two regular stories, the rich one is offered first.
            assertEquals(listOf(SegmentFormat.STORY, SegmentFormat.STORY, SegmentFormat.TEASER), f.narrations.map { it.second })
            assertEquals(RadioState.CONVERSING, s.state.value.radioState)
            assertEquals("c", s.state.value.pendingOffer?.let { "c" })
            s.ask("Yes please"); runCurrent()
            advanceTimeBy(2_000); runCurrent()
            assertEquals("c" to SegmentFormat.STORY, f.narrations.last())
            assertNull(s.state.value.pendingOffer)
            // Offers are answered locally; no model round trip for a plain yes.
            assertTrue(f.asked.isEmpty())
            assertTrue(f.styles.all { it == HostStyle.KIDS })
        }
    }

    @Test
    fun declinedOfferIsNotOfferedAgain() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 100.0)), place("b", Geo.destination(here, 90.0, 110.0)), rich("c", 180.0, 120.0)))
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceUntil { s.state.value.pendingOffer != null }
            s.ask("no thanks"); runCurrent()
            advanceTimeBy(300_000); runCurrent()
            assertEquals(0, f.narrations.count { it == ("c" to SegmentFormat.STORY) })
        }
    }

    @Test
    fun driverIsAskedWhereTheyAreHeadingAndTripShapesStories() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 800.0))))
        f.reply = ConversationReply("Salzburg, lovely!", tripContext = "driving to Salzburg for a concert")
        val s = session(f)
        running(s) {
            s.setModeOverride(TravelMode.DRIVING); runCurrent()
            s.onLocation(fix(speed = 20f)); runCurrent()
            advanceTimeBy(4_000); runCurrent()
            assertTrue("Where are we heading?" in f.spoken)
            advanceTimeBy(11_000); runCurrent()
            s.ask("We're going to Salzburg for a concert"); runCurrent()
            advanceTimeBy(15_000); runCurrent()
            assertEquals("driving to Salzburg for a concert", s.state.value.tripContext)
            // Asked only once per session.
            advanceTimeBy(120_000); runCurrent()
            assertEquals(1, f.spoken.count { it == "Where are we heading?" })
        }
    }

    @Test
    fun favoritesToggleByUiAndVoiceAndPersist() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 100.0)), place("b", Geo.destination(here, 90.0, 110.0))))
        val file = FavFile()
        f.reply = ConversationReply("Saved it for you.", ConversationAction.STAR_PLACE, entityId = "b")
        val s = session(f, file)
        running(s) {
            s.onLocation(fix()); runCurrent()
            s.toggleFavorite("a"); runCurrent()
            assertEquals(listOf("a"), s.state.value.favorites.map { it.id })
            s.ask("save the second one for later"); runCurrent()
            advanceTimeBy(15_000); runCurrent()
            assertEquals(setOf("a", "b"), s.state.value.favorites.map { it.id }.toSet())
            s.toggleFavorite("a"); runCurrent()
            assertEquals(listOf("b"), s.state.value.favorites.map { it.id })
        }
        val restored = Favorites().apply { restore(file.data) }
        assertEquals(listOf("b"), restored.all.map { it.id })
    }

    @Test
    fun focusGetsGalleryPhotos() = runTest {
        val f = Fake(listOf(place("a", Geo.destination(here, 0.0, 100.0))))
        val s = session(f)
        running(s) {
            s.onLocation(fix()); runCurrent()
            advanceTimeBy(4_000); runCurrent()
            assertEquals(listOf("https://img/1.jpg", "https://img/2.jpg"), s.state.value.focus?.gallery)
        }
    }

    @Test
    fun shareTextHasNameSummaryAndLinks() {
        val fav = FavoritePlace.of(
            place("wiki:en:1", GeoPoint(47.9, 13.8), name = "Schloss Ort").copy(
                extract = "Schloss Ort is a castle on an island in Lake Traun. It dates to the 11th century. " + "More. ".repeat(80),
                url = "https://en.wikipedia.org/wiki/Schloss_Ort",
            ),
            1,
        )
        val text = ShareText.build(fav)
        assertTrue(text.startsWith("📍 Schloss Ort"))
        assertTrue("https://en.wikipedia.org/wiki/Schloss_Ort" in text)
        assertTrue("openstreetmap.org/?mlat=47.9&mlon=13.8" in text)
        assertTrue(fav.summary!!.length <= 281)
    }

    @Test
    fun galleryFiltersNonPhotos() {
        assertTrue(WikipediaClient.isPhoto("File:Schloss Ort Gmunden 2019.jpg"))
        assertFalse(WikipediaClient.isPhoto("File:Flag of Austria.svg"))
        assertFalse(WikipediaClient.isPhoto("File:Commons-logo.png"))
        assertFalse(WikipediaClient.isPhoto("File:Locator map Gmunden.png"))
    }

    @Test
    fun promptsCarryPersonaHumourRulesAndTeaserFormat() {
        val p = RadioAgent.narrationInstructions("en-US", HostStyle.ENTERTAINING)
        assertTrue("fun fact" in p && "humour" in p.lowercase() && "never joke about tragedies" in p)
        assertTrue("format \"teaser\"" in p)
        val c = RadioAgent.conversationInstructions("en-US", searchAvailable = false, style = HostStyle.KIDS)
        assertTrue(HostStyle.KIDS.persona in c)
        assertTrue("clarifying" in c && "pending_offer" in c && "trip_context" in c)
    }
}
