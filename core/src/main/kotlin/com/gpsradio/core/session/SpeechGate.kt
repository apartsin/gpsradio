package com.gpsradio.core.session

import kotlin.math.sqrt

/**
 * On-device speech gate for always-listening mode (spec A §33): the mic is open all the time, but audio
 * is only sent to the voice model while the listener is actually talking.
 *
 * - Opens after [onsetMs] of speech-level energy, and then sends the last [prerollMs] too, so the first
 *   word isn't clipped.
 * - While the radio or the host is audible, the listener must be clearly louder than the playback
 *   ([loudRms]) to open it: the phone must not "hear itself".
 * - Stays open through short pauses and closes after [hangoverMs] of quiet, sending that trailing quiet so
 *   the server's turn detection sees the end of the turn.
 *
 * Saves mobile data (streaming the mic nonstop is ~170 MB/h) and stops the model reacting to the radio.
 * Chunks are 16-bit little-endian mono PCM.
 */
class SpeechGate(
    private val sampleRate: Int = 24_000,
    private val prerollMs: Long = 500,
    private val onsetMs: Long = 120,
    private val hangoverMs: Long = 1_500,
    private val quietRms: Double = 700.0,
    private val loudRms: Double = 2_500.0,
) {
    private val preroll = ArrayDeque<ByteArray>()
    private var prerollBytes = 0L
    private var voicedMs = 0L
    private var quietMs = 0L

    var isOpen: Boolean = false
        private set

    private fun ms(bytes: Int): Long = bytes * 1000L / (sampleRate * 2)

    /** Feed one captured chunk; returns what to send now (possibly nothing, or preroll + this chunk). */
    fun process(chunk: ByteArray, playbackAudible: Boolean): List<ByteArray> {
        val level = rms(chunk)
        val threshold = if (playbackAudible) loudRms else quietRms
        val voiced = level >= threshold
        val len = ms(chunk.size)
        if (isOpen) {
            quietMs = if (voiced) 0 else quietMs + len
            if (quietMs >= hangoverMs) {
                isOpen = false
                quietMs = 0
                voicedMs = 0
            }
            return listOf(chunk)
        }
        voicedMs = if (voiced) voicedMs + len else 0
        if (voicedMs >= onsetMs) {
            isOpen = true
            quietMs = 0
            val out = preroll.toMutableList().apply { add(chunk) }
            preroll.clear()
            prerollBytes = 0
            return out
        }
        preroll.addLast(chunk)
        prerollBytes += chunk.size
        while (preroll.size > 1 && ms(prerollBytes.toInt()) > prerollMs) prerollBytes -= preroll.removeFirst().size
        return emptyList()
    }

    /** Forget partial state (e.g. after the mic was switched off). */
    fun reset() {
        preroll.clear()
        prerollBytes = 0
        voicedMs = 0
        quietMs = 0
        isOpen = false
    }

    companion object {
        fun rms(pcm: ByteArray): Double {
            val n = pcm.size / 2
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
            }
            return sqrt(sum / n)
        }
    }
}
