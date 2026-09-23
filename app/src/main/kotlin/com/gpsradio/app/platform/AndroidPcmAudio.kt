package com.gpsradio.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
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
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null

    private val queue = LinkedBlockingQueue<ByteArray>()
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
        rec.startRecording()
        thread(name = "live-mic", isDaemon = true) {
            val buf = ByteArray(chunkBytes)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf.copyOf(n))
            }
        }
        return true
    }

    override fun stopCapture() {
        if (!capturing) return
        capturing = false
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        echo?.release()
        noise?.release()
        echo = null
        noise = null
    }

    override fun play(pcm: ByteArray) {
        ensurePlayer()
        queue.offer(pcm)
    }

    override fun stopPlayback() {
        queue.clear()
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
                    val chunk = queue.take()
                    t.write(chunk, 0, chunk.size)
                }
            } catch (_: InterruptedException) {
                // released
            }
        }
    }

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
