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
import androidx.compose.ui.test.performTextInput
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

    private fun fix() = app.session.onLocation(LocationSample(47.9180, 13.7990, 5f, System.currentTimeMillis(), 0f))

    @Test
    fun narratesNearbyStoryAnswersQuestionAndRemembersPreference() {
        // First launch: enter a (fake) key.
        compose.onNodeWithText("OpenAI API key").performTextInput("sk-test")
        compose.onNodeWithText("Start").performScrollTo().performClick()

        // Start the radio and feed GPS fixes.
        compose.onNodeWithContentDescription("Start radio").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Stop radio"), 10_000)
        repeat(3) { fix(); Thread.sleep(300) }

        // A grounded story about the nearby castle is narrated and shown.
        compose.waitUntilAtLeastOneExists(hasText("FAKE-STORY", substring = true), 30_000)
        assertTrue(FakeServices.played.any { it.startsWith("AUDIO:") })

        // Ask a follow-up by typing; the answer arrives in the transcript.
        compose.onNodeWithTag("askField").performTextInput("How long is the bridge?")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntilAtLeastOneExists(hasText("FAKE-ANSWER", substring = true), 30_000)
        compose.onNodeWithText("How long is the bridge?").assertExists()

        // The preference the model extracted is remembered and visible in Settings.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("style: Keep stories short"), 10_000)
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithContentDescription("Stop radio").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Radio off"), 10_000)
    }
}
