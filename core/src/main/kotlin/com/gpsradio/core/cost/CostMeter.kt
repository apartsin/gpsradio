package com.gpsradio.core.cost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Estimated OpenAI spend (spec A §41): every call reports its usage here, priced at list prices. An estimate,
 * not a bill (prices change; the dashboard is the truth), but close enough for a meter and a daily cap.
 */
class CostMeter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val prices: Prices = Prices(),
    /** Called after each change (throttling is up to the caller) to persist [serialize]. */
    private val onChange: (String) -> Unit = {},
) {
    /** USD per 1M tokens, per call or per character. */
    data class ModelPrice(val input: Double, val cachedInput: Double, val output: Double)

    data class Prices(
        val text: Map<String, ModelPrice> = mapOf(
            "gpt-4.1" to ModelPrice(2.00, 0.50, 8.00),
            "gpt-4.1-mini" to ModelPrice(0.40, 0.10, 1.60),
            "gpt-4.1-nano" to ModelPrice(0.10, 0.025, 0.40),
            "gpt-4o" to ModelPrice(2.50, 1.25, 10.00),
            "gpt-4o-mini" to ModelPrice(0.15, 0.075, 0.60),
        ),
        /** Unknown text models are priced like the full model (never under-estimate). */
        val fallback: ModelPrice = ModelPrice(2.00, 0.50, 8.00),
        val webSearchCall: Double = 0.025,
        /** gpt-4o-mini-tts: about $0.015 a minute of speech, ~900 characters. */
        val ttsPerChar: Double = 0.015 / 900,
        /** Speech-to-text, per request (a short utterance). */
        val transcription: Double = 0.001,
        // gpt-realtime, per 1M tokens.
        val realtimeTextIn: Double = 4.00,
        val realtimeCachedIn: Double = 0.40,
        val realtimeAudioIn: Double = 32.00,
        val realtimeTextOut: Double = 16.00,
        val realtimeAudioOut: Double = 64.00,
    ) {
        fun of(model: String): ModelPrice = text[model] ?: text.entries.firstOrNull { model.startsWith(it.key + "-20") }?.value ?: fallback
    }

    enum class Kind(val label: String) { STORIES("stories & answers"), RESEARCH("web research"), VOICE("story voice"), LIVE("live conversation") }

    @Serializable
    private data class Snapshot(val days: Map<String, Map<String, Double>> = emptyMap())

    /** What the meter shows: today and this session, by kind. */
    data class Totals(val today: Double = 0.0, val session: Double = 0.0, val todayByKind: Map<Kind, Double> = emptyMap())

    private val days = LinkedHashMap<String, MutableMap<Kind, Double>>()
    private var session = 0.0
    private val _totals = MutableStateFlow(Totals())
    val totals: StateFlow<Totals> = _totals.asStateFlow()

    private fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toString()

    @Synchronized
    fun add(kind: Kind, usd: Double) {
        if (usd <= 0.0) return
        val day = days.getOrPut(today()) { mutableMapOf() }
        day[kind] = (day[kind] ?: 0.0) + usd
        session += usd
        publish()
        onChange(serialize())
    }

    /** A Responses API call: tokens by the model's price, plus web searches. */
    fun recordResponse(model: String, inputTokens: Int, cachedTokens: Int, outputTokens: Int, webSearches: Int, kind: Kind) {
        val p = prices.of(model)
        val uncached = (inputTokens - cachedTokens).coerceAtLeast(0)
        val usd = (uncached * p.input + cachedTokens * p.cachedInput + outputTokens * p.output) / 1e6 + webSearches * prices.webSearchCall
        add(kind, usd)
    }

    fun recordSpeech(chars: Int) = add(Kind.VOICE, chars * prices.ttsPerChar)

    fun recordTranscription() = add(Kind.LIVE, prices.transcription)

    /** The `usage` object of a Realtime `response.done` event. */
    fun recordRealtime(usage: JsonObject) {
        fun num(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val inD = usage["input_token_details"] as? JsonObject
        val outD = usage["output_token_details"] as? JsonObject
        val cached = num(inD, "cached_tokens")
        val textIn = (num(inD, "text_tokens") - cached).coerceAtLeast(0.0)
        val usd = (textIn * prices.realtimeTextIn + cached * prices.realtimeCachedIn + num(inD, "audio_tokens") * prices.realtimeAudioIn +
            num(outD, "text_tokens") * prices.realtimeTextOut + num(outD, "audio_tokens") * prices.realtimeAudioOut) / 1e6
        add(Kind.LIVE, usd)
    }

    fun todayUsd(): Double = synchronized(this) { days[today()]?.values?.sum() ?: 0.0 }

    fun serialize(): String = synchronized(this) {
        json.encodeToString(Snapshot.serializer(), Snapshot(days.mapValues { (_, m) -> m.mapKeys { it.key.name } }))
    }

    /** Keeps the last 31 days. */
    fun restore(serialized: String?) = synchronized(this) {
        if (serialized.isNullOrBlank()) return@synchronized
        val snap = runCatching { json.decodeFromString(Snapshot.serializer(), serialized) }.getOrNull() ?: return@synchronized
        snap.days.entries.sortedBy { it.key }.takeLast(31).forEach { (d, m) ->
            days[d] = m.mapNotNull { (k, v) -> runCatching { Kind.valueOf(k) }.getOrNull()?.let { it to v } }.toMap().toMutableMap()
        }
        publish()
    }

    private fun publish() {
        val t = days[today()].orEmpty()
        _totals.value = Totals(t.values.sum(), session, t.toMap())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
