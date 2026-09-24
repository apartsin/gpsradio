package com.gpsradio.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.data.AppSettings
import com.gpsradio.app.platform.FileJournalStore
import com.gpsradio.app.ui.GpsRadioTheme
import com.gpsradio.app.ui.JournalActions
import com.gpsradio.app.ui.JournalList
import com.gpsradio.app.ui.RadioActions
import com.gpsradio.app.ui.RadioContent
import com.gpsradio.app.ui.SettingsScreen
import com.gpsradio.app.ui.TourBanner
import com.gpsradio.app.ui.TourChips
import com.gpsradio.app.ui.dayLabel
import com.gpsradio.app.ui.shortDistance
import com.gpsradio.app.ui.tourProgress
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.journal.Journal
import com.gpsradio.core.journal.JournalEntry
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.RadioUiState
import com.gpsradio.core.tour.TourState
import com.gpsradio.core.tour.TourStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class TourJournalUiTest {
    @get:Rule val compose = createComposeRule()

    private val here = GeoPoint(47.61, 13.78)
    private val loc = LocationContext(here, 6f, 0, 1.2, null, TravelMode.WALKING)

    private fun place(id: String, name: String, at: GeoPoint) =
        PlaceCandidate(id, name, "castle", at, "wikipedia:en", 0.9, 0.8, setOf(Topic.HISTORY), extract = "Facts.")

    private val tour = TourState(
        stops = listOf(
            TourStop(place("a", "Castle", Geo.destination(here, 0.0, 250.0)), 250.0, 4.2),
            TourStop(place("b", "Church", Geo.destination(here, 90.0, 400.0)), 300.0, 13.0),
            TourStop(place("c", "Bridge", Geo.destination(here, 180.0, 150.0)), 350.0, 22.0),
        ),
        nextIndex = 1,
        minutes = 30,
        start = here,
    )

    private val today = LocalDate.now().toString()
    private val journal = listOf(
        JournalEntry("a", "Castle", System.currentTimeMillis(), here, "Built on a rock.", "https://w/a", today),
        JournalEntry("old", "Old Mill", 1_000L, here, "It ground flour for 300 years.", null, "2020-01-02"),
    )

    @Test
    fun chipsStartToursAndHideDuringATour() {
        val started = mutableListOf<Int>()
        compose.setContent { GpsRadioTheme { TourChips(null, onStartTour = { started += it }) } }
        compose.onNodeWithText("Walking tour:").assertIsDisplayed()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("60 min").performClick()
        assertEquals(listOf(15, 60), started)
    }

    @Test
    fun bannerShowsProgressAndEnds() {
        var ended = false
        compose.setContent { GpsRadioTheme { TourBanner(tour, loc, onEndTour = { ended = true }) } }
        compose.onNodeWithText("Stop 2 of 3 · Church · 400 m").assertIsDisplayed()
        compose.onNodeWithText("End tour").performClick()
        assertTrue(ended)
    }

    @Test
    fun progressAndDistanceFormatting() {
        assertEquals("Stop 2 of 3 · Church", tourProgress(tour, null))
        assertEquals("Tour complete", tourProgress(tour.copy(nextIndex = 3), loc))
        assertEquals("250 m", shortDistance(247.0))
        assertTrue(shortDistance(1_520.0).startsWith("1"))
        val d = LocalDate.of(2026, 9, 23)
        assertEquals("Today", dayLabel("2026-09-23", d))
        assertEquals("Yesterday", dayLabel("2026-09-22", d))
        assertEquals("2026-09-01", dayLabel("2026-09-01", d))
    }

    @Test
    fun journalListsDaysWithRetellShareAndExport() {
        val retold = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val exported = mutableListOf<String>()
        val actions = JournalActions(onRetell = { retold += it.placeId }, onShare = { shared += it.placeId }, onExportDay = { exported += it })
        compose.setContent { GpsRadioTheme { JournalList(journal, canRetell = true, actions = actions) } }
        compose.onNodeWithText("Journal").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Built on a rock.").assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Tell me again"))[0].performClick()
        compose.onNodeWithContentDescription("Share Castle").performClick()
        compose.onNodeWithText("2020-01-02").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Share Old Mill").performScrollTo().performClick()
        assertEquals(listOf("a"), retold)
        assertEquals(listOf("a", "old"), shared)
        compose.onNodeWithTag("journal").assertIsDisplayed()
    }

    @Test
    fun journalWithoutRadioHasNoRetellButton() {
        compose.setContent { GpsRadioTheme { JournalList(journal.take(1), canRetell = false, actions = JournalActions()) } }
        compose.onNodeWithText("Castle").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("Tell me again") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test
    fun menuPagesShowTourBannerChipsAndJournal() {
        val started = mutableListOf<Int>()
        var ended = false
        val actions = RadioActions(onStartTour = { started += it }, onEndTour = { ended = true })
        var state by androidx.compose.runtime.mutableStateOf(
            RadioUiState(radioState = RadioState.RADIO, location = loc, tour = tour, journal = journal),
        )
        compose.setContent { GpsRadioTheme { RadioContent(state, false, actions, placePanel = { _, _ -> }, menuOpen = true) } }
        // Nearby (from the menu): the running tour's banner, and chips only when no tour is running.
        compose.onNodeWithText("Nearby").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("tourBanner").assertIsDisplayed()
        compose.onNodeWithText("End tour").performClick()
        assertTrue(ended)
        assertEquals(0, compose.onAllNodesWithTextCount("30 min"))
        state = state.copy(tour = null)
        compose.onNodeWithText("30 min").performClick()
        assertEquals(listOf(30), started)
    }

    @Test
    fun savedPageShowsTheJournalWithExport() {
        val exported = mutableListOf<String>()
        val actions = RadioActions(journal = JournalActions(onExportDay = { exported += it }))
        val state = RadioUiState(radioState = RadioState.RADIO, location = loc, journal = journal)
        compose.setContent {
            GpsRadioTheme { RadioContent(state, false, actions, placePanel = { _, _ -> }, initialPage = com.gpsradio.app.ui.RadioPage.SAVED) }
        }
        compose.onNodeWithText("Journal").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTextCount("Tell me again"))
        compose.onAllNodes(androidx.compose.ui.test.hasText("Export GPX"))[0].performClick()
        assertEquals(listOf(today), exported)
    }

    @Test
    fun journalIsReachableWhileOffAir() {
        compose.setContent { GpsRadioTheme { RadioContent(RadioUiState(journal = journal), false, RadioActions(), placePanel = { _, _ -> }, menuOpen = true) } }
        compose.onNodeWithText("Saved & journal").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Journal").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Tell me again"))
    }

    @Test
    fun soundEffectsToggleIsSaved() {
        var saved: AppSettings? = null
        compose.setContent {
            GpsRadioTheme { SettingsScreen(AppSettings(apiKey = "sk-x"), onSave = { saved = it }, onClearHistory = {}, onBack = {}) }
        }
        compose.onNodeWithContentDescription("Sound effects").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertFalse(saved!!.soundEffects)
    }

    @Test
    fun journalFileStoreRoundTrips() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val j = Journal().apply { record("a", "Castle", here, "Built on a rock. More.", null, System.currentTimeMillis()) }
        FileJournalStore(context).save(j.serialize())
        val restored = Journal().apply { restore(FileJournalStore(context).load()) }
        assertEquals(listOf("Castle"), restored.all.map { it.name })
    }
}
