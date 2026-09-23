package com.gpsradio.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gpsradio.app.GpsRadioApp
import com.gpsradio.app.data.AppSettings
import com.gpsradio.app.platform.VoiceRecorder
import com.gpsradio.app.service.RadioService
import android.content.Intent
import android.net.Uri
import com.gpsradio.core.favorites.FavoritePlace
import com.gpsradio.core.favorites.ShareText
import com.gpsradio.core.model.TravelMode
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.RadioUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as GpsRadioApp
    private val session: RadioSession = graph.session
    private val recorder = VoiceRecorder(app)

    val settings: StateFlow<AppSettings> = graph.settings.settings
    val radio: StateFlow<RadioUiState> = session.state

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        graph.settings.update(transform)
    }

    fun startRadio() {
        RadioService.start(getApplication<Application>())
    }

    fun stopRadio() {
        RadioService.stop(getApplication<Application>())
    }

    fun pause() {
        session.pause()
    }

    fun resume() {
        session.resume()
    }

    fun skip() {
        session.skip()
    }

    fun repeat() {
        session.repeat()
    }

    fun whatsNearby() {
        session.whatsNearby()
    }

    fun tellAbout(id: String) {
        session.tellAbout(id)
    }

    fun ask(text: String) {
        session.ask(text)
    }

    fun setMode(mode: TravelMode?) {
        session.setModeOverride(mode)
    }

    fun clearHistory() {
        session.clearHistory()
    }

    fun toggleFavorite(id: String) {
        session.toggleFavorite(id)
    }

    fun removeFavorite(id: String) {
        session.removeFavorite(id)
    }

    fun answerOffer(yes: Boolean) {
        session.answerOffer(yes)
    }

    /** Snapshot of any place the UI can show: nearby, saved, or the one in focus. */
    private fun placeSnapshot(id: String): FavoritePlace? {
        val s = radio.value
        s.nearby.firstOrNull { it.place.id == id }?.let { return FavoritePlace.of(it.place, System.currentTimeMillis()) }
        s.favorites.firstOrNull { it.id == id }?.let { return it }
        return s.focus?.takeIf { it.id == id }?.let { FavoritePlace(it.id, it.name, "place", it.point, null, it.url, it.imageUrl) }
    }

    /** Android share sheet with the place name, a short summary and links. */
    fun shareIntent(id: String): Intent? {
        val place = placeSnapshot(id) ?: return null
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, ShareText.subject(place))
            .putExtra(Intent.EXTRA_TEXT, ShareText.build(place))
        return Intent.createChooser(send, "Share ${place.name}")
    }

    /** Hands off to the user's maps app for directions. */
    fun navigateIntent(id: String): Intent? {
        val p = placeSnapshot(id) ?: return null
        val label = Uri.encode(p.name)
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.point.lat},${p.point.lon}?q=${p.point.lat},${p.point.lon}($label)"))
    }

    fun forgetMemory(id: String) {
        session.forgetMemory(id)
    }

    fun clearMemory() {
        session.clearMemory()
    }

    /** Push-to-talk: start capturing and interrupt narration (barge-in). */
    fun startTalking() {
        if (recorder.isRecording) return
        session.pause()
        runCatching { recorder.start() }
            .onSuccess { _recording.value = true }
            .onFailure { session.resume() }
    }

    fun stopTalking() {
        if (!recorder.isRecording) return
        _recording.value = false
        val bytes = recorder.stop()
        if (bytes != null) session.askAudio(bytes, "utterance.m4a", "audio/mp4") else session.resume()
    }

    override fun onCleared() {
        recorder.cancel()
        super.onCleared()
    }
}
