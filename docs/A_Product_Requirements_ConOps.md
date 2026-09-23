# Interactive Location-Based Radio

Product Requirements & Concept of Operations

Working specification • Version 0.4 (living document) • 23 September 2026

> This Markdown file is the maintained spec. `source/A_Product_Requirements_Concept_of_Operations_v0.2.docx` is the original snapshot. Changes since v0.2: app-only MVP with the user's own key (§17), preference memory (§14), photos and map (§15), activity-aware programming (§16), roadmap (§18), guide personality and humour (§19), refining questions and story offers (§20), natural voice conversation (§21), favourites and sharing (§22), distribution (§23). v0.4: Russian default language (§13), built-in key (§17), pacing dial and non-stop radio (§24), location-synced playback (§25).

## 1. Product Vision

The product is an audio-first, location-aware companion that turns the physical world around a user into a continuously generated, conversational radio program. It proactively tells the user what is interesting nearby, explains local history and unusual facts, recommends things worth seeing, and allows the user to interrupt at any time with natural-language questions.

The defining experience is not a conventional tour guide or chatbot. It is a personal radio station whose programming is selected from the user's current surroundings and can branch into an interactive conversation.

## 2. Product Goals

- Make unfamiliar places immediately interesting without requiring the user to search, type, or plan.

- Provide short, engaging, location-relevant narration while walking, driving, or exploring.

- Allow seamless follow-up conversation: 'tell me more', 'is that true?', 'where is it?', 'what else is nearby?', or arbitrary questions.

- Balance proactive narration with silence; speak only when there is sufficiently valuable new content.

- Prefer specific stories, historical context, unusual facts, nature, architecture, culture, and lesser-known discoveries over generic tourist lists.

- Ground factual and current claims in external information sources rather than relying solely on model memory.

- Adapt over time to user interests, skips, follow-up questions, travel mode, and previously heard content.

## 3. Target Usage Modes

| Mode | Typical behavior |
|---|---|
| Walking | Short-range discoveries; buildings, streets, viewpoints, local history; frequent opportunities for user questions. |
| Driving | Longer look-ahead radius; fewer interruptions; narration selected for places along or near the direction of travel. |
| Stationary exploration | Deeper stories and recommendations around the current location; user can ask for themes or destinations. |
| Background radio | Hands-free passive listening with occasional relevant stories and minimal interaction requirement. |

## 4. Core User Experience

- User opens the app and grants location and audio permissions.

- The app determines current location, movement state, speed, and optionally heading.

- The system discovers and ranks nearby points of interest, stories, historical events, natural features, and other relevant content.

- When a candidate exceeds the editorial threshold, the system generates a concise spoken segment.

- The user may interrupt or speak at any time. The system answers in the context of the current story, location, and conversation.

- After the conversational branch ends, the system returns naturally to location-based radio mode.

- As the user moves, new candidates are discovered while previously told or substantially similar stories are suppressed.

## 5. Functional Requirements

| ID | Capability | Requirement |
|---|---|---|
| PR-01 | Location awareness | Obtain current user location with user permission and update it at a frequency appropriate to travel mode. |
| PR-02 | Nearby discovery | Identify relevant places, landmarks, geographic features, historical sites, cultural objects, and other noteworthy entities around the user. |
| PR-03 | Fact and story discovery | Retrieve useful historical context, unusual facts, local stories, and current practical information when relevant. |
| PR-04 | Editorial selection | Rank candidate stories using relevance, proximity, direction, novelty, interest, confidence, and repetition history. |
| PR-05 | Proactive narration | Initiate audio segments without requiring a question when sufficiently interesting new material is available. |
| PR-06 | Conversational interruption | Allow the user to interrupt narration and ask a follow-up or unrelated question. |
| PR-07 | Context retention | Resolve references such as 'that place', 'the second one', 'there', and 'tell me more' using conversation and location context. |
| PR-08 | Return to radio | Resume proactive location programming after a conversational branch ends. |
| PR-09 | Recommendations | Answer requests such as 'what should I see around here?' and prioritize practical, nearby options. |
| PR-10 | Navigation handoff | Provide distance/direction and, where integrated, launch or request routing to a selected destination. |
| PR-11 | Personalization | Adjust content selection using explicit preferences and observed interactions such as follow-ups, skips, and topic choices. |
| PR-12 | History suppression | Avoid repeating the same place, fact, or substantially equivalent story unless requested. |
| PR-13 | Travel-mode adaptation | Adapt content density, radius, and narration length for walking, driving, and stationary use. |
| PR-14 | Source grounding | Use authoritative or credible external sources for specific historical, geographic, and current claims when needed. |
| PR-15 | Uncertainty handling | Distinguish documented fact, disputed interpretation, folklore, and legend when material is uncertain. |

## 6. Editorial Behavior

The product should behave more like an intelligent radio editor than a continuous notification system. Candidate content should be scored before airtime.

- Prefer a compelling specific story over a generic description.

- Do not narrate every nearby POI.

- Do not repeat facts simply because the user remains near the same place.

- Use silence when no candidate is sufficiently interesting.

- Start with approximately 20–60 second segments; deepen only when the user engages.

- Prioritize information visible or relevant now: a nearby building, mountain, lake, historical site, neighborhood, event location, or route.

- For driving, avoid instructions or interactions that demand visual attention.

- Separate established history from local legend and say when evidence is uncertain.

## 7. Conversational Requirements

| User utterance | Expected behavior |
|---|---|
| Tell me more. | Continue the active story with greater depth; do not restart it. |
| Is that actually true? | Verify or qualify the preceding claim and explain evidence/uncertainty. |
| Where is it? | Resolve the active entity and provide relative location/distance. |
| What else is nearby? | Refresh/rerank nearby candidates, excluding recently covered material. |
| Anything about WWII? | Apply a thematic filter to nearby discovery and narration. |
| Skip. | Stop the current segment and negatively weight that item/topic for the immediate session. |
| Continue. | Return from conversational mode to proactive radio programming. |

## 8. Content Categories

- History and historical events

- Local legends and folklore, clearly identified as such

- Architecture and notable buildings

- Nature, geology, mountains, lakes, rivers, forests and viewpoints

- Art, culture, literature and notable residents

- Industrial, scientific and technological history

- War and political history where locally relevant

- Food traditions and local products

- Unusual or obscure facts

- Practical nearby attractions and things worth seeing

- Time-sensitive information such as opening status when specifically useful and verifiable

## 9. Non-Functional Product Requirements

| Area | Target |
|---|---|
| Latency | Follow-up conversation should feel immediate; target first audible response within ~1–2 seconds where network conditions permit. |
| Reliability | Gracefully degrade when web/places sources fail; never fabricate a result to fill silence. |
| Battery | Use adaptive location updates and caching rather than continuous high-accuracy GPS when unnecessary. |
| Privacy | Location collection is permission-based, minimized, and not retained beyond product needs without explicit user consent. |
| Safety | Driving mode must be audio-first and avoid requiring screen interaction. |
| Cost | Cache area research and reuse structured content; avoid repeated research calls for unchanged surroundings. |
| Accessibility | Support spoken interaction plus text transcript; controls usable without fine motor interaction. |

## 10. MVP Scope

The MVP should prove the core experience rather than build a complete travel platform.

- Android application with foreground location, running app-only (no backend) with the user's own OpenAI key (see §17).

- Walking/driving detection or manual mode selection.

- Nearby discovery using a places/geographic source.

- OpenAI-generated short narration based on grounded place/fact context.

- Speech playback.

- Push-to-talk or tap-to-talk conversational follow-up.

- Session memory of active topic and already narrated items.

- Basic user interests (e.g., history, nature, architecture, culture).

- Simple controls: play/pause, ask, skip, repeat, and 'what is nearby?'.

- Remembered listener preferences across sessions (§14).

- A photo of the place being described and a map of it (§15).

Deferred from MVP: social features, user-generated tours, offline full operation, sophisticated route planning, commercial bookings, AR/camera recognition, and long-term collaborative filtering.

## 11. Concept of Operations Example

A user drives through a rural area with the app playing in the background. The system detects an approaching lake and several historical candidates. It selects one high-value story and says that the lake became associated with a wartime treasure legend. The user interrupts: 'Is the treasure story actually true?' The system verifies the distinction between documented events and legend, answers, and accepts a second question: 'Can I walk there?' After providing practical context, the user says 'continue'. The app returns to radio mode and remains silent until another sufficiently interesting location enters the editorial window.

## 12. Product Success Criteria

- Users can start listening with minimal setup and receive relevant content without issuing a query.

- A majority of narrated segments are judged relevant to the user's actual surroundings.

- Follow-up references are resolved correctly without requiring the user to restate the place or story.

- The system rarely repeats content during a trip.

- Factual hallucinations are minimized through grounding and uncertainty labeling.

- The app remains useful when the user never looks at the screen.

- Users can move naturally between passive listening and active conversation.

## 13. Language Selection and Multilingual Operation

The narration and conversational language is user-selectable and must not be hard-coded. During onboarding and in Settings, the user can choose a default language or select Auto, which follows the device/application language where supported.

- **Default: Russian (ru-RU).** A fresh install narrates and converses in Russian; Auto and every other language remain one tap away in Settings.

- The selected default language controls generated narration, conversational responses, speech recognition configuration, and localized UI where available.

- The user may temporarily switch language during a session by voice or UI, for example: 'Continue in Russian'. A temporary session change does not alter the stored default unless the user explicitly requests that change.

- Research and source discovery are language-independent. The system may use credible sources in local or other languages and synthesize the result in the user's selected narration language.

- Names of places and culturally significant terms should preserve useful original-language forms when appropriate, with pronunciation or translation supplied when it improves comprehension.

- Language preferences are retained as user settings and can be changed at any time.

| ID | Capability | Requirement |
|---|---|---|
| PR-16 | Selectable default language | Allow the user to select a supported default language or Auto/device language. |
| PR-17 | Session language switching | Allow temporary language changes during an active session without changing the persistent default. |
| PR-18 | Cross-language research | Allow research in source languages different from the narration language and synthesize results into the selected language. |

## 14. Listener Memory and Personalization

The listener can tell the radio what they like and how they want it to talk, in plain speech, at any time. The system turns durable preferences into a small profile, stores it on the device, and uses it in every later session.

- Examples: "I love castles", "no war stories, please", "keep the stories short", "we travel with two kids", "remember that I'm vegetarian".
- Only durable preferences are stored. One-off requests such as "tell me more about this one" are not.
- The radio briefly confirms when it remembers something ("Got it, shorter stories from now on").
- The listener can say "forget that I like churches". In Settings they can also see every remembered item, delete any of them, or clear all of them.
- Remembered likes and avoids affect both *what* is chosen (editorial ranking) and *how* it is told (narration style).
- Memory stays on the device and is never backed up or synced in the MVP.

| ID | Capability | Requirement |
|---|---|---|
| PR-19 | Learn preferences from conversation | Extract durable likes, avoids, style wishes and relevant personal context from what the listener says. |
| PR-20 | Persist and reuse | Store the profile on the device and apply it to ranking and to all generated narration and answers in later sessions. |
| PR-21 | Inspect and forget | Let the listener view, delete and clear remembered items by voice or in Settings. |

## 15. Visual Companion: Photos and Map

The product stays audio-first, and the screen adds useful context when someone looks at it.

- By default the panel shows a map of the place currently being described, with the listener's position. When nothing is on air, it shows the listener's surroundings and the nearby candidates.
- When a real photo of the place exists, it is shown with the place name, and tapping it opens the source.
- Photos must be real: from Wikipedia or Wikimedia Commons, or photos linked from OpenStreetMap. AI-generated pictures of real places are not used, because they would misrepresent the place. Generated illustrations may be considered later only for abstract topics, and must be clearly labelled.
- In driving mode the screen is optional. Nothing requires looking at it.

| ID | Capability | Requirement |
|---|---|---|
| PR-22 | Place map | Show a map with the described place and the listener's position; the map follows the story. |
| PR-23 | Real photos | Show a real, attributed photo of the described place when available; never present generated images as real places. |
| PR-24 | Tap for source | Tapping the photo opens the source article. |

## 16. Activity-Aware Programming

Travel mode determines what is worth telling and how far out to look.

| Mode | Search area | What to prefer | Pacing |
|---|---|---|---|
| Walking | ~1.5 km, weighted to the nearest few hundred metres | Things you can see or reach on foot: facades, plaques, street history, small sights | Frequent, shorter segments. Direction phrased as "on your left" or "ahead". |
| Cycling (planned) | ~3–4 km, ahead-weighted | Route-side sights, viewpoints, rest stops | Medium |
| Driving | ~8 km, shifted ahead along the direction of travel (look-ahead) | **Visible from the road**: mountains, lakes, castles, bridges, landmarks. **Worth a stop on this trip**: sights within a short detour, with an offer to navigate there | Sparse (at least 90 s between segments; 12 s in Non-stop, §24), ≤30 s, no screen interaction, silence at junctions |
| Stationary | ~1.5 km | Deeper stories and recommendations | Longer segments on request |

- Mode is detected from GPS speed with hysteresis today. Android activity recognition (walk, cycle, vehicle, still) will be added to detect mode faster and more reliably, and to save battery while still.
- The listener can always override the mode.

| ID | Capability | Requirement |
|---|---|---|
| PR-25 | Activity detection | Detect walking, cycling, driving and stationary states; allow manual override. |
| PR-26 | Mode-specific radius | Use a very local radius when walking and an ahead-looking corridor when driving. |
| PR-27 | Road-trip categories | While driving, favour what is visible from the road and places worth a stop, and offer navigation to them. |
| PR-28 | Mode-specific pacing | Adapt gap, segment length and phrasing to the mode. |

## 17. App-Only Operation (MVP decision)

The MVP runs entirely on the phone and has no backend. The listener can enter their own OpenAI API key, which is stored encrypted on the device and never stored in the repository. Release builds also carry a built-in default key (PR-29a). Nearby places come from Wikipedia and OpenStreetMap, which are free, need no key and give exact coordinates. OpenAI owns the storytelling, conversation, voice, web research and verification. See `C_Decision_App_Only_Architecture.md`.

| ID | Capability | Requirement |
|---|---|---|
| PR-29 | Own key | The user supplies an OpenAI key, which is stored encrypted on the device and can be replaced or removed in Settings. |
| PR-29a | Built-in key (owner decision, 23 Sep 2026) | Release builds carry a default key from the repository secret `OPENAI_API_KEY`, lightly obfuscated, so the app works out of the box. A key the user enters always overrides it. The key is never committed. **Accepted risk:** the APK is public, so the built-in key can be extracted; the owner should cap its spend limit and rotate it if abused. |

## 18. Roadmap Candidates (prioritised)

These come from the product brainstorm (23 Sep 2026) and are ordered by value for effort.

1. Lock-screen, headset and Bluetooth car controls via a media session; this also prepares for Android Auto.
2. Next-story prefetch, so there is no dead air between segments.
3. Streamed, sentence-by-sentence spoken answers, to meet the ~1–2 s response target.
4. A keyless preview using on-device speech before a key is entered. The same path serves as the offline/degraded mode, reading cached facts aloud.
5. Segment formats that make it feel like radio: short "did you know" bumpers, "on this day", quizzes, a local word.
6. Short audio stings and selectable host personalities (documentary, cheeky, kids, late-night).
7. Trust: spoken confidence phrasing ("records show" vs "locals say"), all sources listed, and "why this story?".
8. Road trips: pacing rules, look-ahead corridor, "worth a stop" with one-tap detour (see §16).
9. Implicit personalization: full listens, early skips and follow-up questions adjust interests.
10. Walking mini-tours ("give me 30 minutes"), with an extra chapter on arrival at each stop.

Also considered: hands-free barge-in with voice activity detection, a family quiz mode, trip journal and sharing, export of a trip, a cost meter.

## 19. The Host: A Knowledgeable, Entertaining Guide

OpenAI is responsible for the whole on-air experience: how stories are told, how the host sounds and how conversation feels. The host should sound like a natural, knowledgeable local guide, not an encyclopedia.

- Every segment blends a **story**, one **memorable fun fact**, and the **historical and cultural context** that makes the place matter.
- Each segment opens with a hook: the most surprising, specific or human detail.
- **Content-related humour is welcome**: a wry aside, a playful comparison, a gentle pun. Humour must never add facts, and there are no jokes about tragedies, victims, war or disasters.
- Legends and disputed claims are labelled as such.
- The listener chooses a **host style**: *Witty guide* (default), *Documentary*, *Family & kids*, or *Late-night chill*. The style shapes the wording and the voice's delivery.

| ID | Capability | Requirement |
|---|---|---|
| PR-30 | Guide persona | Narration and answers mix story, fun fact and context in a natural spoken style. |
| PR-31 | Humour with guardrails | Light, content-related humour; never on tragic topics; never at the expense of accuracy. |
| PR-32 | Host styles | Selectable host personality that applies to text and voice. |

## 20. Refining Questions and Story Offers

The host occasionally asks the listener short questions, as a real guide would:

- **Story offers.** For a rich story, the host may first give a one or two sentence teaser and ask "Want the full story?". A yes tells it. A no skips it without asking again, and without penalising the topic. No answer means "not now".
- **Trip question.** The first time the listener starts driving in a session, the host asks where they're heading. The answer becomes trip context that shapes later stories, for example connecting places to the destination.
- **Clarifying questions.** In conversation the host may ask one short clarifying question when it genuinely helps, never repeatedly.
- Answers work by voice (hands-free when natural voice is on), by a spoken yes or no, or by on-screen buttons.

| ID | Capability | Requirement |
|---|---|---|
| PR-33 | Story offers | Offer rich stories as a teaser plus question; honour yes, no and silence. |
| PR-34 | Trip context | Ask drivers about their trip once per session and use the answer. |
| PR-35 | Clarifying questions | Allow at most one short refining question when useful. |

## 21. Natural Voice Conversation

Conversation should feel like talking to a person, as in ChatGPT's voice mode.

- **Tap the mic once** to talk hands-free: the host hears when you stop speaking, replies in a natural voice, and **can be interrupted mid-sentence**. The conversation closes by itself after a short silence, or when you say "continue", tap again or say "back to the radio".
- The live host can **search the web**, **control the radio** (skip, pause, language, theme, navigate, save a place), **remember preferences**, and **note trip details**.
- If natural voice is unavailable (turned off, no mic permission, connection failure), the classic path is used: hold the mic, transcribe, answer, speak.

| ID | Capability | Requirement |
|---|---|---|
| PR-36 | Hands-free voice | Speech-to-speech conversation with automatic turn-taking and interruption. |
| PR-37 | Voice tools | The live host can search, control the radio, remember and set trip context. |
| PR-38 | Fallback | Classic push-to-talk remains available and is used automatically when live voice fails. |

## 22. Favourites and Sharing

- **Star** any place from the Now card, the Nearby list, the driving screen, or by voice ("save this for later"). Starred places keep their name, summary, photo and links, even after leaving the area.
- The **Saved** tab lists starred places, each with Share, Navigate and Remove.
- **Share** uses the standard Android share sheet: place name, a short summary, a Wikipedia link and a map link.

| ID | Capability | Requirement |
|---|---|---|
| PR-39 | Favourites | Star and un-star places; persist them; review them later. |
| PR-40 | Share | Share a place through the system share sheet with a summary and links. |

## 23. Distribution

- Every push publishes the newest APK at a permanent direct link: `https://github.com/apartsin/gpsradio/releases/download/latest/gpsradio.apk`.
- All builds share one signing key, so a new build installs as an update over the old one.

## 24. Pacing Dial and Non-stop Radio

The listener chooses how talkative the radio is: **Chatty**, **Balanced** (default), **Rare** or **Non-stop**.

- Between place stories the radio can run short formats: a "did you know" bumper, a quiz (answer out loud or wait for the reveal), "on this day", a station ident with a recap, and stories about the town or region, one angle at a time. All of them stay grounded in sources.
- **Non-stop** never goes quiet while there is anything left to tell. When no strong story is nearby it falls back, in order, to weaker unheard places, stories about the area, the short formats, and then a wider search radius. Nothing is repeated. It also works while driving: segments stay ≤30 s, with about 12 s between them, and the radio is silent at junctions.

| ID | Capability | Requirement |
|---|---|---|
| PR-41 | Pacing dial | Chatty / Balanced / Rare / Non-stop, persisted in Settings. |
| PR-42 | Short formats | Bumper, quiz, on this day, station ID and area stories fill gaps without repeating content. |
| PR-43 | Non-stop | Continuous, autonomous programme that fetches more location content when the nearby stock runs out. |

## 25. Location-Synced Playback

Stories must match where the listener actually is, especially at driving speed.

- The app asks for precise location and, while driving, takes a fresh fix about every 2 s with no batching.
- Before a story plays, the radio projects the position forward by the measured preparation time. It drops a story whose place has already been passed (beyond a tolerance) instead of telling it late.
- While driving, narration avoids exact distances ("coming up on your left", "in about a minute").

| ID | Capability | Requirement |
|---|---|---|
| PR-44 | Precise location | Request fine location; explain in the UI when only approximate location is granted. |
| PR-45 | Playback sync | Compensate for preparation latency and skip stories that are no longer ahead. |

## 26. Out-of-Credit Notice

If the OpenAI key runs out of credit, the listener is told right away and clearly:
- a status message with an "Add key" button;
- a phone notification, even when the app is in the background;
- a short spoken notice once.

The radio keeps going with short on-device notes. The warning clears once credit is back or a new key is saved.

| ID | Capability | Requirement |
|---|---|---|
| PR-46 | Out-of-credit notice | Detect exhausted OpenAI credit, notify the listener on screen, by notification and by voice, and offer to add a key. |
