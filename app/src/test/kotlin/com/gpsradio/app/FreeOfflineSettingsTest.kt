package com.gpsradio.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.data.AppSettings
import com.gpsradio.app.platform.OfflineVoiceInfo
import com.gpsradio.app.platform.TtsEngineOption
import com.gpsradio.app.ui.GpsRadioTheme
import com.gpsradio.app.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class FreeOfflineSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun choosingOnDeviceRecognitionSavesIt() {
        var saved: AppSettings? = null
        var installClicked = false
        compose.setContent {
            GpsRadioTheme {
                SettingsScreen(
                    AppSettings(apiKey = "sk-x"), onSave = { saved = it }, onClearHistory = {}, onBack = {},
                    offlineVoice = OfflineVoiceInfo(
                        engines = listOf(TtsEngineOption("com.google.android.tts", "Speech Services by Google")),
                        voiceInstalled = false,
                    ),
                    onInstallVoiceData = { installClicked = true },
                )
            }
        }
        compose.onNodeWithText("Free & offline").performScrollTo()
        compose.onNodeWithTag("asrEngine").performScrollTo()
        compose.onNodeWithText("System default").performScrollTo()
        compose.onNodeWithText("Install voice data").performScrollTo().performClick()
        assertTrue(installClicked)

        compose.onNodeWithTag("asrEngine").performScrollTo().performClick()
        compose.onNodeWithText("On this phone (free, offline)").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(true, saved!!.asrOnDevice)
        // The offline voice stays on the system default.
        assertNull(saved!!.offlineTtsEngine)
    }

    @Test
    fun offlineVoiceEngineIsPickedFromTheInstalledOnes() {
        var saved: AppSettings? = null
        compose.setContent {
            GpsRadioTheme {
                SettingsScreen(
                    AppSettings(apiKey = "sk-x"), onSave = { saved = it }, onClearHistory = {}, onBack = {},
                    offlineVoice = OfflineVoiceInfo(
                        engines = listOf(TtsEngineOption("com.google.android.tts", "Speech Services by Google")),
                        voiceInstalled = true,
                    ),
                )
            }
        }
        compose.onNodeWithTag("offlineTtsEngine").performScrollTo().performClick()
        compose.onNodeWithText("Speech Services by Google").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals("com.google.android.tts", saved!!.offlineTtsEngine)
        assertEquals(false, saved!!.asrOnDevice)
    }
}
