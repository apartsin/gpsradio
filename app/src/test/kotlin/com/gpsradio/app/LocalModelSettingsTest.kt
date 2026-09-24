package com.gpsradio.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.data.AppSettings
import com.gpsradio.app.platform.ModelDownload
import com.gpsradio.app.platform.NanoState
import com.gpsradio.app.ui.GpsRadioTheme
import com.gpsradio.app.ui.LocalAiUi
import com.gpsradio.app.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class LocalModelSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun withoutNanoTheOpenModelCanBeDownloadedAndChosen() {
        var saved: AppSettings? = null
        val downloads = ArrayList<String>()
        compose.setContent {
            GpsRadioTheme {
                SettingsScreen(
                    AppSettings(apiKey = "sk-x"), onSave = { saved = it }, onClearHistory = {}, onBack = {},
                    localAi = LocalAiUi(nano = NanoState.UNAVAILABLE, onDownload = { downloads += it }),
                )
            }
        }
        compose.onNodeWithText("Gemini Nano isn't available on this phone. Download an open model instead.").performScrollTo()
        compose.onNodeWithTag("localModelDownload").performScrollTo().performClick()
        assertEquals(listOf("gemma4-e2b"), downloads)

        compose.onNodeWithTag("localModel").performScrollTo().performClick()
        compose.onNodeWithText("Qwen3 1.7B (977 MB)").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals("qwen3-1.7b", saved!!.localModel)
    }

    @Test
    fun downloadShowsProgressAndReadyModelCanBeDeleted() {
        val deleted = ArrayList<String>()
        compose.setContent {
            GpsRadioTheme {
                SettingsScreen(
                    AppSettings(apiKey = "sk-x", localModel = "gemma4-e4b"), onSave = {}, onClearHistory = {}, onBack = {},
                    localAi = LocalAiUi(
                        installed = setOf("gemma4-e4b"),
                        downloads = mapOf("gemma4-e2b" to ModelDownload(0.4f)),
                        onDelete = { deleted += it },
                    ),
                )
            }
        }
        compose.onNodeWithText("Gemma 4 E4B is downloaded and ready.").performScrollTo()
        compose.onNodeWithTag("localModelDelete").performScrollTo().performClick()
        assertEquals(listOf("gemma4-e4b"), deleted)
    }

    @Test
    fun providersAreChosenPerJob() {
        var saved: AppSettings? = null
        compose.setContent {
            GpsRadioTheme {
                SettingsScreen(AppSettings(apiKey = "sk-x"), onSave = { saved = it }, onClearHistory = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("storyProvider").performScrollTo().performClick()
        compose.onNodeWithText("On this phone (free, offline)").performClick()
        compose.onNodeWithTag("voiceProvider").performScrollTo().performClick()
        compose.onNodeWithText("Phone voice (free, offline)").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(true, saved!!.storyOnDevice)
        assertEquals(true, saved!!.voiceOnDevice)
        assertEquals(false, saved!!.assistantOnDevice)
    }
}
