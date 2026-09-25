package com.gpsradio.app

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.gpsradio.app.ui.MainActivity
import com.gpsradio.core.model.LocationSample
import com.gpsradio.core.model.RadioState
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic flows on a real Android runtime (spec A §74): stories keep coming, "Next" plays a different story at
 * once, and the radio keeps playing on the phone when the OpenAI credit runs out. OpenAI, Wikipedia and
 * OpenStreetMap are [FakeDispatcher]; the phone's voice is faked in [TestGpsRadioApp].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BasicFlowsEndToEndTest {
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

    @Before
    fun setUp() {
        FakeServices.dispatcher.manyPlaces = true
        FakeServices.dispatcher.quotaExhausted = false
        // Another test may have heard these places already: start fresh.
        app.session.clearHistory()
    }

    @After
    fun tearDown() {
        runCatching { app.session.stop() }
        FakeServices.dispatcher.manyPlaces = false
        FakeServices.dispatcher.quotaExhausted = false
    }

    private fun radioInfo(): String {
        val st = app.session.state.value
        return "state=${st.radioState} status=${st.status?.text} nearby=${st.nearby.size} nowPlaying=${st.nowPlaying?.title}\n" +
            "transcript=${st.transcript.takeLast(6).map { "${it.speaker}: ${it.text.take(80)}" }}\n" +
            "requests=${FakeServices.dispatcher.requests.toList().takeLast(20).map { it.substringBefore('?').take(60) }}\n" +
            "played=${FakeServices.played.toList().takeLast(8).map { it.take(60) }}"
    }

    /** Polls [condition] (radio state, not UI) and fails with what the radio was doing. */
    private fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(200)
        }
        val tree = runCatching { compose.onRoot().printToString(maxDepth = 20) }.getOrDefault("<no tree>")
        throw AssertionError("Timed out waiting for $what.\nRADIO: ${radioInfo()}\nScreen:\n$tree")
    }

    private fun fix() = app.session.onLocation(LocationSample(47.9180, 13.7990, 5f, System.currentTimeMillis(), 0f))

    /** First launch asks for a key; later tests find the radio set up and just start it. */
    private fun startRadio() {
        compose.waitForIdle()
        val setup = runCatching { compose.onNodeWithText("OpenAI API key").assertExists() }.isSuccess
        if (setup) {
            compose.onNodeWithText("OpenAI API key").performTextInput("sk-test")
            compose.onNodeWithText("Save and start listening").performScrollTo().performClick()
        } else if (app.session.state.value.radioState == RadioState.IDLE) {
            compose.onNodeWithContentDescription("Start radio").performClick()
        }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Stop radio"), 10_000)
        repeat(3) { fix(); Thread.sleep(300) }
    }

    private fun storiesPlayed() = FakeServices.played.toList()

    @Test
    fun nextPlaysADifferentStoryAtOnce() {
        startRadio()
        waitUntil("a first story", 45_000) { app.session.state.value.nowPlaying != null }
        val first = app.session.state.value.nowPlaying!!.title
        val before = storiesPlayed().size

        compose.onNodeWithTag("nextButton").performClick()
        waitUntil("a different story after Next", 45_000) {
            app.session.state.value.nowPlaying?.title.let { it != null && it != first } && storiesPlayed().size > before
        }
        assertNotEquals(first, app.session.state.value.nowPlaying!!.title)

        // And again: Next keeps working, not just once.
        val second = app.session.state.value.nowPlaying!!.title
        compose.onNodeWithTag("nextButton").performClick()
        waitUntil("a third story after Next", 45_000) {
            app.session.state.value.nowPlaying?.title.let { it != null && it != second }
        }
    }

    @Test
    fun storiesKeepComingOneAfterAnother() {
        startRadio()
        // Non-stop radio: after one story ends, the next starts by itself (no button pressed).
        waitUntil("two different stories in a row", 90_000) {
            FakeServices.played.toList().filter { it.startsWith("AUDIO:") || it.startsWith("DEVICE:") }.toSet().size >= 2
        }
    }

    @Test
    fun outOfCreditTheRadioKeepsPlayingOnThePhone() {
        FakeServices.dispatcher.quotaExhausted = true
        startRadio()
        // OpenAI refuses everything: stories are read from the facts with the phone's voice.
        waitUntil("a story on the phone's voice", 60_000) { storiesPlayed().any { it.startsWith("DEVICE:") } }
        val first = app.session.state.value.nowPlaying?.title
        compose.onNodeWithTag("nextButton").performClick()
        waitUntil("the next story on the phone's voice", 60_000) {
            storiesPlayed().filter { it.startsWith("DEVICE:") }.toSet().size >= 2 &&
                app.session.state.value.nowPlaying?.title != first
        }
        assertTrue(radioInfo(), app.session.state.value.radioState != RadioState.IDLE)

        // The credit is topped up: OpenAI stories come back by themselves (and later tests start from a healthy radio).
        FakeServices.dispatcher.quotaExhausted = false
        val before = storiesPlayed().size
        waitUntil("an OpenAI story again after the credit returns", 180_000) {
            storiesPlayed().drop(before).any { it.startsWith("AUDIO:") }
        }
    }

    @Test
    fun pauseResumeAndStopStart() {
        startRadio()
        waitUntil("a first story", 45_000) { app.session.state.value.nowPlaying != null }
        app.session.pause()
        waitUntil("paused", 10_000) { app.session.state.value.radioState == RadioState.PAUSED }
        app.session.resume()
        waitUntil("playing again", 45_000) { app.session.state.value.radioState != RadioState.PAUSED }

        compose.onNodeWithContentDescription("Stop radio").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Off air"), 10_000)
        compose.onNodeWithContentDescription("Start radio").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Stop radio"), 10_000)
        fix()
        val before = storiesPlayed().size
        waitUntil("a story after restarting", 45_000) { storiesPlayed().size > before }
    }
}
