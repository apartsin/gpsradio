package com.gpsradio.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.ui.GpsRadioTheme
import com.gpsradio.app.ui.RadioActions
import com.gpsradio.app.ui.RadioContent
import com.gpsradio.app.ui.RadioPage
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.SourceRef
import com.gpsradio.core.model.Speaker
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TranscriptEntry
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.LiveState
import com.gpsradio.core.session.RadioUiState
import com.gpsradio.core.session.Status
import com.gpsradio.core.session.StatusLevel
import com.gpsradio.core.session.FocusPlace
import com.gpsradio.core.session.DetourSuggestion
import com.gpsradio.core.session.OfferKind
import com.gpsradio.core.favorites.FavoritePlace
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class RadioContentTest {
    @get:Rule val compose = createComposeRule()

    private val here = GeoPoint(47.61, 13.78)
    private val loc = LocationContext(here, 6f, 0, 1.2, 0.0, TravelMode.WALKING)
    private val castle = PlaceCandidate(
        "wiki:en:1", "Ort Castle", "castle", GeoPoint(47.612, 13.78), "wikipedia:en", 0.9, 0.8, setOf(Topic.HISTORY),
        extract = "A castle on a lake.", url = "https://en.wikipedia.org/wiki/Ort_Castle",
    )
    private val ranked = RankedCandidate(castle, 220.0, 0.0, 3.1, ScoreBreakdown(0.8, 1.0, 1.0, 0.6, 1.0, 0.9, 0.0, 0.0))

    private fun show(
        state: RadioUiState,
        actions: RadioActions = RadioActions(),
        recording: Boolean = false,
        page: RadioPage? = null,
        liveMode: Boolean = false,
        alwaysListening: Boolean = false,
    ) = compose.setContent {
        GpsRadioTheme {
            RadioContent(state, recording, actions, placePanel = { _, _ -> }, liveMode = liveMode, alwaysListening = alwaysListening, initialPage = page)
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.waitForIdle()
    }

    @Test
    fun idleShowsOffStateAndStartCallsAction() {
        var started = false
        show(RadioUiState(), RadioActions(onStart = { started = true }))
        compose.onNodeWithText("Off air").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start radio").performClick()
        assertTrue(started)
    }

    @Test
    fun mainScreenHasOnlyRadioMicPictureAndMenu() {
        val calls = mutableListOf<String>()
        show(
            RadioUiState(radioState = RadioState.NARRATING, location = loc, focus = FocusPlace.of(castle), nearby = listOf(ranked)),
            RadioActions(onStop = { calls += "stop" }, onToggleListening = { calls += "mic" }),
            liveMode = true, alwaysListening = true,
        )
        compose.onNodeWithText("Ort Castle").assertIsDisplayed()
        compose.onNodeWithText("ON AIR", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop radio").performClick()
        compose.onNodeWithContentDescription("Turn microphone off").performClick()
        assertEquals(listOf("stop", "mic"), calls)
        // Nothing else on the main screen: no transport buttons, text entry, tabs or per-place actions.
        for (gone in listOf("Skip", "Pause", "Repeat", "Nearby?", "Save place", "Share place", "Navigate there", "Send")) {
            compose.onNodeWithContentDescription(gone).assertDoesNotExist()
        }
        compose.onNodeWithTag("askField").assertDoesNotExist()
        // The menu's items exist off-screen until it's opened.
        compose.onNodeWithText("Transcript").assertIsNotDisplayed()
        compose.onNodeWithTag("offerCard").assertDoesNotExist()
    }

    @Test
    fun micSwitchShowsOpenAndClosed() {
        show(RadioUiState(radioState = RadioState.RADIO, location = loc), liveMode = true, alwaysListening = false)
        compose.onNodeWithContentDescription("Turn microphone on").assertIsDisplayed()
        compose.onNodeWithText("Mic off").assertIsDisplayed()
    }

    @Test
    fun micSwitchIsOpenWhenAlwaysListening() {
        show(RadioUiState(radioState = RadioState.RADIO, location = loc, listening = true, live = LiveState.LISTENING), liveMode = true, alwaysListening = true)
        compose.onNodeWithText("Mic on").assertIsDisplayed()
        compose.onNodeWithTag("micSwitch").assertIsDisplayed()
    }

    @Test
    fun menuOpensSettingsAndHasNoManualModes() {
        var settings = false
        show(RadioUiState(radioState = RadioState.RADIO, location = loc), RadioActions(onOpenSettings = { settings = true }))
        openMenu()
        // The travel mode is inferred automatically: no manual mode choices.
        for (m in listOf("Walk", "Cycle", "Drive", "Still")) compose.onNodeWithText(m).assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        assertTrue(settings)
    }

    @Test
    fun drivingUsesTheSameSimpleScreen() {
        show(
            RadioUiState(
                radioState = RadioState.NARRATING,
                location = loc.copy(travelMode = TravelMode.DRIVING, speedMps = 22.0),
                focus = FocusPlace.of(castle),
            ),
        )
        compose.onNodeWithText("Ort Castle").assertIsDisplayed()
        compose.onNodeWithText("Driving mode", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop radio").assertIsDisplayed()
        compose.onNodeWithTag("detourCard").assertDoesNotExist()
    }

    @Test
    fun statusShowsErrorsWithASettingsLink() {
        var opened = false
        show(
            RadioUiState(
                radioState = RadioState.RADIO,
                location = loc,
                status = Status("OpenAI didn't accept the API key. Check it in Settings.", StatusLevel.ERROR, needsKey = true),
            ),
            RadioActions(onOpenSettings = { opened = true }),
        )
        compose.onNodeWithText("OpenAI didn't accept the API key. Check it in Settings.").assertIsDisplayed()
        compose.onNodeWithText("Open Settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun nearbyFromTheMenuListsPlacesAndTapTellsAboutThem() {
        var told: String? = null
        show(RadioUiState(radioState = RadioState.RADIO, location = loc, nearby = listOf(ranked)), RadioActions(onTellAbout = { told = it }))
        openMenu()
        compose.onNodeWithText("Nearby").performClick()
        compose.onNodeWithText("Ort Castle").performClick()
        assertEquals(castle.id, told)
        // Back returns to the main screen.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
    }

    @Test
    fun transcriptPageShowsTheConversation() {
        show(
            RadioUiState(
                radioState = RadioState.CONVERSING,
                location = loc,
                transcript = listOf(
                    TranscriptEntry(Speaker.USER, "Is that actually true?", 1),
                    TranscriptEntry(Speaker.RADIO, "Partly: the treasure is a legend.", 2),
                ),
            ),
            page = RadioPage.TRANSCRIPT,
        )
        compose.onNodeWithText("Is that actually true?").assertIsDisplayed()
        compose.onNodeWithText("Partly: the treasure is a legend.").assertIsDisplayed()
    }

    @Test
    fun savedPageListsFavoritesWithActions() {
        val fav = FavoritePlace.of(castle, 1)
        var removed: String? = null
        show(
            RadioUiState(radioState = RadioState.RADIO, location = loc, favorites = listOf(fav)),
            RadioActions(onRemoveFavorite = { removed = it }),
            page = RadioPage.SAVED,
        )
        compose.onNodeWithText("Ort Castle").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Ort Castle").performClick()
        assertEquals(castle.id, removed)
    }

    @Test
    fun nearbyMarksPhotoSpots() {
        show(RadioUiState(radioState = RadioState.RADIO, location = loc, nearby = listOf(ranked), photoSpotIds = setOf(castle.id)), page = RadioPage.NEARBY)
        compose.onNodeWithContentDescription("Photo spot").assertIsDisplayed()
    }

    @Test
    fun nearbyShowsWhatMakesAPlaceSpecial() {
        val special = ranked.copy(place = castle.copy(features = setOf(com.gpsradio.core.model.PlaceFeature.JEWISH_HERITAGE, com.gpsradio.core.model.PlaceFeature.FILM_LOCATION)))
        show(RadioUiState(radioState = RadioState.RADIO, location = loc, nearby = listOf(special)), page = RadioPage.NEARBY)
        compose.onNodeWithText("Jewish heritage", substring = true).assertIsDisplayed()
        compose.onNodeWithText("filmed here", substring = true).assertIsDisplayed()
    }

    @Test
    fun nearbyListsEventsTodayAboveThePlaces() {
        val event = com.gpsradio.core.events.LocalEvent(
            "Jazz on the Lake", "concert", "Esplanade", System.currentTimeMillis() + 3_600_000, null, "https://example.org/jazz", "Open-air, free", 1.2,
        )
        show(RadioUiState(radioState = RadioState.RADIO, location = loc, nearby = listOf(ranked), todayEvents = listOf(event)), page = RadioPage.NEARBY)
        compose.onNodeWithText("Today nearby").assertIsDisplayed()
        compose.onNodeWithText("Jazz on the Lake").assertIsDisplayed()
        compose.onNodeWithText("Esplanade · 1.2 km · Open-air, free").assertIsDisplayed()
        compose.onNodeWithText("Ort Castle").assertIsDisplayed()
    }
}
