package com.gpsradio.core.model

import kotlinx.serialization.Serializable

enum class TravelMode { STATIONARY, WALKING, CYCLING, DRIVING, UNKNOWN }

/**
 * Activity reported by the platform's activity recognition (Android Activity Recognition
 * Transition API). Used as a prior that biases speed-based travel-mode detection (spec B §24).
 */
enum class ActivityType { IN_VEHICLE, ON_BICYCLE, WALKING, RUNNING, STILL }

/** Road-trip category of a candidate while driving (spec A §16, PR-27). */
enum class RoadTripKind {
    /** A landmark you can see from the road: peak, lake, castle, tower, bridge, lighthouse, viewpoint. */
    VISIBLE,
    /** A high-relevance sight close to the route: the story ends with an offer to navigate there. */
    WORTH_A_STOP,
}

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
    /** True while speed or heading changes sharply (junction, roundabout, braking); see ManeuverDetector. */
    val maneuvering: Boolean = false,
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

/** A nearby entity from a places/geographic source (spec B §5.2). Serializable for the offline area cache. */
@Serializable
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
    /** Real photo of the place (Wikipedia/Wikimedia Commons), when one exists. */
    val imageUrl: String? = null,
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
    /** Road-trip bonus while driving (visible from the road / worth a stop), 0..1. */
    val roadTrip: Double = 0.0,
)

/** A candidate scored for airtime relative to the current user position (spec B §5.3/§8). */
data class RankedCandidate(
    val place: PlaceCandidate,
    val distanceM: Double,
    val bearingDeg: Double,
    val score: Double,
    val breakdown: ScoreBreakdown,
    /** Set while driving when the place is visible from the road or worth a stop. */
    val roadTrip: RoadTripKind? = null,
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
