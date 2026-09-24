package com.gpsradio.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.platform.UpdateState
import com.gpsradio.app.ui.GpsRadioTheme
import com.gpsradio.app.ui.UpdateBanner
import com.gpsradio.app.ui.UpdatesSection
import com.gpsradio.core.update.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()

    private val info = UpdateInfo("0.5.150", 150, "https://example.org/a.apk", "0".repeat(64), notes = "Better stories")

    @Test
    fun settingsSectionChecksShowsAndInstallsAnUpdate() {
        var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        var checks = 0
        val installed = mutableListOf<UpdateInfo>()
        compose.setContent {
            GpsRadioTheme { UpdatesSection(state, onCheck = { checks++ }, onInstall = { installed += it }, onAllowInstalls = {}) }
        }
        compose.onNodeWithTag("appVersion").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
        compose.onNodeWithText("Check for updates").performClick()
        assertEquals(1, checks)
        state = UpdateState.UpToDate
        compose.onNodeWithText("You have the latest tested version.").assertIsDisplayed()
        state = UpdateState.Available(info)
        compose.onNodeWithTag("updateStatus").assertTextContains("0.5.150", substring = true)
        compose.onNodeWithText("Install 0.5.150").performClick()
        assertEquals(listOf(info), installed)
        state = UpdateState.Downloading(info, 0.42f)
        compose.onNodeWithTag("updateStatus").assertTextContains("42%", substring = true)
    }

    @Test
    fun installPermissionIsRequestedOnce() {
        var allowed = false
        compose.setContent {
            GpsRadioTheme { UpdatesSection(UpdateState.NeedsPermission(info), onCheck = {}, onInstall = {}, onAllowInstalls = { allowed = true }) }
        }
        compose.onNodeWithText("Allow installs").performClick()
        assertTrue(allowed)
        compose.onNodeWithText("Install 0.5.150").assertIsDisplayed()
    }

    @Test
    fun bannerOffersTheUpdateOnlyWhenOneIsReady() {
        var state by mutableStateOf<UpdateState>(UpdateState.UpToDate)
        val installed = mutableListOf<UpdateInfo>()
        compose.setContent { GpsRadioTheme { UpdateBanner(state, onInstall = { installed += it }, onAllowInstalls = {}) } }
        compose.onNodeWithTag("updateBanner").assertDoesNotExistCompat()
        state = UpdateState.Available(info)
        compose.onNodeWithText("Update 0.5.150 available").assertIsDisplayed()
        compose.onNodeWithText("Better stories").assertIsDisplayed()
        compose.onNodeWithText("Install").performClick()
        assertEquals(listOf(info), installed)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

    @Test
    fun onStartANewerVersionIsOfferedWithUpdateAndLater() {
        var updated = false
        var later = false
        compose.setContent {
            com.gpsradio.app.ui.UpdatePrompt(info, onUpdate = { updated = true }, onLater = { later = true })
        }
        compose.onNodeWithText("Update available").assertExists()
        compose.onNodeWithText("GPS Radio 0.5.150", substring = true).assertExists()
        compose.onNodeWithText("Update").performClick()
        org.junit.Assert.assertTrue(updated)
        compose.onNodeWithText("Later").performClick()
        org.junit.Assert.assertTrue(later)
    }
}
