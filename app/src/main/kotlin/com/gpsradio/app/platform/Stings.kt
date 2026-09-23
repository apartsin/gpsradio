package com.gpsradio.app.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.gpsradio.core.session.Sting
import com.gpsradio.core.session.StingPlayer
import com.gpsradio.core.session.StingSynth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.EnumMap

/**
 * Plays the radio's earcons. The PCM is synthesized in code ([StingSynth], no asset files) and
 * played through a small static-mode [AudioTrack]; cancelling the coroutine stops it at once.
 * Whether stings play at all is decided by the session ("Sound effects" setting).
 */
class Stings : StingPlayer {
    private val rate = StingSynth.SAMPLE_RATE
    private val cache = EnumMap<Sting, ShortArray>(Sting::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun pcm(sting: Sting): ShortArray = synchronized(cache) { cache.getOrPut(sting) { StingSynth.pcm(sting, rate) } }

    override suspend fun play(sting: Sting) {
        val samples = withContext(Dispatchers.Default) { pcm(sting) }
        val track = withContext(Dispatchers.Default) { build(samples) } ?: return
        try {
            track.play()
            delay(samples.size * 1000L / rate + 30)
        } finally {
            withContext(NonCancellable) {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    /** A static track holding the whole sting; null when the device refuses to create one. */
    private fun build(samples: ShortArray): AudioTrack? = runCatching {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (track.state == AudioTrack.STATE_UNINITIALIZED) {
            track.release()
            return@runCatching null
        }
        // Static mode: the data must be written before play().
        if (track.write(samples, 0, samples.size) < samples.size) {
            track.release()
            return@runCatching null
        }
        track
    }.getOrNull()
}
