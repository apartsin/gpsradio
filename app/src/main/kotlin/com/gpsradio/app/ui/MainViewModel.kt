package com.gpsradio.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gpsradio.app.GpsRadioApp
import com.gpsradio.app.R
import com.gpsradio.app.data.AppSettings
import com.gpsradio.app.platform.UpdateState
import com.gpsradio.app.platform.VoiceRecorder
import com.gpsradio.core.update.UpdateInfo
import com.gpsradio.app.service.RadioService
import android.content.Intent
import android.net.Uri
import com.gpsradio.core.favorites.FavoritePlace
import com.gpsradio.core.favorites.ShareText
import com.gpsradio.core.journal.JournalEntry
import com.gpsradio.core.journal.JournalGpx
import com.gpsradio.core.journal.JournalText
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
    val update: StateFlow<UpdateState> = graph.updater.state
    val cost: StateFlow<com.gpsradio.core.cost.CostMeter.Totals> = graph.meter.totals

    /** Automatic checks are rate-limited (every 6 h); manual ones run now. */
    fun checkForUpdate(manual: Boolean, onStart: Boolean = false) = graph.updater.check(manual, onStart)

    /** The install needs the "install unknown apps" permission first. */
    fun canInstallUpdates(): Boolean = graph.updater.canInstall()

    fun installUpdate(info: UpdateInfo) = graph.updater.install(info)

    /** Opens the system page to allow installs from GPS Radio. */
    fun allowInstalls() {
        runCatching { getApplication<Application>().startActivity(graph.updater.permissionIntent()) }
    }

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        val before = graph.settings.current.effectiveApiKey
        graph.settings.update(transform)
        // A new key may have credit: stop warning and try OpenAI again right away.
        if (graph.settings.current.effectiveApiKey != before) session.onApiKeyChanged()
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

    /** Tap the mic in natural-voice mode: open or close the hands-free conversation. */
    fun toggleLive() {
        session.toggleLive()
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
        return Intent.createChooser(send, getApplication<Application>().getString(R.string.share_item, place.name))
    }

    /** Hands off to the user's maps app for directions. */
    fun navigateIntent(id: String): Intent? {
        val p = placeSnapshot(id) ?: return null
        val label = Uri.encode(p.name)
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.point.lat},${p.point.lon}?q=${p.point.lat},${p.point.lon}($label)"))
    }

    /** Walking mini-tour of about [minutes]. */
    fun startTour(minutes: Int) {
        session.startTour(minutes)
    }

    fun endTour() {
        session.endTour()
    }

    /** "Tell me again" from the journal. */
    fun retell(entry: JournalEntry) {
        session.retell(entry.placeId, entry.name)
    }

    /** Share one journal entry: name, first sentence and links. */
    fun journalShareIntent(entry: JournalEntry): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, JournalText.subject(entry))
            .putExtra(Intent.EXTRA_TEXT, JournalText.share(entry))
        return Intent.createChooser(send, getApplication<Application>().getString(R.string.share_item, entry.name))
    }

    /**
     * A day's journal as GPX, sent as text through the share sheet (no FileProvider needed: works with
     * any mail, chat, notes or cloud app; the subject carries the .gpx file name).
     */
    fun journalGpxIntent(day: String): Intent? {
        val entries = radio.value.journal.filter { it.day == day }
        if (entries.isEmpty()) return null
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, JournalGpx.fileName(day))
            .putExtra(Intent.EXTRA_TEXT, JournalGpx.build(entries, "GPS Radio journal $day"))
        return Intent.createChooser(send, getApplication<Application>().getString(R.string.export_day_gpx, day))
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
