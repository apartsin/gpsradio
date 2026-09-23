package com.gpsradio.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GpsRadioTheme {
                val settings by vm.settings.collectAsStateWithLifecycle()
                val radio by vm.radio.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                when {
                    !settings.hasApiKey -> SetupScreen(settings, onSave = { next -> vm.saveSettings { next } })
                    showSettings -> SettingsScreen(
                        settings = settings,
                        onSave = { next -> vm.saveSettings { next }; showSettings = false },
                        onClearHistory = vm::clearHistory,
                        memory = radio.memory,
                        onForgetMemory = vm::forgetMemory,
                        onForgetAllMemory = vm::clearMemory,
                        onBack = { showSettings = false },
                    )
                    else -> RadioScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

@Composable
fun GpsRadioTheme(content: @Composable () -> Unit) {
    val teal = Color(0xFF1F5E57)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF7FD1C4), secondary = Color(0xFFF2B880))
    } else {
        lightColorScheme(primary = teal, secondary = Color(0xFFB5652B))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
