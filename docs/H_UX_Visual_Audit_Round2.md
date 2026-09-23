# UX / visual design audit — round 2 (23 Sep 2026)

The mockups in `docs/ux/*.png` were rendered from the Compose code in headless Chromium: idle, walking and on air, conversation with an offer, driving, settings, dark variants, and launcher icons in several masks.

## Headline
- **Theme.** It is still the Material purple template: only primary and secondary were set, so containers are lavender or pink and the dark-mode icon colour is purple.
- **Now tab doesn't fit.** On a 360×740 phone the story card gets about 1.6 lines, and less than zero when an offer is shown.
- **Driving is still busy.** It shows the full status card, the chips and Stop.
- **Repetition.** "On air" appears three times; the mode appears twice.

## Plan (status)
| # | Change | Status |
|---|---|---|
| P0-1 | Full M3 colour scheme (tokens below), `dynamicColor=false` | Planned (UI round 2, after feature merges) |
| P0-2 | Status card → 40 dp station strip (ON AIR · mode · speed, mode dropdown); offer card moves into the story slot | Planned |
| P0-3 | Driving: no status card, chips or Stop; 120 dp mic; 88 dp Pause and Skip; tap-to-talk by default while driving | Planned |
| P0-4 | Two-pane landscape layout | Planned |
| P0-5 | Errors announced (assertive live region) | Planned |
| P1 | Mic active colour amber with a ring (not error red); icon sizes; labels on 2 lines; photo scrim with title and actions over the photo; 48 dp source buttons; status severity styling; setup reduced to key + start; idle Walk/Drive preselect; first-minute skeleton with nearby chips; notification colour, large icon and app vectors; 12 sp minimum type | Planned |
| P2 | Card differentiation; display typeface; pause animations and respect reduced motion; progress bar; icon glyph centring and larger dot; notification icon at 16 px | Planned |

## Proposed tokens (M3)
| Token | Light | Dark |
|---|---|---|
| primary / onPrimary | #0F6B62 / #FFFFFF | #7ED8CA / #003733 |
| primaryContainer / on | #BFEDE4 / #00201C | #005049 / #9EF2E3 |
| secondary / onSecondary | #8A4B0F / #FFFFFF | #FFB876 / #4A2800 |
| secondaryContainer / on | #FFDCC0 / #2E1500 | #6A3B00 / #FFDCC0 |
| tertiary (on air) / container | #B3202E / #FFDAD8 | #FFB3B0 / #93000F |
| surface | #FBF9F4 | #101413 |
| surfaceContainerLow / Container / High | #F4F1EB / #EEEBE5 / #E8E5DF | #191D1C / #1C2321 / #262D2B |
| onSurface / onSurfaceVariant | #1B1C1A / #3F4946 | #E0E3E1 / #BEC9C5 |
| outline / outlineVariant | #6F7976 / #BEC9C5 | #89938F / #3F4946 |
