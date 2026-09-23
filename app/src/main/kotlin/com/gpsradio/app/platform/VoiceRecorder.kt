package com.gpsradio.app.platform

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Push-to-talk capture to AAC/M4A, which the transcription API accepts directly. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start() {
        if (recorder != null) return
        val out = File(context.cacheDir, "utterance.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(48_000)
        r.setOutputFile(out.path)
        r.prepare()
        r.start()
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
    }

    /** Returns the recording, or null if it was too short to contain speech. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file ?: return null
        val bytes = if (ok && System.currentTimeMillis() - startedAt > 400) f.readBytes() else null
        f.delete()
        return bytes
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
    }
}
