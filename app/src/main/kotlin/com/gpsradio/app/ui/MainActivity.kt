package com.gpsradio.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                var autoStart by remember { mutableStateOf(false) }
                when {
                    !settings.canListen -> SetupScreen(
                        settings,
                        onSave = { next ->
                            vm.saveSettings { next }
                            // "Save and start listening": go straight to the radio (asks for location there).
                            autoStart = true
                        },
                        // Keyless preview: same radio screen, stories read on the device.
                        onTryWithoutKey = { next ->
                            vm.saveSettings { next }
                            autoStart = true
                        },
                    )
                    showSettings -> SettingsScreen(
                        settings = settings,
                        onSave = { next -> vm.saveSettings { next }; showSettings = false },
                        onClearHistory = vm::clearHistory,
                        memory = radio.memory,
                        onForgetMemory = vm::forgetMemory,
                        onForgetAllMemory = vm::clearMemory,
                        onBack = { showSettings = false },
                    )
                    else -> RadioScreen(
                        vm,
                        onOpenSettings = { showSettings = true },
                        autoStart = autoStart,
                        onAutoStarted = { autoStart = false },
                    )
                }
            }
        }
    }
}
