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
