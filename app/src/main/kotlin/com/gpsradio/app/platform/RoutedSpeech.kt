package com.gpsradio.app.platform

import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.session.SpeechService

/**
 * The voice provider chosen in Settings (spec A §70): the OpenAI voice, or the phone's own when [usePhone].
 * OpenAI failures are not hidden here: the session sees them and falls back to the phone (and says why).
 */
class RoutedSpeech(
    private val cloud: SpeechService,
    private val phone: SpeechService,
    private val usePhone: () -> Boolean,
) : SpeechService {
    override suspend fun synthesize(text: String, language: String, style: HostStyle): ByteArray =
        if (usePhone()) phone.synthesize(text, language, style) else cloud.synthesize(text, language, style)

    override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?): String =
        cloud.transcribe(audio, fileName, mimeType, prompt)
}
