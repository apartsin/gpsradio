# Feature and UX brainstorm — 23 Sep 2026

Effort estimates: S = hours, M = 1–3 days, L = a week or more. The prioritized top 10 is also in spec A §18.

## Recommended top 10
1. **Media session with lock-screen, headset and Bluetooth car controls** (M). Makes hands-free use possible and is the prerequisite for Android Auto.
2. **Next-story prefetch** (S/M). Removes dead air between segments. *(Implemented in the first cut.)*
3. **Streamed, sentence-by-sentence answers** (M/L). Meets the 1–2 s target.
4. **Keyless on-device speech preview, which is also the offline/degraded mode** (M).
5. **Segment formats**: "did you know" bumpers, "on this day", quizzes, a local word, then-and-now (M/L).
6. **Short audio stings and host personality presets** (S/M).
7. **Confidence labels in speech and text, all citations listed, "why this story?"** (M).
8. **Road-trip pacing, look-ahead corridor, "worth a stop" with one-tap detour** (M).
9. **Implicit personalization signals**: full listen, early skip, follow-up questions (S).
10. **Walking mini-tour** ("give me 30 minutes"), with an extra chapter on arrival at each stop (M/L).

## All ideas by area
- **Driving:**
  - pacing rules: at least 90 s between segments, silence at junctions, 30 s maximum per segment;
  - corridor look-ahead and a "visible from the road" bonus;
  - "worth a stop" with detour time and a one-tap detour;
  - passenger mode, which allows images and longer segments;
  - headset button mapping: pause, skip, push-to-talk.
- **Walking:**
  - mini-tours with arrival detection at about 40 m;
  - "you're standing in front of it" second chapter;
  - "on your left" cues;
  - plaques and memorials boosted.
- **Onboarding:**
  - keyless preview using Android's own speech;
  - key paste helper with validation;
  - a spoken "you're in…" opener within 3 s, with no model call;
  - spoken interest picker.
- **Radio feel:**
  - segment formats;
  - host presets;
  - audio stings;
  - station ID and a recap every ~10 stories;
  - a pacing dial (chatty / balanced / rare).
- **Conversation:**
  - streamed answers;
  - voice-activity barge-in (L);
  - a clarify action;
  - `previous_response_id` continuity (needs `store: true`);
  - more local commands ("repeat", "how far", "what was that place called").
- **Personalization:**
  - implicit signals;
  - "not now" vs "never";
  - trip journal.
- **Offline:**
  - area cache kept on disk, with the corridor fetched ahead;
  - degraded mode reading Wikipedia extracts with on-device speech;
  - network-aware scheduler.
- **Battery:**
  - activity-transition-driven GPS;
  - scheduler runs on events instead of a fixed tick;
  - TTS cache.
- **Social:**
  - story share card;
  - GPX/KML export;
  - "send to a friend at this spot" link.
- **Accessibility:**
  - TalkBack semantics and haptics;
  - clock-face directions;
  - adjustable speech speed.
- **Family:**
  - kids host;
  - back-seat quiz show.
- **Multilingual:**
  - local-name pronunciation;
  - phrase of the place;
  - "hear that in German" for learners.
- **Android:**
  - media3 session;
  - Android Auto;
  - Quick Settings tile / App Actions;
  - auto-start on car Bluetooth;
  - proper ducking for navigation prompts.
- **Trust:**
  - claim-level confidence (`documented | disputed | legend`);
  - score explanation;
  - all citations;
  - freshness stamps.
- **Cost:**
  - a cost meter;
  - a smaller model for short bumpers.
