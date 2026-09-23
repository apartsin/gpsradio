package com.gpsradio.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.gpsradio.core.ai.RealtimeProtocol
import com.gpsradio.core.session.PcmAudio
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Full-duplex PCM for the live voice: 24 kHz mono 16-bit capture (voice-communication source with
 * echo cancellation, so the host's own voice doesn't trigger an interruption) and streaming playback
 * on a dedicated thread that can be flushed instantly for barge-in.
 */
class AndroidPcmAudio(private val context: Context) : PcmAudio {
    private val rate = RealtimeProtocol.SAMPLE_RATE

    @Volatile private var capturing = false
    private var record: AudioRecord? = null
    /** The current capture's thread and stop flag: a quick stop/start never lets an old thread read a released recorder. */
    private var micThread: Thread? = null
    private var micRunning: java.util.concurrent.atomic.AtomicBoolean? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    @Volatile private var focus: AudioFocusRequest? = null

    /** Until when the speaker is (roughly) still playing host audio; used by the echo gate. */
    @Volatile private var playingUntilMs = 0L
    private var track: AudioTrack? = null
    private var player: Thread? = null

    @SuppressLint("MissingPermission")
    override fun startCapture(onChunk: (ByteArray) -> Unit): Boolean {
        if (capturing) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunkBytes = rate / 10 * 2 // 100 ms
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunkBytes * 4),
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        record = rec
        capturing = true
        gate.reset()
        rec.startRecording()
        // No audio focus for listening: an always-open mic must not duck or pause the radio. Focus is
        // taken only while the live host speaks (see play()).
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        micRunning = running
        micThread = thread(name = "live-mic", isDaemon = true) {
            val buf = ByteArray(chunkBytes)
            while (running.get()) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (_: IllegalStateException) {
                    break // recorder released
                }
                if (n < 0) break // ERROR_DEAD_OBJECT / ERROR_INVALID_OPERATION: the recorder is gone
                if (n == 0) continue
                // Speech gate: only speech is sent (with a short preroll); while the radio or the host is
                // audible, the listener must be clearly louder than the playback, so the phone never
                // answers itself. Also saves mobile data in always-listening mode.
                val playbackAudible = radioAudible || System.currentTimeMillis() < playingUntilMs
                gate.process(buf.copyOf(n), playbackAudible).forEach(onChunk)
            }
        }
        return true
    }

    override fun stopCapture() {
        if (!capturing) return
        capturing = false
        micRunning?.set(false)
        record?.let { runCatching { it.stop() } } // unblocks a pending read
        micThread?.let { t -> if (t !== Thread.currentThread()) runCatching { t.join(500) } }
        micThread = null
        micRunning = null
        record?.let { runCatching { it.release() } }
        record = null
        echo?.release()
        noise?.release()
        echo = null
        noise = null
        abandonFocus()
    }

    override fun setRadioAudible(audible: Boolean) {
        radioAudible = audible
    }

    override fun play(pcm: ByteArray) {
        ensurePlayer()
        if (focus == null) requestFocus()
        // 24 kHz mono 16-bit = 48 bytes per ms; extend the "host audible" window by this chunk.
        val now = System.currentTimeMillis()
        playingUntilMs = maxOf(playingUntilMs, now) + pcm.size / 48 + 250
        queue.offer(pcm)
    }

    override fun pendingPlaybackMs(): Long = (playingUntilMs - System.currentTimeMillis()).coerceAtLeast(0)

    override fun stopPlayback() {
        abandonFocus()
        queue.clear()
        playingUntilMs = 0
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
                t.play()
            }
        }
    }

    private fun ensurePlayer() {
        if (track != null) return
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, rate)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        player = thread(name = "live-speaker", isDaemon = true) {
            try {
                while (true) {
                    val chunk = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        // The host has finished speaking: give audio focus back so other apps' music resumes.
                        if (focus != null && System.currentTimeMillis() > playingUntilMs) abandonFocus()
                        continue
                    }
                    t.write(chunk, 0, chunk.size)
                }
            } catch (_: InterruptedException) {
                // released
            } catch (_: IllegalStateException) {
                // track released while writing
            }
        }
    }

    /** The live host holds audio focus right now (so a focus loss elsewhere in the app was caused by us). */
    val holdsFocus: Boolean get() = focus != null

    /** Ducks or pauses other audio (music, podcasts) while the conversation is open. */
    @Synchronized
    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .build()
        focus = req
        runCatching { audioManager.requestAudioFocus(req) }
    }

    @Synchronized
    private fun abandonFocus() {
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
    }

    private val gate = com.gpsradio.core.session.SpeechGate()
    @Volatile private var radioAudible = false

    /** Frees the speaker between conversations. */
    fun release() {
        stopCapture()
        queue.clear()
        player?.interrupt()
        player = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
