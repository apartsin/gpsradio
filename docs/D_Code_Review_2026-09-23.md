# Code review — 23 Sep 2026

This was an independent read-only review of `core/`, `app/` and CI on branch `claude/android-app-mvp`, focused on bugs, latency, cost and privacy. The reviewer found no definite compile errors. OpenAI request shapes are correct, and the API key is never logged.

The Status column shows what was done about each finding.

## P0 — latency
| # | Finding | Status |
|---|---|---|
| 1 | Fully serial, non-streaming pipeline, with web search on every conversation turn. Realistic 4–12 s to first audio against a 1–2 s target. | Plan in spec B §25. Fixed now: web search only when needed, next-story prefetch, TTS cache, shorter timeouts. Planned: streamed sentence-by-sentence TTS, PCM capture, Realtime. |

## P1 — should fix
| # | Finding | Status |
|---|---|---|
| 2 | Stationary: discovery is triggered only by new fixes, and stationary requests use a 25 m minimum distance. A failed first discovery, a language change or "refresh nearby" never retries. Fixes above 100 m accuracy are rejected, so the app waits for GPS indoors forever. | Fixed: the scheduler checks whether a refresh is due, the app seeds from the last known location, and fixes up to 300 m are accepted. |
| 3 | Push-to-talk pauses and cancels the story, which was already marked heard before playback. An accidental tap loses the story, and resume immediately starts the next one. | Fixed: a story is marked heard only on completion or skip, and an interrupted story is re-queued. |
| 4 | Skip during "preparing" penalizes the previous story and re-researches the same candidate. Repeated skip taps while idle compound the topic penalty. | Fixed: the pending story is tracked; penalties apply only when something was playing. |
| 5 | After a failed or empty transcription, or a conversation error, the session stays in CONVERSING and is silent for 45 s. | Fixed: it returns to radio. |
| 6 | No backoff. Transient errors (401, 429, offline) blacklist candidates one by one. | Fixed: exponential backoff for auth, rate-limit, server and network errors; the blacklist is kept for content errors only. |
| 7 | 90 s read timeout and 120 s call timeout, so a stalled call freezes the UI for 2 minutes. | Fixed: 25 s timeouts per model or speech call, 30 s for discovery. |
| 8 | EncryptedSharedPreferences created on the main thread with no recovery, which can crash at startup after an OEM key wipe. | Fixed: if creation fails, the store is reset and recreated. |
| 9 | For reasoning models, `max_output_tokens` includes reasoning tokens, so replies are truncated; `incomplete` status is not surfaced. | Fixed: higher cap for reasoning models, and the incomplete reason is reported. |

## P2 — nice to have
| Finding | Status |
|---|---|
| `Call.await` leaks the response body when cancelled | Fixed |
| The structured-output fallback re-runs on any 400 | Fixed: only on schema/format errors |
| Prompt order defeats prefix caching; facts up to 4000 chars | Fixed: static instructions first, facts capped |
| An unsupported language tag triggers rediscovery on every fix | Fixed: tags normalized |
| TopicClassifier substring matches ("war" in "award") | Fixed: word-boundary matching |
| Audio focus: result ignored, no resume after transient loss, focus taken per clip | Partly fixed: the result is checked. Planned: hold focus per programme, resume on gain. |
| `MediaPlayer.prepare()` on the main thread | Planned (moving to AudioTrack with streaming) |
| `repeat()` from PAUSED, `ask()` while paused, `rerank()` recomposing every 3 s | Fixed: no state update when the list is unchanged |
| Partial discovery results cached for 6 h; Overpass times out at an 8 km radius | Fixed: partial results get a short TTL; OSM radius capped when driving |
| Foreground service on Android 14+: restart after location is revoked; notification permission; no microphone service type | Partly fixed: startForeground failure is caught. Planned: request notification permission on its own. |
| Battery: high accuracy every 5 s while walking | Fixed: 10 s / 15 m |
| Transcription: no prompt with place names | Fixed: nearby names passed as a prompt |
| Privacy: Android Geocoder sends a ~1 km point to Google; API error bodies shown in the transcript | Documented in doc C |
