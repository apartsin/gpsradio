# Product and audio-UX review, 24 Sep 2026

Reviewer: a read-only review agent (Fable model), working from specs A–I and the core and app code. This document condenses its report; the **Status** column records follow-ups decided with the owner.

## Headline

The content engine is strong: grounding, retelling, the dignity rules, tiers, junction silence. The weak side is the operating model, in four places:
- non-stop by default;
- an always-open mic by default;
- a shipped API key;
- no memory across sessions for researched stories.

These four decide whether people keep it running all day. Today they push the wrong way on cost, battery, repetition and trust.

## 1. Value: top 10 (impact vs. effort)

| # | Improvement | Effort | First step | Status |
|---|---|---|---|---|
| 1 | Persist what has already been told. Researched angles and programme state live only in memory, so day 2 in the same town repeats paid research and stories; places are forgotten after 30 days. | S | Save told angles and facet ids, keyed by scope and angle, with a 90-day expiry; keep places for 180 days, with "tell me again" to override. | open |
| 2 | Make barge-in work in a real car. A phone in a cradle, with Bluetooth audio latency defeating echo cancellation, is the likeliest field failure of the speech gate. | M | Offer capture through the car's hands-free mic (SCO) as a setting; a long press on the headset or steering-wheel button as push-to-talk. | open |
| 3 | A cost and battery budget with a visible meter: non-stop + gpt-4.1 + TTS + up to 40 lookups/h + a persistent Realtime session + fast GPS. Expect about $1.5–2.5/h in a town whose places have run out. | S/M | A per-session cost counter (usage is already parsed), a daily cap, a data saver. | open |
| 4 | Value after the trip: a spoken 2-minute "your day" recap and a shareable card (route, photos, sources). | M | A RECAP format over the day's journal; a share card rendered from the UI. | open |
| 5 | Route prefetch as an offline mode: prepare stories along a planned route on Wi-Fi the night before. | M/L | Extend corridor discovery to a route polyline; cache text and voice on disk. | open |
| 6 | Trust by voice: "sources?" and "is that true?" as voice commands; say "records show" vs "the story goes" consistently. | S | A voice command that reads the source names; add it to the evals. | open |
| 7 | The first minute: "You're in X, N stories around you" within 3 s; spoken onboarding (language, interests, kids on board); Russian UI strings. | S/M | An instant intro line with no model call; three onboarding questions written into memory. | open |
| 8 | Family back-seat mode: an opt-in quiz, mic closed by default. | S | `Programme.Config.quizzes` already exists and is tested. | open |
| 9 | Blind and low-vision walkers: TalkBack, clock-face directions, "describe what's in front of me". | M | A semantics pass; clock-face directions. | open |
| 10 | Say it like a local: pronounce the place name in the local language; one local word per town. | S | Use the DIALECT angle. | open |

Not recommended yet: sponsored local businesses. The notable-only rule is a trust asset; a curated pack made with a tourism board is the path that respects it.

## 2. Always on

- **Habit loop.** Car connects or a new town → play → one story worth retelling → investment (memory, favourites, journal). Show the investment, e.g. "42 places heard in Salzkammergut, 3 new since Tuesday".
- **Auto-start, lowest friction first.**
  - A media-button receiver, so Play on the steering wheel resumes the radio (no approvals needed).
  - When Bluetooth connects or the phone detects driving, a "Start the radio?" notification with a Start button. Starting silently from the background is blocked on Android 14+ for microphone and location services.
  - Android Auto: needs a media library service, Auto quality review and a Play Store listing, and the self-update mechanism is not allowed on Play.
- **Commute mode.** On routes you drive often, play "what's new": events, "on this day", untold angles, and dated news. One story per leg. Drop to Balanced automatically once the town's tier 1–2 angles are used up.
- **Silence vs. non-stop.** A 4 s gap while driving is close to continuous speech and tiring. Cap it at about 20 segments an hour outside a tourist mode.
- **Music.** A "with my music" mode is nearly free: Balanced pacing that ducks the listener's music. Duck, rather than pause, for navigation prompts.
- **Battery and data.** Adapt the GPS rate to the detected activity and stop GPS after 5 minutes of standing still; target ≤ 8%/h on foot. Keep MP3 (not PCM streaming) for users on roaming.
- **Surfaces that need no approvals.** A Quick Settings tile, a "Start / Talk" widget, the place photo as lock-screen artwork, a Wear OS tile for push-to-talk.
- **Notifications.** No heads-up notifications while driving; a weekly recap.

## 3. Risks and gaps

1. **The shipped key.** OpenAI's policies forbid embedding keys in client code. It also makes the owner the GDPR data controller for every user's voice and location (needs a privacy notice and a data processing agreement), and cost is unbounded. A thin proxy with per-device quotas fixes all three, and is required before any Play Store release or Android Auto.
2. **Spec contradictions.**
   - Non-stop driving gap: 12 s (§16, §24) vs 4 s (§38).
   - Angle lookups: 24/h (§37) vs 40 in the code.
   - Travel mode: chosen in the menu (§36) vs always automatic (§37).
   - "Country is not enough" (§35) vs country-scope angles (§37).
   - Events: default on (§30) vs `false` in core.
3. **Driver distraction.** A "yes" to a detour opens the maps app on screen; questions are asked while driving; notifications arrive while driving; 4 s gaps.
4. **Special-category data.** Wikidata religion (P140) and ethnicity (P172) of living people: restrict to people who have died. Make "Jewish & Israel" an onboarding choice rather than on by default.
5. **Licensing and rate limits.**
   - Attribution: Wikipedia (CC BY-SA) and Commons photos need per-file credit.
   - OpenStreetMap map tiles: the tile policy discourages app use of the public server.
   - Public Overpass/Wikimedia: they rate-limit per IP address, and mobile carriers put thousands of users behind one address.
6. **Hallucination in research.** The same gpt-4.1-mini call both finds and rates an item. For tier-1 angles (records, quirks, mysteries), require a Wikipedia or official citation, or two independent sources.
7. **Realtime session limits and cost.** Check for audible gaps on reconnect; measure the cost of refreshing instructions for every story.
8. **Kids.** In the family persona, keep the mic closed by default (children's privacy: COPPA, GDPR-K).
9. **Play readiness.** The microphone foreground service needs a declaration; self-update is not allowed.

## 4. Next three releases

- **R1 "Trustworthy all day" (2–3 weeks).**
  - Told stories remembered across sessions; commute "what's new".
  - A cost meter, a daily cap and a driving segment cap; Balanced by default on familiar ground; no notifications while driving.
  - Russian UI strings; spoken sources; Commons attribution; a living-person filter.
  - Resolve the spec contradictions.
- **R2 "Car and family" (4–6 weeks).**
  - A capture path through the car mic; resume from media buttons; "car connected" notification; tile and widget.
  - "With my music" mode; family mode.
  - Trip recap and share card; a TalkBack pass.
- **R3 "Plan, offline, scale" (6–10 weeks).**
  - Route prefetch as the offline mode.
  - A thin proxy replacing the shipped key.
  - Commercial map tiles and Overpass; Android Auto and a Play Store listing; a pilot curated pack with a tourism board.
