package com.gpsradio.core.session

import kotlin.math.sqrt

/**
 * On-device speech gate for always-listening mode (spec A §33): the mic is open all the time, but audio
 * is only sent to the voice model while the listener is actually talking.
 *
 * - Opens after [onsetMs] of speech-level energy, and then sends the last [prerollMs] too, so the first
 *   word isn't clipped.
 * - While the radio or the host is audible, the listener must be clearly louder than what the mic picks up
 *   of the playback: the phone must not "hear itself". That echo level is measured while the listener is
 *   quiet (spec A §62), so at a normal volume a normal voice gets through at once; [loudRms] is the ceiling
 *   (and the start value until the echo has been measured).
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
    /** What the mic picks up of the playback while the listener is quiet (moving average), and for how long. */
    private var echoRms = 0.0
    private var echoMs = 0L

    var isOpen: Boolean = false
        private set

    private fun ms(bytes: Int): Long = bytes * 1000L / (sampleRate * 2)

    /** Feed one captured chunk; returns what to send now (possibly nothing, or preroll + this chunk). */
    fun process(chunk: ByteArray, playbackAudible: Boolean): List<ByteArray> {
        val level = rms(chunk)
        val len = ms(chunk.size)
        val threshold = if (playbackAudible) playbackThreshold() else quietRms
        val voiced = level >= threshold
        // Learn the echo only from quiet moments (the listener's own voice must not raise it).
        if (playbackAudible && !voiced && !isOpen) {
            echoRms = if (echoMs == 0L) level else echoRms * 0.9 + level * 0.1
            echoMs += len
        }
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

    /**
     * During playback: clearly above the measured echo ([ECHO_MARGIN] times), never below a real voice level
     * and never above [loudRms]; until the echo is known, [loudRms].
     */
    fun playbackThreshold(): Double =
        if (echoMs < ECHO_LEARN_MS) loudRms else (echoRms * ECHO_MARGIN).coerceIn(quietRms * 1.3, loudRms)

    /** Forget partial state (e.g. after the mic was switched off). */
    fun reset() {
        preroll.clear()
        prerollBytes = 0
        voicedMs = 0
        quietMs = 0
        isOpen = false
    }

    companion object {
        /** How much louder than the echo the listener must be. */
        const val ECHO_MARGIN = 2.5
        /** Echo measured for this long before it's trusted. */
        const val ECHO_LEARN_MS = 600L

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
