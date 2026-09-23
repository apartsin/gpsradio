package com.gpsradio.core.model

import kotlinx.serialization.Serializable

enum class TravelMode { STATIONARY, WALKING, DRIVING, UNKNOWN }

/** Radio state per spec B §4/§9. IDLE means no session is running. */
enum class RadioState { IDLE, RADIO, RESEARCHING, NARRATING, CONVERSING, PAUSED }

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

/** Raw device fix (spec B §5.1). */
data class LocationSample(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val timestampMs: Long,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String = "fused",
)

/** Accepted, smoothed location with derived movement state. */
data class LocationContext(
    val point: GeoPoint,
    val accuracyM: Float,
    val timestampMs: Long,
    val speedMps: Double,
    val headingDeg: Double?,
    val travelMode: TravelMode,
)

/** Approximate place name for the user's area, used to localize web search. */
data class AreaLabel(
    val city: String? = null,
    val region: String? = null,
    val countryCode: String? = null,
)

enum class Topic(val key: String) {
    HISTORY("history"),
    LEGENDS("legends"),
    ARCHITECTURE("architecture"),
    NATURE("nature"),
    CULTURE("culture"),
    INDUSTRY("industry"),
    WAR("war"),
    FOOD("food"),
    UNUSUAL("unusual"),
    ATTRACTIONS("attractions");

    companion object {
        fun fromKey(key: String): Topic? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

enum class ResearchStatus { UNRESEARCHED, RESEARCHING, READY, FAILED }

/** A nearby entity from a places/geographic source (spec B §5.2). */
data class PlaceCandidate(
    val id: String,
    val name: String,
    val category: String,
    val point: GeoPoint,
    val source: String,
    val sourceConfidence: Double,
    /** Intrinsic interest 0..1, estimated from the amount/quality of available material. */
    val baseRelevance: Double,
    val topics: Set<Topic>,
    val description: String? = null,
    val extract: String? = null,
    val url: String? = null,
    val wikidataId: String? = null,
    val researchStatus: ResearchStatus = ResearchStatus.UNRESEARCHED,
)

data class ScoreBreakdown(
    val relevance: Double,
    val interest: Double,
    val novelty: Double,
    val proximity: Double,
    val direction: Double,
    val sourceQuality: Double,
    val repetition: Double,
    val conversationCost: Double,
)

/** A candidate scored for airtime relative to the current user position (spec B §5.3/§8). */
data class RankedCandidate(
    val place: PlaceCandidate,
    val distanceM: Double,
    val bearingDeg: Double,
    val score: Double,
    val breakdown: ScoreBreakdown,
) {
    val storyId: String get() = place.id
}

data class SourceRef(val title: String, val url: String)

enum class Speaker { RADIO, USER, SYSTEM }

data class TranscriptEntry(
    val speaker: Speaker,
    val text: String,
    val timestampMs: Long,
    val entityId: String? = null,
    val sources: List<SourceRef> = emptyList(),
)
