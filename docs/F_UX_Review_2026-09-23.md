# UX review — 23 Sep 2026

An independent UX review covered the user journey, layout, controls, visual design and accessibility. Its ASCII layout sketches for walking and driving informed the redesigned radio screen. The Status column shows what was done about each finding.

## P0
| Finding | Status |
|---|---|
| Driving mode looked identical to walking: small targets, text field, tabs | Done: `DrivingContent` shows a photo, the place name, a 104 dp mic, 72 dp Pause/Back and Skip, Save and Navigate, and no text entry |
| No lock-screen, headset or car Bluetooth controls; the notification only had Stop | Done: media session with a MediaStyle notification (Pause/Resume, Skip, Stop) whose title follows the story on air |
| Every status message was shown in error red; raw exceptions leaked; no "fix key" path | Done: `Status{text, level, needsKey}`, severity colours, friendly API-key message with an "Open Settings" button |
| Denying location was a dead end; the notification request was bundled with location | Done: permission card with "Allow location"; notification permission requested after the radio starts |
| No mic feedback; very short taps failed silently | Done: haptics on press and release, an equalizer animation while recording, accessibility semantics |
| State labels were confusing ("Listening for something worth telling" sounded like the mic) | Done: Off air / Scanning for stories / Tuning in… / On air (pulsing ON AIR pill + equalizer) / Conversation / Your call / Paused |
| A spoken "You're in {city}" opener | Planned (keyless on-device speech preview, roadmap #4) |

## P1
| Finding | Status |
|---|---|
| Controls hierarchy: Stop next to Pause at the same size | Done: Stop moved to the top bar; Pause is the primary 64 dp control; Repeat · Pause · Skip · Nearby |
| No explicit way back from a conversation | Done: "Back to radio" replaces Pause while conversing |
| Resume should continue in place | Partly done: an interrupted story is re-queued (not marked heard). Planned: true pause and resume of audio |
| Now tab should be a story card with Save and Share | Done: title row with Star, Share and Navigate; sources listed |
| Map fought with page scrolling; photo affordance unclear | Done: photo pager with page dots, a map inset that expands when tapped, a "Wikipedia ↗" chip; the panel is outside any scroll container |
| Mode chips: show what Auto detected; hide debug info (±m, raw score) | Done |
| Nearby rows: 56 dp, star toggle instead of the score | Done |
| Text field: IME Send action, "Ask anything…" placeholder, hide the keyboard after sending | Done |
| Setup: show/hide key, format hint, "Save and start listening" goes straight to the radio | Done |
| Dark map tiles, landscape layout, large-font wrapping of controls | Planned |

## P2
| Finding | Status |
|---|---|
| Visual identity: ON AIR pill, equalizer, station feel | Done (first pass); custom typography planned |
| Star / Saved journal | Done: Saved tab (while running and when idle) with Share, Navigate and Remove |
| Share via the Android share sheet | Done |
| Host personality selector | Done: Witty guide, Documentary, Family & kids, Late-night chill |
| Refining-question card with chips | Done: offer card "Want the full story?" with Yes / Not now; a driver is asked where they're heading |
| Realtime voice mode (orb, captions, End button) | In progress (natural voice conversation) |
| Photo gallery | Done: Wikipedia/Commons images per place |
| Launcher icon: pin + amber on-air light + broadcast arcs; monochrome layer | Done |
