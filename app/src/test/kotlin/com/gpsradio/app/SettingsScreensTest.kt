package com.gpsradio.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsradio.app.data.AppSettings
import com.gpsradio.app.ui.GpsRadioTheme
import com.gpsradio.app.ui.SettingsScreen
import com.gpsradio.app.ui.SetupScreen
import com.gpsradio.core.memory.MemoryCategory
import com.gpsradio.core.memory.MemoryItem
import com.gpsradio.core.model.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class SettingsScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun setupRequiresKeyAndSavesInterests() {
        var saved: AppSettings? = null
        compose.setContent { GpsRadioTheme { SetupScreen(AppSettings(), onSave = { saved = it }) } }
        compose.onNodeWithText("Save and start listening").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("OpenAI API key").performTextInput("  sk-test-123  ")
        compose.onNodeWithText("Food").performScrollTo().performClick()
        compose.onNodeWithText("Late-night chill").performScrollTo().performClick()
        compose.onNodeWithText("Save and start listening").performScrollTo().assertIsEnabled().performClick()
        assertEquals("sk-test-123", saved!!.apiKey)
        assertTrue(Topic.FOOD in saved!!.interests)
        // Russian is the default narration language.
        assertEquals(false, saved!!.languageAuto)
        assertEquals("ru-RU", saved!!.preferredLanguage)
        assertEquals(com.gpsradio.core.ai.HostStyle.CHILL, saved!!.hostStyle)
    }

    @Test
    fun settingsListsMemoryAndForgetsItems() {
        val memory = listOf(
            MemoryItem("m1", MemoryCategory.LIKE, "Loves castles", "architecture"),
            MemoryItem("m2", MemoryCategory.STYLE, "Keep stories short"),
        )
        val forgotten = mutableListOf<String>()
        var all = false
        compose.setContent {
            GpsRadioTheme {
                SettingsScreen(
                    AppSettings(apiKey = "sk-x"), onSave = {}, onClearHistory = {}, onBack = {},
                    memory = memory, onForgetMemory = { forgotten += it }, onForgetAllMemory = { all = true },
                )
            }
        }
        compose.onNodeWithText("like: Loves castles").performScrollTo()
        compose.onNodeWithContentDescription("Forget Keep stories short").performScrollTo().performClick()
        compose.onNodeWithText("Forget everything about me").performScrollTo().performClick()
        assertEquals(listOf("m2"), forgotten)
        assertTrue(all)
    }
}
