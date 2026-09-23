package com.gpsradio.app

import androidx.compose.ui.test.assertIsDisplayed
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

    private fun show(state: RadioUiState, actions: RadioActions = RadioActions(), recording: Boolean = false) =
        compose.setContent { GpsRadioTheme { RadioContent(state, recording, actions, placePanel = { _, _ -> }) } }

    @Test
    fun idleShowsOffStateAndStartCallsAction() {
        var started = false
        show(RadioUiState(), RadioActions(onStart = { started = true }))
        compose.onNodeWithText("Off air").assertIsDisplayed()
        compose.onNodeWithText("Start listening").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start radio").performClick()
        assertTrue(started)
    }

    @Test
    fun narratingShowsNowPlayingWithSource() {
        val seg = Segment("The castle rises from the lake.", castle.id, "Ort Castle", listOf(SourceRef("Ort Castle", castle.url!!)))
        show(RadioUiState(radioState = RadioState.NARRATING, location = loc, nowPlaying = seg, nearby = listOf(ranked)))
        compose.onAllNodesWithText("On air")[0].assertIsDisplayed()
        compose.onNodeWithText("ON AIR", substring = true).assertIsDisplayed()
        compose.onNodeWithText("The castle rises from the lake.").assertIsDisplayed()
        compose.onNodeWithText("Source: Ort Castle").assertIsDisplayed()
        compose.onNodeWithText("Walking mode · 4 km/h").assertIsDisplayed()
        compose.onNodeWithText("Very local stories", substring = true).assertIsDisplayed()
    }

    @Test
    fun controlsModeChipsAndTypedQuestionsReachActions() {
        val calls = mutableListOf<String>()
        var mode: TravelMode? = TravelMode.UNKNOWN
        show(
            RadioUiState(radioState = RadioState.RADIO, location = loc),
            RadioActions(
                onSkip = { calls += "skip" },
                onPause = { calls += "pause" },
                onRepeat = { calls += "repeat" },
                onNearby = { calls += "nearby" },
                onMode = { mode = it },
                onAsk = { calls += "ask:$it" },
            ),
        )
        compose.onNodeWithContentDescription("Skip").performClick()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Repeat").performClick()
        compose.onNodeWithContentDescription("Nearby?").performClick()
        compose.onNodeWithText("Drive").performClick()
        compose.onNodeWithTag("askField").performTextInput("Is that true?")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("skip", "pause", "repeat", "nearby", "ask:Is that true?"), calls)
        assertEquals(TravelMode.DRIVING, mode)
    }

    @Test
    fun nearbyTabListsPlacesAndTapTellsAboutThem() {
        var told: String? = null
        show(RadioUiState(radioState = RadioState.RADIO, location = loc, nearby = listOf(ranked)), RadioActions(onTellAbout = { told = it }))
        compose.onNodeWithText("Nearby").performClick()
        compose.onNodeWithText("Ort Castle").performClick()
        assertEquals(castle.id, told)
    }

    @Test
    fun transcriptShowsConversationAndErrors() {
        show(
            RadioUiState(
                radioState = RadioState.CONVERSING,
                location = loc,
                status = Status("OpenAI didn't accept the API key. Check it in Settings.", StatusLevel.ERROR, needsKey = true),
                transcript = listOf(
                    TranscriptEntry(Speaker.USER, "Is that actually true?", 1),
                    TranscriptEntry(Speaker.RADIO, "Partly: the treasure is a legend.", 2),
                ),
            ),
        )
        compose.onNodeWithText("Conversation").assertIsDisplayed()
        // The Now tab shows the latest spoken answer; the Transcript tab has the whole exchange.
        compose.onNodeWithText("Answer").assertIsDisplayed()
        compose.onNodeWithText("Transcript").performClick()
        compose.onNodeWithText("Is that actually true?").assertIsDisplayed()
        compose.onNodeWithText("Partly: the treasure is a legend.").assertIsDisplayed()
        compose.onNodeWithText("OpenAI didn't accept the API key. Check it in Settings.").assertIsDisplayed()
        compose.onNodeWithText("Open Settings").assertIsDisplayed()
    }

    @Test
    fun starShareAndNavigateTheFocusedPlace() {
        val calls = mutableListOf<String>()
        show(
            RadioUiState(radioState = RadioState.RADIO, location = loc, focus = FocusPlace.of(castle), nearby = listOf(ranked)),
            RadioActions(onToggleStar = { calls += "star:$it" }, onShare = { calls += "share:$it" }, onNavigate = { calls += "nav:$it" }),
        )
        compose.onNodeWithContentDescription("Save place").performClick()
        compose.onNodeWithContentDescription("Share place").performClick()
        compose.onNodeWithContentDescription("Navigate there").performClick()
        assertEquals(listOf("star:${castle.id}", "share:${castle.id}", "nav:${castle.id}"), calls)
    }

    @Test
    fun savedTabListsFavoritesWithActions() {
        val fav = FavoritePlace.of(castle, 1)
        var removed: String? = null
        show(
            RadioUiState(radioState = RadioState.RADIO, location = loc, favorites = listOf(fav)),
            RadioActions(onRemoveFavorite = { removed = it }),
        )
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Ort Castle").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Ort Castle").performClick()
        assertEquals(castle.id, removed)
    }

    @Test
    fun offerCardAnswersYesOrNo() {
        val answers = mutableListOf<Boolean>()
        show(
            RadioUiState(radioState = RadioState.CONVERSING, location = loc, pendingOffer = "Ort Castle"),
            RadioActions(onAnswerOffer = { answers += it }),
        )
        compose.onNodeWithText("Want the full story about Ort Castle?").assertIsDisplayed()
        compose.onNodeWithText("Yes, tell it").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf(true, false), answers)
        // During a conversation the primary control returns to the radio.
        compose.onNodeWithContentDescription("Back to radio").assertIsDisplayed()
    }

    @Test
    fun drivingShowsGlanceableLayoutWithoutTextEntry() {
        var skipped = false
        show(
            RadioUiState(
                radioState = RadioState.NARRATING,
                location = loc.copy(travelMode = TravelMode.DRIVING, speedMps = 22.0),
                focus = FocusPlace.of(castle),
            ),
            RadioActions(onSkip = { skipped = true }),
        )
        compose.onNodeWithText("Ort Castle").assertIsDisplayed()
        compose.onNodeWithText("Driving mode · 79 km/h").assertIsDisplayed()
        compose.onNodeWithText("Looking ahead along the road", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Hold to ask a question").assertIsDisplayed()
        compose.onNodeWithTag("askField").assertDoesNotExist()
        compose.onNodeWithContentDescription("Skip").performClick()
        assertTrue(skipped)
    }

    @Test
    fun naturalVoiceMicIsTapToTalkAndShowsLiveState() {
        var toggles = 0
        compose.setContent {
            GpsRadioTheme {
                RadioContent(
                    RadioUiState(radioState = RadioState.CONVERSING, location = loc, live = LiveState.LISTENING),
                    recording = false,
                    actions = RadioActions(onLiveToggle = { toggles++ }),
                    placePanel = { _, _ -> },
                    liveMode = true,
                )
            }
        }
        compose.onNodeWithContentDescription("End voice conversation").performClick()
        assertEquals(1, toggles)
        compose.onNodeWithText("Just talk · tap to end").assertExists()
    }
}
