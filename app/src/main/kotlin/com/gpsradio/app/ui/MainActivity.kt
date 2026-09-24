package com.gpsradio.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpsradio.app.GpsRadioApp
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** The interface language this activity was created with (spec A §49); a change recreates it. */
    private var uiLanguage: String? = null

    override fun attachBaseContext(newBase: Context) {
        uiLanguage = (newBase.applicationContext as? GpsRadioApp)?.uiLanguage()
        val tag = uiLanguage
        if (tag == null) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /** Set by the "out of OpenAI credit" notification: open Settings so the listener can add a key. */
    private val openSettingsRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openSettingsRequest.value = intent.wantsSettings()
        enableEdgeToEdge()
        setContent {
            GpsRadioTheme {
                val settings by vm.settings.collectAsStateWithLifecycle()
                // A new narration language also switches the interface.
                LaunchedEffect(settings) {
                    val now = (application as GpsRadioApp).uiLanguage()
                    if (uiLanguage != null && now != null && now != uiLanguage) recreate()
                }
                val radio by vm.radio.collectAsStateWithLifecycle()
                val update by vm.update.collectAsStateWithLifecycle()
                val cost by vm.cost.collectAsStateWithLifecycle()
                // Android's "install this update?" screen: open it from here, the visible activity (spec B §36).
                val installConfirm by (application as GpsRadioApp).updater.confirm.collectAsStateWithLifecycle()
                LaunchedEffect(installConfirm) {
                    if (installConfirm != null) (application as GpsRadioApp).updater.showPendingConfirm(this@MainActivity)
                }
                var showSettings by remember { mutableStateOf(false) }
                var autoStart by remember { mutableStateOf(false) }
                val openSettings by openSettingsRequest
                LaunchedEffect(openSettings) {
                    if (openSettings) {
                        showSettings = true
                        openSettingsRequest.value = false
                    }
                }
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
                        update = update,
                        onCheckUpdate = { vm.checkForUpdate(manual = true) },
                        onInstallUpdate = vm::installUpdate,
                        onAllowInstalls = vm::allowInstalls,
                        cost = cost,
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

    override fun onResume() {
        super.onResume()
        // Rate-limited to every 6 h; also re-evaluates after returning from the "install unknown apps" page.
        vm.checkForUpdate(manual = false)
        // An update confirmation that arrived while the app was in the background.
        (application as com.gpsradio.app.GpsRadioApp).updater.showPendingConfirm(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.wantsSettings()) openSettingsRequest.value = true
    }

    private fun Intent?.wantsSettings() = this?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.gpsradio.app.OPEN_SETTINGS"
    }
}
