# Interactive Location-Based Radio

System Design, Architecture & Internal Interface Specification

Working specification • Version 0.1 • 23 September 2026

## 1. Design Objectives

The architecture separates sensing, geographic discovery, factual research, editorial selection, conversational reasoning, and audio delivery. This separation prevents the voice model from being responsible for discovering every nearby fact in real time, reduces latency and cost, and makes grounding and repetition control explicit.

## 2. Logical Architecture

Android Client → Location/Movement Manager → Session Orchestrator → Area Discovery & Content Cache → Editorial Ranker → Narration/Conversation Agent → Audio. External services provide places/geographic data, web/research sources, routing, and OpenAI model capabilities.

| Component | Responsibility |
|---|---|
| Android Client | Permissions, location sensing, audio capture/playback, UI, foreground/background lifecycle, local cache. |
| Location Manager | Normalize GPS updates; derive speed, heading, movement mode, and meaningful location changes. |
| Session Orchestrator | Own session state, coordinate services, determine when to refresh area context, route user events. |
| Discovery Service | Find nearby POIs/features and create structured candidates from geographic/places sources. |
| Research Service | Enrich candidates with facts, history, source references, confidence, and freshness metadata. |
| Content Cache | Store area packages and enriched candidate records to avoid repeated research. |
| Editorial Ranker | Score candidates and decide whether/when a story deserves airtime. |
| Conversation Agent | Generate narration, answer follow-ups, call tools, maintain conversational references. |
| Audio/Realtime Layer | Speech input/output, interruption/barge-in, turn detection, playback state. |
| Routing Service | Distance, ETA, route/direction information for selected destinations. |
| Profile/History Store | Interests, session history, heard-story fingerprints, optional long-term preferences. |
| Telemetry | Latency, failures, cost, source usage, story selection, skips/follow-ups, privacy-safe diagnostics. |

## 3. Deployment Architecture

Recommended MVP deployment:

- Native Android client in Kotlin/Jetpack Compose.

- Android Fused Location Provider for device location.

- Small backend API (Node.js/TypeScript or Python/FastAPI).

- Backend owns permanent API credentials and privileged tool access.

- OpenAI Responses/agent workflow for discovery/research and narration; OpenAI Realtime can be introduced for low-latency full-duplex voice.

- External places/geographic provider for deterministic nearby entities.

- Optional routing provider for travel times and directions.

- Persistent relational/document store plus a short-lived cache.

## 4. Core Runtime State

The orchestrator maintains a compact state object. A representative internal model is:

| Field | Type | Meaning |
|---|---|---|
| session_id | UUID | Current listening session. |
| location | GeoPoint | Latest accepted latitude/longitude plus accuracy and timestamp. |
| speed_mps | float | Smoothed speed. |
| heading_deg | float? | Direction of movement when reliable. |
| travel_mode | enum | stationary \| walking \| driving \| unknown. |
| active_entity_id | string? | Place/entity currently being narrated or discussed. |
| active_topic | string? | Current conversational subject. |
| radio_state | enum | playing \| paused \| conversing \| researching \| silent. |
| heard_story_ids | set | Story fingerprints already delivered. |
| recent_entity_ids | list | Recently mentioned places. |
| interest_profile | map<string,float> | Topic weights used by editorial ranking. |
| candidate_queue | list<Candidate> | Ranked nearby material ready for possible narration. |
| conversation_ref | string? | Reference to current model conversation/session. |

## 5. Data Models

### 5.1 LocationSample

| Field | Type |
|---|---|
| lat / lon | double |
| accuracy_m | float |
| timestamp | instant |
| speed_mps | float? |
| bearing_deg | float? |
| provider | string |

### 5.2 PlaceCandidate

| Field | Type / notes |
|---|---|
| id | stable provider/internal ID |
| name | string |
| category | enum/string |
| coordinates | GeoPoint |
| distance_m | computed |
| bearing_from_user | degrees |
| source | provider |
| source_confidence | 0..1 |
| metadata | opening/category/basic descriptive data |
| research_status | unresearched \| researching \| ready \| failed |

### 5.3 StoryCandidate

| Field | Type / notes |
|---|---|
| story_id | stable hash/fingerprint |
| entity_id | related PlaceCandidate |
| title | short internal label |
| topic_tags | history/nature/etc. |
| facts | structured factual claims |
| sources | source URLs/IDs and timestamps |
| confidence | 0..1 |
| novelty | 0..1 |
| editorial_score | computed |
| estimated_duration_sec | integer |
| freshness_requirement | static \| periodic \| live |

## 6. Location Processing

- Reject stale or grossly inaccurate samples according to configurable thresholds.

- Smooth speed and bearing to prevent mode oscillation.

- Derive travel mode from speed plus hysteresis; allow user override.

- Trigger area refresh by meaningful displacement, not every GPS sample.

- Example initial thresholds: stationary refresh 300–500 m; walking 300–800 m; driving 2–5 km look-ahead, all configurable.

- Increase location interval when stationary or screen/background conditions permit to reduce battery consumption.

## 7. Discovery and Research Pipeline

- Location Manager emits a meaningful location-context update.

- Orchestrator computes search radius/look-ahead based on travel mode.

- Discovery Service queries geographic/places data and normalizes returned entities.

- Previously known entities are merged from cache; new/high-potential entities enter enrichment.

- Research Service retrieves factual/historical material only for candidates likely to matter.

- Claims are stored with provenance, confidence, and freshness class.

- Editorial Ranker creates or updates StoryCandidates and candidate queue.

- Narration Agent receives only the selected grounded context rather than an unconstrained request to invent nearby facts.

## 8. Editorial Ranking Specification

An initial transparent scoring model is preferable to a fully learned policy:

score = wR·relevance + wI·interest + wN·novelty + wP·proximity + wD·direction + wQ·source_quality − wX·repetition − wC·conversation_cost

- Relevance: intrinsic importance/interest of the story.

- Interest: match to explicit and learned user topic weights.

- Novelty: difference from stories already heard during the session/trip.

- Proximity: distance transformed by travel mode.

- Direction: bonus for objects ahead or along the current route.

- Source quality: confidence and evidence quality.

- Repetition: entity/story/semantic similarity penalty.

- Conversation cost: suppress interruption when the user is already engaged or a recent segment just ended.

A separate speak threshold controls whether the highest-ranked candidate actually receives airtime. If nothing crosses the threshold, the correct action is silence.

## 9. Conversation State Machine

| State | Event | Next state / action |
|---|---|---|
| SILENT/RADIO | candidate selected | NARRATING: synthesize/play segment. |
| NARRATING | user begins speaking | CONVERSING: stop/duck playback and capture utterance. |
| CONVERSING | follow-up | CONVERSING: answer using active entity/topic and tools as required. |
| CONVERSING | continue/resume | RADIO: restore candidate scheduler. |
| NARRATING | skip | RADIO/SILENT: mark story skipped; select next candidate only if warranted. |
| Any | pause | PAUSED: stop proactive output. |
| PAUSED | resume | RADIO: refresh location/context if stale. |

## 10. Internal Tool Interfaces

| Tool | Input | Output |
|---|---|---|
| get_current_location | none/session | LocationContext |
| find_nearby_places | lat, lon, radius, categories, limit | PlaceCandidate[] |
| research_place | place_id, topics, depth | ResearchBundle |
| search_local_history | area/entity, query, date/theme filters | SourcedFact[] |
| get_route | origin, destination, mode | RouteSummary |
| get_session_context | session_id | SessionContext |
| mark_story_heard | story_id, outcome | ack |
| update_interest_signal | topic/entity, signal type | updated weights |

## 11. Client ↔ Backend API

| Endpoint | Method | Purpose |
|---|---|---|
| /v1/session | POST | Create session; return session ID and initial configuration. |
| /v1/session/{id}/location | POST | Send meaningful location context update. |
| /v1/session/{id}/nearby | GET | Optional UI request for current ranked nearby items. |
| /v1/session/{id}/event | POST | skip, pause, resume, repeat, selected-POI and other control events. |
| /v1/session/{id}/ask | POST | Text/transcribed user utterance when not using direct Realtime transport. |
| /v1/session/{id}/audio-token | POST | Issue short-lived credentials/session parameters for Realtime voice if used. |
| /v1/session/{id} | DELETE | Terminate session and release ephemeral state. |

All requests use TLS. Permanent OpenAI/provider API keys remain server-side. Client authentication should use application/user tokens separate from provider credentials.

## 12. Example API Payloads

Location update:

{"lat":47.61,"lon":13.78,"accuracy_m":8,"speed_mps":1.3,"bearing_deg":275,"timestamp":"2026-09-23T10:00:00Z"}

Selected narration context passed to the model should be constrained and structured: entity identity, current distance/direction, verified facts, source metadata, already-mentioned facts, user interests, target duration, and current conversational references.

## 13. Model Responsibilities and Boundaries

| Model should | Model should not |
|---|---|
| Turn grounded structured facts into natural narration. | Invent nearby POIs from coordinates alone when deterministic discovery is available. |
| Select wording and conversational depth. | Treat model memory as authoritative for current opening hours or live conditions. |
| Resolve conversational references. | Persist raw location indefinitely by default. |
| Call research/routing tools when needed. | Present folklore or disputed claims as established fact. |
| Decide whether additional research is required. | Control safety-critical driving/navigation behavior. |

## 14. Voice Layer

MVP may use speech-to-text plus generated speech as separate stages. A later/full experience should use a low-latency realtime voice session supporting barge-in. The Android client owns microphone permissions, audio focus, Bluetooth/headset routing, interruption handling, and foreground-service behavior. The backend issues only short-lived voice-session credentials to the device.

## 15. Caching Strategy

- Area cache keyed by coarse geospatial cell plus language and research version.

- Place cache keyed by provider ID.

- Research bundle cache with freshness policy based on claim type.

- Story fingerprint cache to suppress semantic repetition.

- Session cache for active topic/entity and candidate queue.

- Static history may have long TTL; opening hours, events, weather-like facts and availability require short TTL or live retrieval.

## 16. Privacy and Security

- Request foreground/background location only when required by the selected operating mode.

- Transmit the minimum precision required for the current function.

- Do not embed permanent provider keys in the APK.

- Encrypt transport and protect stored user/profile data.

- Define retention separately for raw GPS, derived trip history, content history, and preference signals.

- Provide explicit controls for deleting history and disabling personalization.

- Do not expose exact location in logs; quantize or redact diagnostics where possible.

## 17. Failure and Degradation Behavior

| Failure | Behavior |
|---|---|
| GPS unavailable | Use last known location with age warning internally; avoid precise proximity claims. |
| Places provider failure | Use cached area data; otherwise remain silent rather than invent nearby entities. |
| Research/web failure | Use previously verified cached facts; avoid uncertain new historical claims. |
| OpenAI failure | Stop generated narration; preserve app controls and retry with backoff. |
| Audio interruption/call | Yield Android audio focus; pause narration; resume only according to user/system policy. |
| Network loss | Use cached content if available; clearly limit interactive factual queries requiring online retrieval. |

## 18. Observability

- End-to-end time from user speech end to first response audio.

- Time from location trigger to candidate availability.

- Research/cache hit ratio and external request cost.

- Narration completion, skip, repeat, and follow-up rates.

- Duplicate-story rate.

- Tool/model error rate.

- Grounding/source coverage for factual segments.

- Battery/location-update metrics on device.

- No raw transcript or exact-location telemetry unless explicitly permitted by product privacy policy.

## 19. MVP Implementation Sequence

- Android location + simple UI + backend session.

- Nearby POI discovery and normalized PlaceCandidate model.

- Grounded text narration and on-screen transcript.

- Text-to-speech playback and radio scheduler.

- Push-to-talk follow-up conversation.

- Story/history cache and repetition suppression.

- Interest weighting and skip/follow-up signals.

- Realtime voice/barge-in.

- Driving-specific audio focus, look-ahead ranking, and route-aware discovery.

## 20. Initial Acceptance Tests

| Test | Expected result |
|---|---|
| Stationary start in populated area | Within a reasonable startup interval, app can identify grounded nearby candidates and narrate one if above threshold. |
| User asks 'tell me more' | Response continues the active entity/topic without requiring restatement. |
| User moves several km | Old candidates decay; new area discovery occurs; no stale proximity claims. |
| User says 'skip' | Playback stops promptly and the same story is not immediately replayed. |
| No worthwhile candidates | System remains silent rather than producing generic filler. |
| Historical claim has weak evidence | Narration qualifies uncertainty or omits the claim. |
| Repeated route segment | Previously heard stories are suppressed unless user requests them. |
| Network outage | Cached behavior continues where possible and unsupported queries fail gracefully. |

## 21. Language Architecture and Interfaces

Language is an explicit part of profile and session state. Research-language selection is decoupled from presentation language so locally authoritative sources can be used regardless of the language heard by the user.

| Field | Type | Meaning |
|---|---|---|
| preferred_language | BCP-47 tag | Persistent user default, e.g. en-US, ru-RU, he-IL, de-AT. |
| language_mode | auto \| explicit | Whether the default follows device/app locale or an explicit selection. |
| session_language | BCP-47 tag | Effective narration/conversation language for the current session. |
| source_languages | list<BCP-47/base tags> | Optional research-language hints; not a restriction on credible sources. |

### 21.1 Language Resolution

- At session creation, resolve session_language from explicit user selection; otherwise preferred_language; otherwise supported device/app locale; finally a configured product fallback.

- A spoken or UI request to change language updates session_language immediately.

- Only an explicit request to make the change permanent updates preferred_language or language_mode.

- Speech recognition and speech synthesis/realtime voice should use session_language where the service supports locale selection.

- The research service may formulate searches in multiple languages, especially the local language of the place, and return grounded facts for narration in session_language.

### 21.2 API Changes

| Interface | Addition | Behavior |
|---|---|---|
| POST /v1/session | language_mode, preferred_language/session_language | Initialize effective session language. |
| POST /v1/session/{id}/event | event=change_language, language=<BCP-47> | Change current session language. |
| User profile/settings | preferred_language, language_mode | Persist the user's default selection. |
| Research tool calls | output_language plus optional source-language hints | Search broadly but return normalized content for selected narration language. |

### 21.3 Multilingual Acceptance Tests

- User selects Russian as default while located in a German-speaking region: narration and conversation are in Russian while German-language sources may be used.

- User says 'continue in English': subsequent audio is English for the current session and the stored default remains unchanged.

- User explicitly chooses 'make English my default': the profile is updated and future sessions begin in English.

- Auto mode follows a supported device/app language and falls back predictably when the locale is unsupported.

- Place names and source facts remain semantically correct across translation and are not transformed into invented place names.
