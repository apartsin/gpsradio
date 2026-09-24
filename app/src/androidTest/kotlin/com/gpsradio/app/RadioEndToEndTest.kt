package com.gpsradio.app

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.gpsradio.app.ui.MainActivity
import com.gpsradio.core.model.LocationSample
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full app on a real Android runtime (emulator): setup → start radio → GPS fix → grounded story
 * narrated → typed question answered in context → learned preference shows up in Settings.
 * OpenAI, Wikipedia and OpenStreetMap are replaced by [FakeDispatcher]; no API key is needed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RadioEndToEndTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GpsRadioApp

    /** Waits for a node; on timeout fails with the whole UI tree so CI logs show what was on screen. */
    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher, timeoutMs: Long) {
        try {
            compose.waitUntilAtLeastOneExists(matcher, timeoutMs)
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot(useUnmergedTree = false).printToString(maxDepth = 30) }.getOrDefault("<no tree>")
            // What the radio itself was doing, and what it asked the fake services: shows *why* nothing happened.
            val st = app.session.state.value
            val radio = "state=${st.radioState} status=${st.status?.text} mode=${st.location?.travelMode} " +
                "nearby=${st.nearby.size} discovering=${st.discovering} nowPlaying=${st.nowPlaying?.title} " +
                "offer=${st.pendingOffer} live=${st.live} lang=${st.sessionLanguage}\n" +
                "transcript=${st.transcript.takeLast(6).map { "${it.speaker}: ${it.text.take(80)}" }}\n" +
                "requests=${FakeServices.dispatcher.requests.toList().map { it.substringBefore('?').take(60) }}\n" +
                "played=${FakeServices.played.toList().map { it.take(60) }}"
            throw AssertionError("Timed out waiting for ${matcher.description}.\nRADIO: $radio\nScreen:\n$tree", e)
        }
    }

    private fun fix() = app.session.onLocation(LocationSample(47.9180, 13.7990, 5f, System.currentTimeMillis(), 0f))

    private fun openMenuItem(label: String) {
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(label).performClick()
    }

    @Test
    fun narratesNearbyStoryAnswersQuestionAndRemembersPreference() {
        // First launch: enter a (fake) key.
        compose.onNodeWithText("OpenAI API key").performTextInput("sk-test")
        compose.onNodeWithText("Save and start listening").performScrollTo().performClick()

        // Saving starts the radio right away (location already granted); feed GPS fixes.
        waitFor(hasContentDescription("Stop radio"), 10_000)
        repeat(3) { fix(); Thread.sleep(300) }

        // The main screen shows the place on air; the story itself is in the Transcript (menu).
        waitFor(hasText("Schloss Ort"), 30_000)
        openMenuItem("Transcript")
        waitFor(hasText("FAKE-STORY", substring = true), 30_000)
        assertTrue(FakeServices.played.any { it.startsWith("AUDIO:") })

        // A follow-up question (spoken in the app; sent as text here): the answer arrives in the transcript.
        app.session.ask("How long is the bridge?")
        waitFor(hasText("FAKE-ANSWER", substring = true), 30_000)
        compose.onNodeWithTag("transcript").performScrollToNode(hasText("How long is the bridge?"))
        compose.onNodeWithContentDescription("Back").performClick()

        // Saved places (voice: "save this place"): it appears under Saved & journal in the menu.
        val focus = app.session.state.value.focus!!.id
        app.session.toggleFavorite(focus)
        openMenuItem("Saved & journal")
        waitFor(hasText("Schloss Ort"), 5_000)
        compose.onNodeWithContentDescription("Back").performClick()

        // The preference the model extracted is remembered and visible in Settings (menu).
        openMenuItem("Settings")
        waitFor(hasText("style: Keep stories short"), 10_000)
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithContentDescription("Stop radio").performClick()
        waitFor(hasText("Off air"), 10_000)
    }
}
