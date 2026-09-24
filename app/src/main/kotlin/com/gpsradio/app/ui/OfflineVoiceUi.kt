package com.gpsradio.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gpsradio.app.platform.OfflineVoiceInfo
import com.gpsradio.app.platform.OfflineVoices

/**
 * The phone's TextToSpeech engines and whether [engine] (null = system default) has an offline voice for
 * [languageTag], looked up while Settings is shown. Starts as "not known yet".
 */
@Composable
fun rememberOfflineVoiceInfo(engine: String?, languageTag: String): OfflineVoiceInfo {
    val context = LocalContext.current.applicationContext
    var info by remember { mutableStateOf(OfflineVoiceInfo()) }
    DisposableEffect(engine, languageTag) {
        val cancel = OfflineVoices.query(context, engine, languageTag) { info = it }
        onDispose { cancel() }
    }
    return info
}

/** Opens the speech engine's "install voice data" screen; false if the phone has none. */
fun openInstallVoiceData(context: Context): Boolean = try {
    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
