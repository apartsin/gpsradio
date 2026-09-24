package com.gpsradio.core

import com.gpsradio.core.session.SpeechGate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec A §62: over the radio, a normal voice gets through once the echo level is known. */
class SpeechGateEchoTest {
    private fun chunk(amplitude: Int, ms: Int = 20): ByteArray {
        val n = 24 * ms
        return ByteArray(n * 2).also { b ->
            for (i in 0 until n) {
                val v = if (i % 2 == 0) amplitude else -amplitude
                b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
    }

    private fun speaks(gate: SpeechGate, amplitude: Int): Boolean {
        repeat(15) { gate.process(chunk(amplitude), playbackAudible = true) } // 300 ms of speech
        return gate.isOpen
    }

    @Test
    fun aQuietRadioLetsANormalVoiceThrough() {
        val gate = SpeechGate()
        repeat(50) { gate.process(chunk(300), playbackAudible = true) } // 1 s of faint echo
        assertTrue(gate.playbackThreshold() < 2_500.0)
        assertTrue(speaks(gate, 1_200), "a normal voice over a quiet radio")
    }

    @Test
    fun aLoudRadioStillNeedsAClearlyLouderVoice() {
        val gate = SpeechGate()
        repeat(50) { gate.process(chunk(1_000), playbackAudible = true) } // loud echo
        assertFalse(speaks(gate, 1_500), "the radio's own sound must not open it")
    }

    @Test
    fun beforeTheEchoIsKnownTheCeilingApplies() {
        val gate = SpeechGate()
        assertFalse(speaks(gate, 1_200))
    }
}
