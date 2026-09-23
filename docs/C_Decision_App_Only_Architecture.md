# Decision record: app-only MVP (no backend)

Date: 23 September 2026 · Status: accepted · Affects: spec B §3, §11, §14, §16

## Decision

The MVP runs entirely on the Android device and talks directly to external services:

| Concern | Spec B (original) | MVP implementation |
|---|---|---|
| Place discovery | Discovery Service on backend | On device: Wikipedia geosearch (narration language + English) and OpenStreetMap Overpass, merged and cached per ~1 km cell |
| Research / grounding | Research Service on backend | Wikipedia intro extracts as grounded facts for narration; OpenAI Responses `web_search` for follow-ups and verification |
| Narration / conversation | Backend-orchestrated model calls | On device, OpenAI Responses API (models configurable in Settings) |
| Voice | Backend-issued short-lived tokens | On device: OpenAI speech-to-text + text-to-speech (push-to-talk). Realtime voice can be added later; the app can mint its own ephemeral token from the user's key |
| API credentials | Kept server-side | **User supplies their own OpenAI key**, stored encrypted on the device (Android Keystore via EncryptedSharedPreferences). No key is ever bundled in the APK or committed |
| Content cache / history | Server store | Local: in-memory area cache, heard-story history file with 30-day expiry |
| Localized web search | — | Coarse city/region/country from Android's on-device geocoder passed as `user_location` to web search |

## Consequences

- Each user pays for their own usage; there is no shared research cache across users, no central rate limiting, and no aggregate telemetry.
- Prompts and ranking weights ship with the app; changing them requires an app update.
- Exact coordinates go to Wikipedia/OpenStreetMap (rounded to ~11 m) and only approximate coordinates (~110 m) plus city name go to OpenAI.

## Path to a backend

All external access sits behind small interfaces in the `core` module (`PlacesProvider`, `Narrator`, `SpeechService`). A backend can later implement these (e.g. `BackendNarrator` calling `/v1/session/{id}/ask`) without changing the session orchestrator or the UI.
