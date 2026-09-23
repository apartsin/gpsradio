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
