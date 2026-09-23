# Code review rounds

## Round 1 — 23 Sep 2026 (HEAD 1f9ac80): NOT CLEAN
The reviewer confirmed that the OpenAI Realtime GA usage is correct: session.update shape, event names, function-call outputs, and `response.create` with instructions. It also spot-checked the doc D fixes and confirmed them.

| # | Sev | Finding | Status |
|---|---|---|---|
| 1 | P0 | Radio controls (Back to radio, Skip, Pause, offer buttons, Repeat, Tell me about, headset/notification) did not close an open live conversation: two audio streams, mic left open | Fixed: `endConversation`, `skip`, `doPause`, `repeat`, `tellAbout` and `answerOffer` close live; regression test |
| 2 | P1 | Echo: the host's voice on the loudspeaker could trigger server VAD (self-interruption) | Mitigated: an echo gate in `AndroidPcmAudio` sends silence while the host is audible unless the mic level shows the listener talking over it; echo cancellation stays on. Needs field tuning |
| 3 | P1 | Stale `pendingOffer` after Back to radio or Skip | Fixed: `endConversation` and `skip` clear the offer; test |
| 4 | P1 | A typed question during a live answer was dropped (only one active response allowed) | Fixed: sends `response.cancel`, flushes audio and drops leftover deltas; test |
| 5 | P1 | Microphone foreground-service type was decided only at service start | Fixed: the service restarts when mic permission is granted |
| 6 | P1 | `session.update` errors were invisible (e.g. a TTS-only voice) | Fixed: errors before the first audio are fatal with a message; unsupported voices map to `coral`; tests |
| — | P2 | Server close not surfaced | Fixed (`onClosing` acknowledges) |
| — | P2 | Connection closed while connecting went silent | Fixed: shown as an error |
| — | P2 | Tool calls raced the idle watchdog | Fixed: timer paused while a tool runs |
| — | P2 | Leftover audio after barge-in | Fixed: dropped until `response.done` |
| — | P2 | No audio focus during live voice | Fixed: transient focus while capturing |
| — | P2 | 25 s dead air after live exchanges | Reduced to 15 s |
| — | P2 | Live mic was a dead end without mic permission | Fixed: falls back to hold-to-talk until granted |
| — | P2 | Race between AudioTrack write and release | Guarded |
| — | P2 | `resume()` while a story plays | Ignored now |
| — | P2 | Map re-renders every overlay on each state change | Open (minor) |
| — | P2 | `conversation.item.truncate` after barge-in | Open |

## Round 2 — 23 Sep 2026 (HEAD 26adf26, after merging road trips, offline/trust, tours/journal, formats/pacing): NOT CLEAN
The reviewer checked every `when` over the extended enums in the app: all are exhaustive. It confirmed that NONSTOP disables teasers and quiz answer windows, and that live teardown cannot deadlock.

| # | Sev | Finding | Status |
|---|---|---|---|
| 1 | P1 | `handleUtterance` played the answer unguarded: audio focus refused (phone call) → uncaught exception → process crash | Fixed: guarded with a status message; the session scope has a `CoroutineExceptionHandler`; test |
| 2 | P1 | Journal "Tell me again" (`retell`) skipped round 1's live/offer cleanup | Fixed: `closeLive()` + `clearOffer()`; test |
| 3 | P1 | A walking tour silenced the radio indefinitely (e.g. the listener got in a car) | Fixed: the tour ends when driving/cycling, more than 1 km from the next stop for over 5 min, or past 2× its planned length; test |
| 4 | P1 | The prefetched story was almost always discarded as stale while driving/cycling (double OpenAI cost) | Fixed: no prefetch at speed |
| 5 | P2 | Quiz answer late: survives a tour start; waits for the full driving gap | Fixed: resolved on tour start; revealed as soon as the window closes (junctions still hold it) |
| 6 | P2 | A quiz without a parsable `ANSWER:` line was never resolved | Fixed: such a quiz is dropped before airing |
| 7 | P2 | Pause or hold-to-talk during a tour stop lost that stop | Fixed: rolled back unless skipped or failed |
| 8 | P2 | Live idle watchdog could cut the end of a long answer still queued for playback | Fixed: `PcmAudio.pendingPlaybackMs()` counts as activity |
| 9 | P2 | The tick after discovery never aired anything (ran inside the still-active discovery job) | Fixed: scheduled after the job completes |
| 10 | P2 | Restart after a long stop could narrate the old location | Fixed: a fix older than 2 min is dropped on start |
| 11 | P2 | Fillers called OpenAI during an outage (dead air until timeouts) | Fixed: skipped while on-device |
| 12 | P2 | Plain `writeText` stores could lose the journal/favourites on a kill mid-write | Fixed: all stores write-then-rename |

Also added in this round (a user request): out-of-credit (`insufficient_quota`) detection and notification (spec B §35).

## Round 3 — 23 Sep 2026 (line-by-line review of 16 core/app files, HEAD 15910a4): NOT CLEAN → fixed
Overall grade from the reviewer: "B: careful about grounding and failure modes; problems where features were bolted onto RadioSession and the live-voice path."

| # | Sev | Finding | Status |
|---|---|---|---|
| 1 | P1 | Live voice: `response.create` sent right after a tool call, while the tool-calling response was still active; that error before the first audio was fatal, killing the conversation (and always-listening standby) | Fixed: follow-up requested on `response.done`; errors classified (setup/key/quota/handshake fatal, routine protocol errors not); a server close in always-listening mode is a normal end; tests |
| 2 | P1 | Mic thread could read a released `AudioRecord` after a quick stop/start (crash), and busy-looped on read errors | Fixed: per-capture thread and stop flag, joined before release; breaks on errors |
| 3 | P2 | A blank `reply` made the app speak the raw JSON | Fixed |
| 4 | P2 | Events 4–8 were marked announced but never spoken | Fixed: announce ≤3 and mark only those |
| 5 | P2 | Hours filled from OSM were presented as "checked online" | Fixed: `source = "mixed"`, and the prompt names the provenance |
| 6 | P2 | Skipped visit checks (offline, budget) blocked the check for the rest of the day | Fixed: skipped checks aren't cached |
| 8 | P2 | Echo guard only while NARRATING (notices, answers and host questions were unguarded) | Fixed: driven by the actual audio output (count of active plays); the leaked `MainScope` collector is removed |
| 9 | P2 | The Jewish birthplace fact was appended after a long extract and cut by the fact budget | Fixed: prepended |
| 10 | P2 | Quiz prompt text shipped although quizzes are off (and contradicted "never test knowledge") | Fixed: removed from all prompts (mechanics kept behind `Programme.Config.quizzes`) |
| — | P2 | A notice could overlap a story (`announce()` not tracked) | Fixed: stored as the speech job |
| — | P2 | `today()` used the system zone while events and visits use the injected zone | Fixed |
| — | P2 | `onApiKeyChanged` didn't reset the backoff unless out of credit | Fixed |
| — | P2 | A 401/403 was reported as "OpenAI is unreachable" | Fixed: "OpenAI didn't accept the API key" with Settings action; test updated |
| — | P2 | Audio focus kept after the host finished (other apps stayed paused) | Fixed: released when the playback queue drains |
| — | P2 | A teaser for a worth-a-stop place asked two questions | Fixed: no `road_trip` in teasers |
| — | P2 | The widened non-stop radius never shrank | Fixed: reset once a refresh finds ≥ 8 places |
| — | P3 | `stop()` left a stale offer; events from a previous town could be announced; "401" substring matching; overnight `openAt`; stale comments (GPS rates now match: walking 10 s / 15 m); photo-tip interval untested | Fixed (+ tests) |
| — | refactor | Split `RadioSession` (2.2k lines) into visit/events/tour/live coordinators; shared `onStoryHeard()`/`conversationRequest()`; static Realtime instructions with context as messages; typed error codes; named magic numbers | Open: next round |
