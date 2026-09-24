# Interactive Location-Based Radio

System Design, Architecture & Internal Interface Specification

Working specification • Version 0.3 (living document) • 23 September 2026

> This Markdown file is the maintained spec. `source/B_System_Design_Architecture_Internal_Specification_v0.2.docx` is the original snapshot. Changes since v0.2: the MVP is app-only (§3 note, doc C), plus §22–§32 (memory, images and map, activity detection, latency plan, test strategy, implementation status, natural voice, prompts and host styles, offers and trip question, favourites and share, UI and distribution).

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

> **Superseded for the MVP:** the MVP runs app-only, with no backend and the user's own key on the device. See `C_Decision_App_Only_Architecture.md`. The layout below remains the target if a backend is introduced.

Recommended MVP deployment (original):

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

- Derive travel mode from speed plus hysteresis and activity recognition; always automatic (spec A §37), no user override.

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

## 22. Listener Memory

**Model.** `MemoryItem{id, category ∈ like|avoid|style|about_me, text, topic?, created}`, at most 40 items. A new item with the same meaning replaces the old one, and a like on a topic replaces an avoid on the same topic (and vice versa). Implemented in `core/memory/UserMemory.kt`.

**Capture.** The conversation call's structured output has two extra required fields. `remember[]` holds `{category, text, topic|null}` and `forget[]` holds strings. The instructions limit `remember` to durable preferences that are stated or clearly implied, not one-off requests. `forget` matches remembered items by normalized text.

**Use.**
1. `promptLines()` is sent as `listener_profile` in both the narration context and the conversation context.
2. `topicWeights()` overrides interest weights in the editorial ranker: like → 1.0, avoid → 0.05.

**Storage and control.** The profile is a JSON file in app-private storage (`FileMemoryStore`), excluded from cloud backup and device transfer. `RadioUiState.memory` exposes it to Settings, where each item can be forgotten or all can be cleared (`forgetMemory`, `clearMemory`).

## 23. Images and Map

**Photo sources, in priority order:**
1. The Wikipedia page thumbnail (`prop=pageimages&piprop=thumbnail&pithumbsize=640`), fetched in the same request as the extracts, so it costs no extra call.
2. The OpenStreetMap `image` tag, if it is an https URL.
3. The OpenStreetMap `wikimedia_commons=File:…` tag, via `Special:FilePath?width=640`.

The result is stored as `PlaceCandidate.imageUrl` and carried in `Segment.imageUrl` and `FocusPlace`.

**Why not generated images:** they would misrepresent real places. OpenAI web search also does not return image URLs through the API.

**UI.** `PlacePanel` combines an osmdroid `MapView` (OSM Mapnik tiles, no API key, tile cache in app-private storage, "© OpenStreetMap contributors" attribution) with a Coil `AsyncImage` card.
- Markers: the focus place, faded markers for nearby candidates, and a blue dot for the listener.
- The map recenters only when the focus changes, so the user can pan freely.
- **Focus** is set when a story starts or when the conversation reply names an `entity_id`.

**Future work:** the OSM tile usage policy suits light use only. At scale, switch to a commercial tile provider or MapLibre with a hosted style.

## 24. Activity Detection and Mode-Specific Discovery

**Today.** Travel mode comes from smoothed GPS speed with hysteresis:
- walking above 0.8 m/s, stationary below 0.35 m/s;
- driving above 6.5 m/s, back out of driving below 3 m/s;
- a new mode must persist for 15 s; a manual override is available.

Per-mode settings:

| Mode | Search radius | Look-ahead | Proximity scale | Minimum gap |
|---|---|---|---|---|
| Walking / stationary | 1.5 km | none | 400 m (walking), 600 m (stationary) | 45–60 s |
| Driving | 8 km | centre shifted 4 km along heading; things behind score 0 on direction | 3 km | 60 s |

**Planned.**
- **Activity recognition:** use the Activity Recognition Transition API (`IN_VEHICLE`, `ON_BICYCLE`, `WALKING`, `RUNNING`, `STILL`; needs the `ACTIVITY_RECOGNITION` permission) as a prior fused with speed. Add a **cycling** mode (3–4 km, ahead-weighted). When STILL, drop to passive or balanced location and slow the scheduler to save battery.
- **Driving corridor:** replace the single look-ahead circle with 2–3 cells along the heading, fetched ahead of time so they are cached before arrival. Categories get separate scores:
  - *visible from road*: `natural=peak|water`, `tourism=viewpoint`, castles, bridges, towers;
  - *worth a stop*: a high-relevance sight within about 5 min of detour, with a parking or visitor-attraction hint.

  A worth-a-stop segment ends with an offer; answering "take me there" goes through the existing NAVIGATE action.
- **Driving pacing:** at least 90 s between segments, at most 30 s per segment, and silence while speed changes sharply (junctions).

**Implemented (23 Sep 2026).**
- **Cycling mode:** 3.5 km radius, centre shifted ~1 km ahead, 1.2 km proximity scale, 60 s gap, ahead-weighted direction. Without a prior, 3.5–6.5 m/s from walking/rest reads as cycling (exit below 2.2 m/s, to driving above 9 m/s).
- **Activity prior:** `LocationProcessor.setActivity()` takes the latest transition (`ActivityType`); it resolves the ambiguous 3–7 m/s band (runner / cyclist / slow car), keeps a stopped car in driving, shortens the mode hold to 5 s when it agrees, and is ignored when speed clearly contradicts it or after 60 min. The app registers the Transition API in `RadioService` (`platform/ActivityTransitions.kt`, `ACTIVITY_RECOGNITION` asked with location) and forwards enters to `RadioSession.onActivity()`.
- **Corridor:** `Corridor` (pure geometry: cells at +2/+6/+10 km with 4 km radius, along/cross-track, distance to route, detour) and `CorridorCache`; `CorridorDiscovery` fetches only cells not covered by a fresh cached cell, so each refresh normally fetches just the new far cell.
- **Road-trip ranking:** `RoadTrip.classify` flags `VISIBLE` (landmark kinds from OSM category/description, ≤ 12 km, not behind) and `WORTH_A_STOP` (relevance ≥ 0.65, ahead, ≤ 5 km from the route line; peaks excluded); bonus weight 0.6. `RankedCandidate.roadTrip` flows into `NarrationRequest.roadTrip`; the narration ends a worth-a-stop story with one navigation offer (with a rough detour), and "take me there" maps to NAVIGATE.
- **Driving pacing:** hard 90 s gap and `ManeuverDetector` (20 s window: speed range ≥ 4 m/s with the minimum < 65 % of the maximum, or ≥ 45° turn above 2 m/s) → `LocationContext.maneuvering` → `EditorialRanker.holdForPacing`.
- **Implicit personalization:** `InterestModel` (full listen +0.05, skip within 8 s −0.25, follow-up question +0.15; once per story and signal; bounded ±0.5; 7-day half-life) adjusts interest weights except remembered likes/avoids; persisted via `InterestStore` (`learned_interests.json`).

## 25. Latency Plan

The target is first audio within about 1–2 s of the user finishing speaking (spec A §9). Current pipelines are serial:

- **Question:** record MP4 → transcribe → converse with web search → full-clip TTS → MediaPlayer. About 4–12 s.
- **Narration:** tick → narrate → full TTS → play. About 4–8 s of "Preparing a story…".

Planned improvements, ordered by payoff:

1. **Search only when needed.** First call without tools, with a `needs_search` flag in the schema. Re-call with `web_search` (`search_context_size: low`) only when the flag is set, speaking a short bridge line meanwhile. This also removes the web-search fee from most turns.
2. **Prefetch the next narration.** While a segment plays, generate text and TTS for the next best candidate. Keep distance and direction in a short lead-in built locally, so the cached body stays valid as the user moves. Cache TTS audio by hash of text, voice and model.
3. **Stream answers.** Use `stream: true` on Responses and cut the text at sentence boundaries. Send each sentence to `/audio/speech` as `pcm` and play it through `AudioTrack` while later sentences are still generating. This needs a streaming `AudioOutput`.
4. **Faster capture.** Record PCM/WAV with `AudioRecord` (no MP4 finalize step), or use streaming transcription.
5. **Longer term: OpenAI Realtime** over WebRTC, with an ephemeral token minted on the device from the user's key. This gives barge-in and sub-second turns. Grounded discovery and web search stay available as tools.
6. **Prompt caching.** Put static instructions first and the per-turn context last so OpenAI's prefix caching applies. Cap facts at about 1,500 characters.

## 26. Test Strategy

No test layer needs a real phone or a real OpenAI key.

| Layer | Where | What it covers |
|---|---|---|
| Core unit tests (JVM) | `core/src/test`, local and CI | Geo math, travel-mode hysteresis, refresh policy, ranker (threshold, repetition, conversation cost, theme), heard history, language resolution, Responses parsing, structured replies, memory, discovery merge against a MockWebServer fake of Wikipedia and Overpass, session orchestration in virtual time (narrate once, barge-in, skip, return to radio, memory persisting across sessions). |
| Android JVM tests (Robolectric + Compose) | `app/src/test`, CI | Radio screen states and controls, mode chips, typed questions, nearby list, transcript and errors; setup and settings forms; memory list and forget actions; file stores. |
| Emulator end-to-end | `app/src/androidTest`, CI job `emulator-e2e` (API 34) | The real app with a test Application pointing OpenAI, Wikipedia and Overpass at an on-device fake server, with silent audio. Enter key → start → GPS fix → grounded story → typed question answered → learned preference visible in Settings → stop. |
| Live smoke test (optional, planned) | CI, runs only when the repository secret `OPENAI_API_KEY` is set | One real narration and one real answer against the live APIs, to catch API or model drift. The key never enters the repo. |
| Manual field test | The owner's phone, with their own key | Real GPS, audio routing, Bluetooth, battery. |

## 27. Implementation Status (updated 23 Sep 2026)

| Area | Status |
|---|---|
| Location processing, travel mode, refresh policy | Done (speed-based); activity recognition planned |
| Discovery (Wikipedia + OSM), merge, cache, photos, gallery | Done |
| Editorial ranker, heard history, teasers | Done |
| Narration and conversation (search on demand), host styles, humour rules | Done |
| Natural voice (Realtime), tools, barge-in; classic push-to-talk fallback | Done (needs field testing on devices) |
| Listener memory; favourites; share | Done |
| Photo pager + map panel; driving layout; media session controls | Done |
| Keyless preview and degraded/offline mode: on-device notes (`NarrationFallback`) read by Android TextToSpeech; OpenAI outages back off to it (`FallbackGate`); area cache on disk (`AreaDiskCache`, 14-day TTL, 24 areas, 1.5 MB); offline-aware discovery and questions | Done |
| Trust: narration returns `{text, basis}` (documented / disputed / legend / mixed) shown as a chip; "why this story?" line from the score breakdown (`StoryReason`) | Done |
| Tests: core JVM, Robolectric UI, emulator E2E, key-gated live smoke test | Done |
| Review findings (docs D, F) | Fixed, or listed as planned |
| Segment formats (bumper, quiz, on this day, station ID, area), pacing dial incl. non-stop (§33) | Done |

## 28. Natural Voice: OpenAI Realtime

**Transport.** A WebSocket to `wss://api.openai.com/v1/realtime?model=gpt-realtime`, authorised with the user's key (app-only, see doc C). OkHttp handles the connection (`RealtimeClient`). The event builders and parser live in `RealtimeProtocol`, which accepts both GA and beta event names.

**Session setup.** On `session.created` the app sends `session.update` with:
- `type: realtime`, instructions, and `output_modalities: [audio]`;
- input: `audio/pcm` at 24 kHz, transcription with `gpt-4o-mini-transcribe`, and `server_vad` turn detection (650 ms silence, `create_response`, `interrupt_response`);
- output: `audio/pcm` at 24 kHz, with the configured voice;
- four function tools.

**Loop (`LiveConversation`).**
- The mic streams 100 ms `input_audio_buffer.append` chunks.
- `response.output_audio.delta` chunks are played as they arrive.
- `input_audio_buffer.speech_started` flushes local playback, which is how barge-in works.
- Transcripts from both sides go to the transcript view and the conversation history.
- `response.function_call_arguments.done` runs the tool and sends `function_call_output`, then `response.create`.
- After 25 s of silence the conversation closes and the radio resumes.

**Tools.**

| Tool | What it does |
|---|---|
| `web_search(query)` | A Responses call with `web_search`; returns 2–4 speakable sentences. |
| `radio_control(action, language?, theme?, entity_id?)` | resume, pause, skip, change language, set/clear theme, navigate, accept/decline an offer, star a place. |
| `remember(category, text, topic?, forget_text?)` | Adds to or removes from listener memory. |
| `set_trip(summary)` | Records trip context. |

**Instructions.** They embed the persona, the rules and the current context JSON (location, active story, nearby places, profile, trip, pending offer). The session is short-lived, so the context stays fresh.

**Android audio (`AndroidPcmAudio`).**
- Capture: `AudioRecord` with the `VOICE_COMMUNICATION` source, 24 kHz mono 16-bit, `AcousticEchoCanceler` and `NoiseSuppressor`. Echo cancellation stops the host's own voice from triggering an interruption.
- Playback: a streaming `AudioTrack` fed by a queue on its own thread, which is flushed on barge-in.
- The foreground service adds the `microphone` type when record permission is granted, so hands-free answers work with the screen off.

**Where it opens.**
- Tapping the mic in natural-voice mode.
- After a teaser, to hear yes or no.
- For the road-trip question, which the live host asks in its own voice.

Typed questions are routed into an open live session. If the connection fails, the transcript shows a note and the classic path remains.

## 29. Prompt Design and Host Styles

- `HostStyle` (persona and voice direction) is applied to narration, conversation, live instructions and TTS `instructions` (`gpt-4o-mini-tts`).
- Narration rules:
  - facts only from the provided facts;
  - a hook first;
  - story, one fun fact, and context;
  - direction mentioned once;
  - speech-like sentences;
  - humour guardrails;
  - `format: teaser` produces a hook plus the question.
- Conversation rules add: one clarifying question at most, `pending_offer` handling, `trip_context` capture, and `star_place`.
- Static rules sit in `instructions` so prefix caching works. Per-turn context goes in a trailing developer message; facts are capped at 1,500 characters.

## 30. Story Offers and the Trip Question

**Teasers.** A candidate qualifies when its facts are at least 900 characters and at least 8 minutes have passed since the last teaser. It is then offered only after 2 or more regular stories. Rich candidates are not prefetched, so the teaser can happen.

**After a teaser.**
- The session sets `pendingOffer`, marks the place as mentioned but not heard, and opens a 25 s answer window (a live listening window when natural voice is on).
- The answer is handled by, in order:
  1. a local yes/no fast path in several languages;
  2. the offer card's buttons;
  3. the model's `accept_offer` / `decline_offer` actions.
- Yes narrates the full story. No marks the place heard, so it isn't offered again, with no topic penalty. Silence clears the offer.

**Trip question.** Asked the first time a session detects driving, via `HostLine.TRIP_QUESTION` (spoken by the live host when available). The answer arrives through `trip_context` or `set_trip` and is passed into narration and conversation.

## 31. Favourites and Share

- `FavoritePlace` holds id, name, category, point, summary (first sentences, ≤280 characters), url, image and time saved. It is persisted as JSON in app-private storage (`FileFavoritesStore`, excluded from backup).
- `RadioSession.toggleFavorite` / `removeFavorite` handle the UI, and `STAR_PLACE` handles voice.
- `ShareText.build` produces the name, summary, "Read more" link and an OpenStreetMap link. The app sends it with `Intent.ACTION_SEND` through the chooser.

## 32. UI and Distribution

- **Screens.**
  - *Idle*: a large Play button, plus the Saved tab.
  - *Running (walk/still)*: status card (ON AIR pill, equalizer, mode chips, severity-coloured status); offer card; tabs Now (photo pager, map inset, Star/Share/Navigate, story card), Transcript, Nearby (with stars) and Saved; controls Repeat, Pause/Back to radio, Skip, Nearby; and a mic plus text field.
  - *Driving*: a large photo, the place name, Star and Navigate, a 104 dp mic, and Pause/Back and Skip at 72 dp.
- **Notification.** MediaStyle, with a media session for lock-screen, headset and car Bluetooth controls.
- **Icon.** An adaptive launcher icon (pin, amber on-air light, broadcast arcs) with a themed monochrome layer.
- **Distribution.** A committed debug signing key keeps updates installable. CI publishes the `latest` GitHub release containing `gpsradio.apk`.

## 33. Walking Tours, Stings and the Trip Journal

**Walking mini-tour** ("give me 30 minutes").
- `TourPlanner` (pure) picks 3–6 high-scoring stops that fit the budget (walking 4.5 km/h × 1.25 street factor, 4 min per stop, loop back to the start), trying several greedy orderings, and orders them by nearest neighbour plus 2-opt. It falls back to two stops, or returns null.
- `RadioSession.startTour(minutes)` sets `tour: TourState(stops, nextIndex)` and speaks `HostLine.TOUR_INTRO`, drafted by `TourText` and restyled by the narrator. While a tour runs, the regular scheduler is held back.
- Coming within 40 m of the next stop (or of a later one) tells its story. A second `SegmentFormat.ARRIVAL` chapter ("look for…") follows when the facts are rich enough; it is prepared while the story plays. Directions to the next stop (`TOUR_NEXT`) come after each stop, and `TOUR_END` closes the tour. English functional lines skip the model.
- Voice: the `start_tour` / `end_tour` conversation actions (`tour_minutes`) and the `radio_control` tool (`minutes`). The conversation context carries `walking_tour`.
- UI: the chips "Walking tour: 15 · 30 · 60 min" in the Nearby tab, plus the line "Stop 2 of 5 · Castle · 250 m" with End tour on the Now tab (`TourUi.kt`).

**Stings.**
- `StingPlayer` is a port. `StingSynth` (in core, pure) synthesizes 24 kHz PCM: a 0.6 s station arpeggio before every story, a two-note chime before classic answers, and a short blip when a live conversation starts listening.
- The app's `Stings` plays them through a static `AudioTrack`.
- The Settings switch "Sound effects" (`SessionConfig.soundEffects`) turns them off.

**Trip journal.**
- Every story heard to the end is recorded with place id, name, time, point, first sentence and source URL.
- `Journal` keeps a local-day grouping, replaces a place heard again on the same day, and prunes entries after 90 days. It is persisted as JSON through `JournalStore` (`FileJournalStore`).
- The Saved tab lists it by day (`JournalUi.kt`). Each entry has "Tell me again" (re-narrates the place if it is still a candidate, otherwise asks the host) and Share.
- **GPX export** of a day (`JournalGpx`, GPX 1.1 waypoints plus a track) goes through the share sheet as text, with the `.gpx` file name as the subject. This avoids a FileProvider, manifest entries and URI grants, and works with every mail, chat, notes or cloud app. A file attachment through a FileProvider is a later option if direct import into map apps is needed.

## 34. Programme: Segment Formats and Pacing

**Formats.** Between place stories, `Programme` (core/session) schedules short radio formats. All are grounded; the humour and fact guardrails of §29 apply.

| Format | Length | Source of facts |
|---|---|---|
| `BUMPER` ("did you know") | ~15 s (~35 words) | `facts` of a nearby candidate not yet told, mentioned or used |
| `QUIZ` | ~15 s | as above (richer facts); the model writes the question and an `ANSWER:` line |
| `ON_THIS_DAY` | ~30 s | Wikipedia's keyless feed `/{lang}.wikipedia.org/api/rest_v1/feed/onthisday/events/MM/DD` (`OnThisDayClient`, English fallback, cached per day); the event most related to the listener's country/region/city or nearby coordinates wins, sombre events are de-prioritised |
| `STATION_ID` | 1–2 sentences | titles told so far ("so far today: …") |
| `AREA` | ~40 s | the Wikipedia article of `AreaLabel.city` / `region` (`WikipediaClient.articleByTitle`), split into facets (overview, history, people, culture, geography); each facet told once |

**Rules** (`Programme.next`, pure; the session reports what aired):
- never two fillers in a row, never while a good place story is ready (ignoring the temporary post-segment penalty), never while driving through a dense area (≥ 8 candidates within 3 km);
- the station ID is due every 10 stories, even when a story is ready;
- fillers wait for the full pacing gap and a per-pacing filler gap; formats rotate (the one aired longest ago first); on this day airs once per day;
- a quiz opens a 25 s answer window; an answer in conversation (classic or live) is judged by the host with `quiz` in context; otherwise the answer line is spoken when the window closes.

**Pacing dial** (`SessionConfig.pacing`, Settings chips): scales the speak threshold and the segment gap.

| Pacing | Threshold | Gap (walking) | Filler gap |
|---|---|---|---|
| Chatty | ×0.85 | ~22 s | 2 min |
| Balanced (default) | ×1.0 | 45 s | 5 min |
| Rare | ×1.25 | 90 s | 12 min |
| Non-stop | ×0.7 | ~4.5 s | none |

A hard floor of a quarter of the gap applies between any two segments. Driving keeps ≤ 30 s per segment and ≥ 90 s between segments, except in Non-stop, where the driving gap is 4 s (`Pacing.drivingMinGapMs`, spec A §38) so the radio keeps talking on the road; junctions (`holdForManeuver`) always mean silence.

**Non-stop.** When nothing crosses the (lowered) threshold, the programme keeps talking in this order: (1) weaker unheard nearby places above half the threshold, told as full stories; (2) area facets; (3) bumpers, on this day and quizzes (fillers may follow fillers); (4) a wider discovery radius (×2 per step, up to 3 steps). Teasers are off and the quiz pause is 5 s, so there are no long answer windows. Nothing is repeated; when everything is exhausted, the radio is silent rather than repetitive.

## 35. Out of OpenAI Credit

When the key's credit or billing limit runs out, OpenAI answers HTTP 429 with `insufficient_quota`. This is not a short rate limit: the listener has to act.
- **Detection** (`QuotaErrors`): the HTTP body's `error.code` / `type` / message, also on a truncated body (`OpenAiClient.friendlyError`); Realtime `error` events, failed `response.done` with `status_details.error`, and a 429 handshake. Messages start with "OpenAI credit ran out", so every path (stories, answers, speech, live voice) recognises them.
- **Session** (`RadioUiState.quotaExhausted`):
  - A persistent ERROR status with an "Add key" action, and a transcript line (not repeated back to back). The text differs for the built-in key ("add your own key") and the user's own key ("top up or add another key").
  - Stories continue as on-device notes. The first one starts with a one-time spoken notice in the session language, because the listener may be driving.
  - Fillers are skipped.
  - The warning clears on the next successful OpenAI story, or at once when the key changes in Settings (`onApiKeyChanged`).
- **App:** a heads-up notification on the "Alerts" channel. Tapping it or its action opens Settings. It is cancelled when credit returns.

## 36. Versions, Releases and Self-Update

The app is not in a store, so it manages its own releases and updates.

- **Versions:** `versionName` is `0.5.<CI run>`. `BuildConfig.GIT_SHA` and `BUILD_DATE` complete it, and Settings shows all three. `versionCode` is fixed (100), so any build installs over any other, older or newer, and keeps settings. The signing key is the committed debug key.
- **Releases (CI):**
  - Every push creates a pre-release `v0.5.<run>` with `gpsradio-0.5.<run>.apk`.
  - The `promote` job runs only after unit, Robolectric and emulator tests pass. It moves `latest` (`gpsradio.apk`, `version.txt`, `update.json`) to this build and keeps the replaced one as `previous`.
  - Only the newest 15 versioned builds are kept.
- **Update manifest:** `latest/update.json` has the fields `version`, `build`, `apk` (the immutable versioned asset), `sha256`, `size`, `commit` and `notes`.
- **Self-update:**
  - `UpdateClient` (core) parses the manifest. It requires https and a 64-hex checksum, compares build numbers (local builds never update), and streams the APK while verifying its SHA-256.
  - `AppUpdater` (app) checks on app resume at most every 6 h, or on demand in Settings.
  - Installing first asks for "Install unknown apps" if needed, then downloads into the app cache and commits a `PackageInstaller` session. On Android 12+ it sets `USER_ACTION_NOT_REQUIRED`, so later updates need no tap. `InstallResultReceiver` starts the confirmation screen when Android asks for one and reports failures, such as a signature conflict.
  - A banner offers the update on the radio screen, but never while driving. Nothing installs without a tap.

## 37. Photo Tips and Drive-By Detours

- **`PhotoSpots`** (core/editorial):
  - `isPhotogenic`, a regex over OSM tags, description and name; `isViewpoint`.
  - `suitable(r, loc)`:
    - walking or cycling: ≤ 600 m; stationary: ≤ 900 m;
    - driving: viewpoints only, 0.3–8 km ahead and ≤ 1.5 km off the route line.
  - `sun()`, an on-device NOAA-style sun position (±1°), and `lightHint()`, which describes the phase (blue/golden hour, midday, after dark) and the sun relative to the subject's bearing.
- **`SegmentFormat.PHOTO_TIP`:**
  - Runs about 15 s and counts as a filler.
  - `Programme` rotates it in when `Situation.photoSpot` is set, at most every 15 min, and never twice for the same place.
  - The narration context adds `light` and `is_viewpoint`. The prompt forbids invented facts and camera jargon; while driving it says "pull over there", never photos at the wheel.
- **`Detours`:**
  - `minutes()` estimates the there-and-back detour (`Corridor.detourM`) at 50 km/h.
  - `ahead()` lists up to 3 unheard worth-a-stop places ≤ 15 min, published as `RadioUiState.detours`.
  - After a WORTH_A_STOP story whose text ends with a question, the session opens an offer with `OfferKind.DETOUR`. A yes (tap, voice, classic or live `accept_offer`) calls `onNavigate`, the maps app handoff. The conversation context labels it "directions to …".
  - `navigateTo(placeId)` serves the driving detour card and the Nearby list.
- **Conversation:** nearby items carry `photo_spot` and `detour_minutes`, so "any good photo spots?" and "any stops worth a detour?" are answered from them.
- **UI:**
  - The offer card shows "Take a short detour to X?" with Navigate there / Not now.
  - The driving layout has a detour card with a 56 dp Navigate button, hidden while an offer is pending.
  - The Nearby list has a camera icon on photo spots and a detour label with a Navigate icon.

## 38. Discovery Extras: Food & Shops, Film, Events, Jewish Heritage; Quizzes Off

- **`PlaceCandidate.features`** is a set of `PlaceFeature` (FILM_LOCATION, HISTORIC_EVENT, EAT_DRINK, SHOP, JEWISH_HERITAGE), with `eventYear`. Both are serialisable with defaults, so the offline cache stays compatible. The narration context passes `features` and `event_year`, and the prompt has a rule per feature. That includes no invented hours, prices or menus for eat/shop, and no humour about persecution.
- **Overpass**, in addition to the existing tags:
  - `amenity∈{restaurant,cafe,pub,bar,biergarten,ice_cream}` or any `shop`, only with `wikidata|wikipedia|heritage|historic` (notable only);
  - `religion=jewish`;
  - `memorial=stolperstein` within 400 m, in a second `out` capped at 40.

  `DiscoveryService.stolpersteine()` clusters stones within 120 m into one place ("Stolpersteine, <street>") whose facts are the names and inscriptions. `osmFacts` adds cuisine and opening date.
- **`WikidataClient`** (keyless SPARQL, `wikibase:around`, `Endpoints.wikidataSparql`):
  - `filmLocations` (P915 on films, series, episodes, documentaries);
  - `events` (P585/P580 with coordinates, plus the Wikipedia sitelink);
  - `jewishConnections` (P19 birthplaces of people with Israeli citizenship P27=Q801, Judaism P140=Q9268 or Jewish ethnicity P172=Q7325, and 8 or more sitelinks).

  All three run in parallel with Wikipedia and OSM, capped at 8 km. They are optional: a failure is ignored and does not count towards partial-result caching.
- **`addWikidata()`** merges the results. It enriches a known place (same Wikidata item or same name within 300 m) with features, topics, a relevance boost and facts ("Filming location of: …", "Birthplace of: …"), or adds a new place. New events take their facts from the Wikipedia intro (`WikipediaClient.pagesByTitle`), otherwise from the Wikidata description at low relevance.
- **Topics:** there are two new topics, `FILM` and `JEWISH`, with a `Topic.label` for the chips ("Jewish & Israel"). `JEWISH` is in the default interests, and `TopicClassifier` has keyword and tag rules for both.
- **Quizzes off:** `Programme.Config.quizzes = false` (the mechanics stay tested with it on). `HostLine.PREFERENCE_QUESTION` is asked once per session (`SessionConfig.askPreferences`, on in the app). It needs at least 4 stories heard, fewer than 3 remembered preferences, and a pacing other than Non-stop. It is spoken classically, or opened in the live voice. The answer becomes memory through the normal conversation flow.

## 39. Events Today Nearby

- **Source:** `EventSource` (a port) and `EventScout`. There is no keyless worldwide events API, so this is one Responses call with `web_search` (`user_location` = the area) and a strict `local_events` JSON schema (title, category enum, venue, start/end local time, url, why, distance_km). The input carries the area, approximate coordinates, local time and weekday.
- **Filter** (`EventScout.parse`, pure):
  - a category from the enum, and a URL;
  - the keyword blacklist (classes, courses, meetings, services, in EN/DE/RU), applied to the title and "why";
  - running now, or starting within 10 h on today's local date;
  - ≤ 25 km, de-duplicated and sorted.
- **Session:** `maybeScoutEvents` runs on every tick and searches in the background (a timeout of 3× narration).
  - It searches at most every 3 h per area and language, and ≥ 45 min apart even when driving through towns.
  - It needs `SessionConfig.localEvents`, a key, being online, and not resting after an outage.
  - Finished events are dropped. `RadioUiState.todayEvents` holds up to 8.
- **Announcing:**
  - An event is due when it starts within 3 h (or is running) and hasn't been announced.
  - `Programme` plays the `EVENTS` filler ahead of stories (after the gap; not back-to-back fillers; not in dense driving).
  - The narration uses the events with local times; the fallback is a plain template.
  - Ids are marked announced before narrating, so a failure never loops.
- **App:**
  - The `RadioService` posts one notification per batch of new events ("events" channel, InboxStyle, taps open the app).
  - The Nearby tab lists "Today nearby" above the places; tapping opens the source.
  - There is a Settings switch. E2E disables the scout.
- **Cost:** about one web-search call per area per 3 h while listening.

## 40. Visit Info (Hours, Admission, Detour Details) and Prompt Caching

**Visit info** (`core/visit`):
- `PlaceCandidate.openingHours` and `fee` come from the OSM tags `opening_hours` and `charge`/`fee`.
- `OpeningHours.today()` evaluates the common forms ("24/7", day ranges and lists, several time ranges, "off", later rules win). It returns null for anything it doesn't understand (PH, months, sunrise), so the app never guesses.
- `VisitScout` makes one Responses call with `web_search` and a strict `visit_info` schema: open_today, hours_today, admission, visit_type, visit_minutes, walk_effort, walk_note, expect, source_url. Hours, admission and open/closed are dropped unless the result carries a source URL.
- `Visits.worthChecking` decides which places get checked: worth-a-stop detours, eat/drink/shop, places with OSM hours/fee, and paid-sight categories.
- **Session:**
  - At most one check per place per local day; failures are cached too.
  - At most 20 web checks per hour, each with a 20 s timeout.
  - The top candidate and detours are prefetched in the background. A story waits at most 8 s for its check. The web result wins and OSM fills the gaps.
  - The narration context gets `visit` (with `checked`: web or OSM listing) and `detour_minutes`. The prompt reports hours/closure and admission with their source, describes the detour (visit type, time, walk effort, what to expect) before the navigation offer, and says "couldn't be confirmed" rather than guessing.
  - `DetourSuggestion.visit` holds the one-line summary for the card.

**Prompt caching:** OpenAI caches the longest identical prompt prefix of 1,024 tokens or more automatically. Cached input is billed at a large discount and processed faster.
- **Static rules first.** All static rules are in `instructions`, which is identical for a given language and host style. Everything per request (place, facts, location, time, visit info) goes last in `input`. Narration instructions are about 2.3k tokens and conversation about 1.5k, so both are cacheable after the first call.
- **No per-call data in instructions.** Nothing in the instructions changes per call: no times, coordinates or counters. The rules for every feature (photo tips, events, visit info, Jewish heritage…) are always present, rather than added only when relevant, because conditional rules would change the prefix and break the cache.
- **Cache keys.** Every request sets `prompt_cache_key` (`gpsradio-narr-<lang>-<style>`, `gpsradio-conv[-search]-<lang>-<style>`, `gpsradio-events`, `gpsradio-visit`), so requests sharing a prefix are routed to the same cache.
- **Measuring hits.** `ResponseResult.cachedTokens` reports `usage.input_tokens_details.cached_tokens`.
- **Realtime.** The Realtime session sends its instructions once per conversation (`session.update`), and the server caches the conversation prefix itself.

## 41. Voice-First Notices and Language

- **Language:**
  - The narration instructions start with `LANGUAGE: … must be in <language>, even when the facts are in another language`, and every narration/filler context carries `spoken_language`.
  - This fixed a regression where the rule, at the end of a grown prompt, was ignored for Russian and Hebrew.
  - Live evals cover Russian and Hebrew, German facts → Russian, and English visit data → Russian.
- **Notices:**
  - `core/lang/Notices` holds hand-written notice texts (ru, en, he, de, es, fr; English fallback).
  - `RadioSession.announce()` speaks one after whatever is playing, and skips it while stopped or paused. It uses the host voice when OpenAI is usable, otherwise `fallbackSpeech` (on-device), and never fails the caller.
  - Hooked up to: questions unavailable (offline or no key), a failed answer (the quota text when out of credit), a tour that can't be planned (no GPS or too few sights), and tour abandoned.
  - The first on-device story of a degraded episode is prefixed with the offline/unreachable notice. It resets when OpenAI works again.
- **Live evals:**
  - A must-pass list (language, safety at the wheel, legend labelling, tragedy/dignity, no invented facts, hours or prices) fails the run on any miss.
  - The judge-graded style cases need ≥ 85%.

## 42. Natural Live Voice

Sources: OpenAI's realtime prompting guide and voice-agent metaprompt, and the realtime VAD docs.
- **Turn-taking:** `semantic_vad` (eagerness `auto`) replaces silence-based `server_vad`. It judges the end of a turn from what was said, so it waits through "um… and the castle…" and answers promptly after a complete question. `noise_reduction: far_field` handles a phone in a car or on a loudspeaker.
- **Barge-in:**
  - When the listener starts speaking (also while the finished answer is still queued for the speaker) or types mid-answer, the client stops playback, drops leftover audio, and sends `conversation.item.truncate`.
  - `audio_end_ms` is the audio received for that item minus what is still queued, so the conversation history holds only what was actually heard.
  - `AudioDelta.itemId` tracks the message being played.
- **Voice:** the live host defaults to `marin` (`SessionConfig.realtimeVoice`); marin and cedar are the most natural gpt-realtime voices.
- **Prompt structure** (guide-style bullets with key words in caps):
  - Role & Objective; Personality & Tone (identity, demeanor, tone, enthusiasm, formality, emotion, occasional filler words); Pacing & Length (1–3 sentences, then yield).
  - Variety (never repeat a sentence or opener; sample phrases are not scripts); Language (stay in the listener's language, say local names the local way).
  - Unclear audio (ignore road noise, music and the radio; ask to repeat instead of guessing); Tools (a short, varied preamble before slow tools); Conversation Flow (offers, goodbyes); then the context.

## 43. Always Listening

- **`SessionConfig.handsFree`** comes from `AppSettings.alwaysListening`, on by default and toggled from the top-bar mic icon, the notification action or Settings. With `liveVoice`, the session keeps a **persistent** `LiveConversation` open while the radio runs (`maybeStandby` on every tick).
  - It closes when switched off, stopped, offline, out of credit, or not possible.
  - Failures back off 1, 2, 4… up to 10 min, and pause quietly with a status after 3 in a row.
  - The listening sting plays only for real conversations.
- **Persistent `LiveConversation`:**
  - `inConversation` tracks an exchange. Idle for 15 s calls `LiveHost.onLiveIdle()`: the radio resumes and the connection stays open.
  - `prompt()` asks something (offers, host questions) on the open connection. `beginExchange()` expects an answer. `quiet()` stops the host but keeps listening; radio controls call it instead of closing.
  - `updateInstructions()` sends an instructions-only `session.update` with fresh context when a new story starts.
- **Barge-in over the radio:** `SpeechStarted` from the server (after the on-device gate let speech through) while not conversing makes `startExchange()` cancel the story, which stays unheard, and switch to CONVERSING. Barge-in over the live host uses the existing truncate path (§42).
- **`SpeechGate`** (core, pure, tested):
  - It opens after 120 ms of speech energy and sends 500 ms of preroll, so the first word isn't lost. It closes after 1.5 s of quiet and sends that quiet so semantic VAD sees the end of the turn.
  - The threshold is 700 RMS normally and 2,500 RMS while playback is audible: the radio's story (`PcmAudio.setRadioAudible`, wired from `RadioState.NARRATING`) or the host's own audio.
  - Only speech is uploaded, instead of about 170 MB/h of continuous PCM.
- **Audio focus:** `AndroidPcmAudio` no longer takes focus when capture starts (an open mic must not duck or pause the radio). It takes focus only while the live host speaks.

### Self-update: the install confirmation (fix)

Android usually asks "Install this update?" (always on the first self-update, and whenever the app is not the installer of record). PackageInstaller reports this to `InstallResultReceiver` as `STATUS_PENDING_USER_ACTION`, with the confirmation intent.

- **The bug.** The receiver opened the confirmation itself. Android blocks activity starts from a broadcast receiver, and the fallback only ran on the app's next resume. With the app already on screen, the update downloaded and then sat at "Installing…" forever.
- **Now:**
  - The intent goes into `AppUpdater.confirm` (a StateFlow). The visible `MainActivity` collects it and opens it at once.
  - A high-priority notification, "GPS Radio x is ready: tap to install", covers the case where the app isn't on screen.
  - If Android answers nothing within 2 minutes, the state becomes Failed with a retry.
  - After the update, `UpdatedReceiver` (`MY_PACKAGE_REPLACED`) posts "GPS Radio updated to x: tap to open", because Android stops the old app and an app can't restart itself from the background.

### Self-update: checked on every start, offered in a dialog

- **When it checks.** Every app start (a fresh activity) checks the release manifest, on top of the 6-hourly checks on resume. The start-up check is silent if it fails.
- **The offer.** When a newer tested build exists, a dialog offers it: "Update available: GPS Radio x. Update now?", with **Update** and **Later**.
  - **Update** installs it. If installs from GPS Radio aren't allowed yet, it opens that permission page first.
  - **Later** hides the dialog for that version until the next start. The Install banner stays in the menu.

### Photos: the image loader identifies itself (fix)

Photos load with Coil, which used its own HTTP client and the generic `okhttp/…` User-Agent. Wikimedia's image servers refuse clients that don't identify themselves (Wikimedia User-Agent policy), so the photo panel showed only the map.

- **The fix.** `GpsRadioApp` is Coil's `ImageLoaderFactory`: the image loader sends the same identifying User-Agent as every other request the app makes (`GpsRadio/<version> (Android; <repo URL>)`).
- **Test.** The live CI test `LivePhotoTest` downloads a real Wikipedia lead photo and gallery images this way. It also prints what a generic client gets.

### Self-update: visible, resumable, with a manual fallback

These fixes follow the report that the update flow still didn't work:

- **Visible.** Download progress, "Installing…", "Allow installs" and failures now show on the main screen, above the place name. Before, they only showed inside the menu, so tapping Update looked like nothing happened.
- **Resumable.** Without the "install unknown apps" permission, Update opens that page, and the update continues by itself when the listener returns (`resumeAfterPermission` on resume). Before, it stopped there.
- **Failures are shown**, with **Retry** and **Install manually**. The manual option hands the downloaded APK (kept after a failure) to Android's own installer screen through a FileProvider, the same screen a browser download opens.
