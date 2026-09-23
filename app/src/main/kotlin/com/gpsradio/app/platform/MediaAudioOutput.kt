package com.gpsradio.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.gpsradio.core.session.AudioOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plays one MP3 clip with transient audio focus (ducking music/navigation), and stops
 * immediately when the coroutine is cancelled (skip, barge-in, pause).
 */
class MediaAudioOutput(
    private val context: Context,
    /** Called when another app takes audio focus, e.g. a phone call. */
    private val onFocusLost: () -> Unit,
) : AudioOutput {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override suspend fun play(audio: ByteArray) {
        if (audio.isEmpty()) return
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("segment", ".mp3", context.cacheDir).apply { writeBytes(audio) }
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onFocusLost()
            }
            .build()
        var player: MediaPlayer? = null
        try {
            withContext(Dispatchers.Main) {
                if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    // E.g. during a phone call: report as transient so the session backs off and retries.
                    throw java.io.IOException("Audio is busy (another app has audio focus)")
                }
                suspendCancellableCoroutine { cont ->
                    val p = MediaPlayer()
                    player = p
                    p.setAudioAttributes(attributes)
                    p.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                    p.setOnErrorListener { _, what, extra ->
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Playback error $what/$extra"))
                        true
                    }
                    try {
                        p.setDataSource(file.path)
                        p.prepare()
                        p.start()
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                player?.let { p -> runCatching { if (p.isPlaying) p.stop() }; p.release() }
                audioManager.abandonAudioFocusRequest(focus)
            }
            file.delete()
        }
    }
}
