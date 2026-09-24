package com.gpsradio.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.platform.FileAreaCacheStore
import com.gpsradio.app.platform.NetworkMonitor
import com.gpsradio.app.data.AppSettings
import com.gpsradio.app.ui.GpsRadioTheme
import com.gpsradio.app.ui.RadioActions
import com.gpsradio.app.ui.RadioContent
import com.gpsradio.app.ui.SettingsScreen
import com.gpsradio.app.ui.SetupScreen
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.StoryBasis
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.RadioState
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.RadioUiState
import com.gpsradio.core.session.Status
import com.gpsradio.core.session.StatusLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class PreviewAndTrustUiTest {
    @get:Rule val compose = createComposeRule()

    private val loc = LocationContext(GeoPoint(47.61, 13.78), 6f, 0, 1.2, 0.0, TravelMode.WALKING)

    @Test
    fun previewStatusOffersToAddKey() {
        var opened = false
        val state = RadioUiState(
            radioState = RadioState.RADIO,
            location = loc,
            status = Status(RadioSession.PREVIEW_NOTE, StatusLevel.INFO, needsKey = true, actionLabel = "Add key"),
        )
        compose.setContent { GpsRadioTheme { RadioContent(state, false, RadioActions(onOpenSettings = { opened = true }), placePanel = { _, _ -> }) } }
        compose.onNodeWithText(RadioSession.PREVIEW_NOTE).assertIsDisplayed()
        compose.onNodeWithText("Add key").performClick()
        assertTrue(opened)
    }

    @Test
    fun setupCanStartWithoutAKey() {
        var tried: AppSettings? = null
        var saved = false
        compose.setContent { GpsRadioTheme { SetupScreen(AppSettings(), onSave = { saved = true }, onTryWithoutKey = { tried = it }) } }
        compose.onNodeWithText("Food").performScrollTo().performClick()
        compose.onNodeWithText("Try without a key (on-device voice)").performScrollTo().assertIsEnabled().performClick()
        assertFalse(saved)
        assertTrue(tried!!.previewMode)
        assertEquals("", tried!!.apiKey)
        assertTrue(tried!!.canListen)
        assertTrue(Topic.FOOD in tried!!.interests)
    }

    @Test
    fun previewSettingsCanBeSavedWithoutKey() {
        var saved: AppSettings? = null
        compose.setContent {
            GpsRadioTheme { SettingsScreen(AppSettings(previewMode = true), onSave = { saved = it }, onClearHistory = {}, onBack = {}) }
        }
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        assertTrue(saved!!.previewMode)
        assertFalse(saved!!.hasApiKey)
    }

    @Test
    fun areaCacheFileRoundTripsAndNetworkCheckIsSafe() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        FileAreaCacheStore(context).save("""{"version":1,"areas":[]}""")
        assertEquals("""{"version":1,"areas":[]}""", FileAreaCacheStore(context).load())
        // Must never throw, whatever Robolectric reports for connectivity.
        NetworkMonitor(context).isOnline()
    }
}
