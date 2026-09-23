package com.gpsradio.core.session

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Short radio earcons (spec A §18 #6). The app synthesizes them; tests use a fake. */
enum class Sting {
    /** ~0.6 s station ident before a story. */
    STATION,
    /** A soft two-note chime before an answer. */
    ANSWER,
    /** A subtle blip when the radio starts listening. */
    LISTENING,
}

/** Plays one sting; returns when it has finished. Cancelling the coroutine must stop it. */
fun interface StingPlayer {
    suspend fun play(sting: Sting)
}

/**
 * Generates the stings as 16-bit mono PCM in code (no asset files): decaying sine "bell" notes
 * with a soft attack and a fade-out, so there are no clicks. Pure, so it is unit-tested on the JVM.
 */
object StingSynth {
    const val SAMPLE_RATE = 24_000

    private data class Note(val startS: Double, val freqHz: Double, val gain: Double, val decayS: Double)

    private fun notes(sting: Sting): Pair<Double, List<Note>> = when (sting) {
        // A bright rising arpeggio (C5–E5–G5–C6), like a station ident.
        Sting.STATION -> 0.6 to listOf(
            Note(0.00, 523.25, 0.9, 0.16),
            Note(0.09, 659.25, 0.9, 0.16),
            Note(0.18, 783.99, 0.9, 0.18),
            Note(0.27, 1046.50, 1.0, 0.22),
        )
        // Two soft notes, a fourth apart (G5 then C6).
        Sting.ANSWER -> 0.45 to listOf(
            Note(0.00, 783.99, 0.8, 0.14),
            Note(0.16, 1046.50, 0.8, 0.18),
        )
        // One short, quiet blip.
        Sting.LISTENING -> 0.09 to listOf(Note(0.0, 1318.51, 0.55, 0.05))
    }

    /** Peak level relative to full scale (after normalizing): stings sit well below speech. */
    private const val LEVEL = 0.28

    fun durationMs(sting: Sting): Long = (notes(sting).first * 1000).roundToInt().toLong()

    fun pcm(sting: Sting, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val (duration, notes) = notes(sting)
        val n = (duration * sampleRate).roundToInt()
        val attack = 0.004 * sampleRate
        val fade = minOf(0.03 * sampleRate, n / 3.0)
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            var v = 0.0
            for (note in notes) {
                val nt = t - note.startS
                if (nt < 0) continue
                val env = minOf(1.0, nt * sampleRate / attack) * exp(-nt / note.decayS)
                // Fundamental plus a quiet octave for a bell-like timbre.
                v += note.gain * env * (0.8 * sin(2 * PI * note.freqHz * nt) + 0.2 * sin(4 * PI * note.freqHz * nt))
            }
            val tail = n - 1 - i
            if (tail < fade) v *= tail / fade
            raw[i] = v
        }
        // Normalize so overlapping notes never exceed the sting level.
        val peak = raw.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        val level = LEVEL * notes.maxOf { it.gain }.coerceAtMost(1.0)
        return ShortArray(n) { i -> (raw[i] / peak * level * Short.MAX_VALUE).roundToInt().toShort() }
    }
}
