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

## 27. Versions and In-App Updates

GPS Radio is not distributed through a store:
- Settings shows the installed version.
- The app checks for new tested builds and installs them after one tap.
- It never prompts while you are driving.
- An older build can always be reinstalled over a newer one without losing settings.

| ID | Capability | Requirement |
|---|---|---|
| PR-47 | Version info | Show version, build and date in Settings. |
| PR-48 | Self-update | Check for tested builds (auto, at most every 6 h, and on demand), download with integrity check, install via the system installer. |
| PR-49 | Safe releases | "latest" moves only after all tests pass; "previous" and per-build releases stay available. |

## 28. Photo Tips and Drive-By Detours

- **Photo tips.** The radio points out photogenic scenery: viewpoints, waterfalls, lakes, castles, bridges and lighthouses.
  - It says what makes the shot and where to stand, plus one light tip based on the sun's actual position (golden hour, backlight, side light).
  - Walking: spots within about 600 m. Driving: only designated viewpoints just off the road ahead, with advice to pull over. Never a photo at the wheel.
  - At most one tip every 15 minutes. The Nearby list marks photo spots with a camera icon, and "where's a good photo?" works in conversation.
- **Drive-by detours.** While driving, "worth a stop" places a few minutes off the road ahead are suggested as small diversions.
  - After such a story the host asks "Want me to navigate there?". A spoken or tapped yes opens the maps app.
  - The driving screen shows the best detour ahead with its minutes and a large Navigate button.

| ID | Capability | Requirement |
|---|---|---|
| PR-50 | Photo tips | Suggest nearby/upcoming photo spots with a light tip; driving: viewpoints only, stop first. |
| PR-51 | Drive-by detours | Offer worth-a-stop places within ~15 min detour with a one-tap/one-word navigation handoff. |

## 29. More to Discover: Food & Shops, Film Locations, Historical Events, Jewish Heritage & Israel

- **Places to eat, drink or shop that are worth remembering.** Only notable ones: those with a Wikipedia/Wikidata entry, heritage status or a historic tag, not every café. The host says what makes them special: history, a famous dish or product, a famous guest. It never invents opening hours, prices, menus or ratings.
- **Film and TV locations** (Wikidata "filming location"), for example "Filming location of Schlosshotel Orth (1996)".
- **Historical events that happened here** (Wikidata events with a date and coordinates, told from their Wikipedia article), opening with the year.
- **Jewish heritage and connections to Israel.** It is a named feature and a topic chip ("Jewish & Israel"), on by default for new installs. It covers:
  - synagogues and Jewish cemeteries;
  - Stolpersteine, grouped per street, with the names they bear;
  - places connected to Jewish history in Wikipedia (communities, the Holocaust, Israel);
  - places near you that are the birthplace of notable Jewish or Israeli people (Wikidata).

  The host is warm about living heritage and dignified about persecution, with no humour.
- **No knowledge quizzes.** The host never tests the listener. Once per session, after a few stories and while it knows little about the listener's taste, it may ask what they would like more of (history, nature, food, film locations, Jewish heritage…). The answer is remembered.

| ID | Capability | Requirement |
|---|---|---|
| PR-52 | Eat & shop | Notable places to eat, drink and shop; no invented hours, prices, menus or ratings. |
| PR-53 | Film locations | Films and shows shot nearby, from Wikidata. |
| PR-54 | Historical events | Dated events at nearby coordinates, grounded in their article. |
| PR-55 | Jewish heritage & Israel | Synagogues, cemeteries, Stolpersteine, Jewish history and Israeli/Jewish people connected to nearby places. |
| PR-56 | No quizzes | Knowledge quizzes are off; one optional preference question per session. |

## 30. Events Today Nearby

"If you're back by the lake at eight tonight, there's an open-air jazz concert."
- About every 3 hours per town, the app looks for public events happening now or within the next ~10 hours that a visitor would enjoy: concerts and live music, festivals, markets, fireworks, parades, open-air cinema, theatre, special exhibitions, notable games and fairs.
- **Never** classes or courses (yoga, fitness, dance, cooking…), workshops, lectures, meetings, regular services or private events.
- Every event needs a source link.
- The host mentions new events promptly and once, as an invitation ("if you're back at…"), with the time and place. It never invents prices, tickets or line-ups.
- A phone notification lists them.
- The Nearby tab shows "Today nearby" with times, venues and links.
- A Settings switch, "Events today nearby", is on by default.

| ID | Capability | Requirement |
|---|---|---|
| PR-57 | Events today | Find visitor-worthy events today nearby (with sources), announce once, notify, list with links; exclude classes/meetings. |

## 31. Opening Hours, Admission and What a Visit Involves

- For places you might actually visit, the host checks today's opening hours and admission once per day and reports them briefly, with where they came from ("open until five, eight euros for adults, according to their website"). Or it says they couldn't be confirmed. It never guesses. This covers detours, places to eat or shop, and sights that usually have tickets (museums, castles, zoos, caves, towers).
- Sources, in order:
  - a web check with the official site first;
  - the OpenStreetMap `opening_hours` and `fee`/`charge` tags, evaluated on the phone for today.
- **Detours** also say what the stop involves:
  - drive-by, short stop, proper visit, walk or hike;
  - how long to spend;
  - whether there's a walk, and how hard it is ("15 min uphill on gravel");
  - what to expect.

  The detour card shows it in one line, e.g. "open 10:00–17:00 · adults €8 · ~45 min visit · easy walk".

| ID | Capability | Requirement |
|---|---|---|
| PR-58 | Hours & fees | Report today's hours/closure and admission from a checked source; otherwise say unconfirmed. |
| PR-59 | Detour details | Visit type, time to spend, walking effort and what to expect for suggested detours. |

## 32. Voice First, in the Listener's Language

- **Voice is the main channel.** Everything the listener needs to know is spoken; the screen repeats and adds detail, but is never the only place it appears. Spoken today:
  - stories and answers;
  - offers ("want the full story?", "want me to navigate there?");
  - events today;
  - hours and fees, and detour details;
  - photo tips;
  - "out of credit";
  - "offline / OpenAI unreachable: short notes for now";
  - "couldn't answer that";
  - "questions need a connection / a key";
  - "can't plan a tour yet";
  - "tour ended".
- **Language.** The host always speaks the selected language (Russian by default), even when the sources are in German, English or anything else. It translates the facts and keeps original place names.
- **Notices without a model** are hand-written in Russian, English, Hebrew, German, Spanish and French, and read with the phone's own voice when OpenAI can't be used.
- **Screen-only exceptions:** the app-update banner (never while driving) and settings.

| ID | Capability | Requirement |
|---|---|---|
| PR-60 | Voice first | Every listener-relevant notice is spoken; the screen is secondary. |
| PR-61 | Language | Narration, answers and notices in the selected language regardless of the source language. |

## 33. Always Listening (Natural Two-Way Conversation)

Like ChatGPT voice mode, the mic can stay open, so the listener just talks at any time, even in the middle of a story.
- The story stops, the host answers naturally, and the listener can interrupt the host too.
- After a short quiet spell the radio carries on, and the mic stays open.
- The host always knows which story is playing, so "tell me more about that" works.
- **Mic switch:** a mic icon in the radio screen's top bar and a "Mic off / Mic on" button in the notification (lock screen, car). It is also in Settings ("Always listening", on by default with the natural voice).
- **Privacy and data:** the phone decides what is speech and sends only that (with half a second before it), so silence and the radio itself are never uploaded. While a story plays, the listener has to be clearly louder than the story. Android shows its microphone indicator while the mic is open.
- If the voice connection keeps failing, always listening pauses (with a notice), and tap-to-talk still works.

| ID | Capability | Requirement |
|---|---|---|
| PR-62 | Always listening | Open mic with natural barge-in over stories and the host; switchable in one tap (screen, notification, Settings). |
| PR-63 | Speech-only upload | Only detected speech is sent; the radio's own audio must not trigger the host. |

### 32.1 One output language (owner decision, 23 Sep 2026)

Once the output language is set, everything the listener hears or reads as content is in it. OpenAI translates or regenerates whatever the source language was. Covered:
- stories, fillers and answers, including the live voice;
- event titles and descriptions, and admission / walking notes / what to expect from the web checks;
- the detour card summary;
- host questions and walking-tour directions;
- spoken notices.

If something can't be put into the listener's language, it is **left out rather than said in another language**:
- on-device notes (offline) are read only when the source text is already in that language, and other places wait until OpenAI is back;
- a tour direction or host question whose translation failed is skipped.

Proper names (places, bands, venues) keep their original form.

*Not yet translated:* the app's own screen labels and status messages. They are English today; making them Russian is a separate UI localization task.

## 34. Retold, Not Read Out

The host narrates; it never reads a text aloud. Wikipedia, OpenStreetMap, web results and other sources are research material only.

- **Own words.** Every story, answer and short segment is retold in the host's own words, in casual spoken language. That is how people actually talk, in every language: in Russian, for example, relaxed conversational Russian rather than bookish or official. Sentences from a source are never copied, and a translation is never word for word.
- **Spoken shape.**
  - The most interesting part comes first, with one idea per sentence.
  - The host addresses the listener directly and reacts to the facts now and then.
  - Dates and numbers are used only when they matter, rounded when that sounds more natural.
  - Encyclopedia filler (full titles, lists of dates, administrative details) is left out.
  - Grounding is unchanged: every fact still comes from the sources (§ facts rules).
- **Delivery.** Every text-to-speech clip is voiced as spontaneous talk with conversational intonation, never as reading or an announcement. The live voice follows the same rule.
- **Evals.**
  - A story must not share a run of 8 or more consecutive words with its source.
  - A judge must rate it as casual talk rather than an encyclopedia entry.
  - A Russian story must be in a relaxed spoken register and not a word-for-word translation.
- **Exception.** The on-device fallback (offline, or no OpenAI key) has no model to retell with, so it can only read short notes that are already in the listener's language (§32).

## 35. Everything Is About Here

Everything the radio airs on its own is about where the listener is. Nothing airs just to fill time.

| Segment | How it is tied to the location |
|---|---|
| Stories, teasers, arrival notes, "did you know" bumpers, photo tips | Places near the listener or on the road ahead |
| Area stories | The town or region the listener is in |
| Events today | Within reach of the listener |
| Station ID | Recaps only places already heard on this trip |
| **On this day** | **Only if the anniversary happened here:** the event names the town or region, or one of its places is within 100 km. |

- **Country is not enough.** Sharing only the country does not count: a treaty in Vienna is not "on this day" for a listener in Gmunden.
- **No local event, no segment.** If nothing local happened on today's date, the slot is skipped and the next local segment airs instead.
- **Per area.** The check runs once per day and area, so a new town on a drive can have its own anniversary.
- **Framing.** The narration ties the event to the place ("right here in Gmunden…") and never invents a connection.

Answers to the listener's own questions follow the question, wherever it leads.

## 36. A Minimal, Voice-First Main Screen

The main screen has only the essentials:

- **Start / Stop radio:** one big button.
- **Mic open / closed:** one big button.
  - **Open** means just talk at any time: always listening with the natural voice. The first tap asks for the microphone permission.
  - **Closed** means the mic is off.
  - The notification's Mic action does the same.
- **The image / map area:** the photo of the place on air, or the map. Under it are the place name (with the ON AIR light) and one status line. An error that needs the API key links to Settings.
- **Menu (☰):**
  - travel mode: Auto, Walk, Cycle, Drive or Still;
  - Nearby, with events today and walking tours;
  - Saved & journal;
  - Transcript;
  - an available update;
  - Settings.

Driving uses the same screen.

**Removed from the main screen:**
- Pause, Skip, Repeat and Nearby? buttons;
- the ask field;
- the tabs;
- the star, share and navigate buttons;
- the offer and detour cards;
- the separate driving layout.

These are now by voice ("skip", "pause", «следующий», "save this place", "yes / not now", "navigate there"), and the Nearby and Saved pages still have per-place actions. Pause and skip also stay on the notification, lock screen and headset or car buttons (previous = repeat).

**Mic closed means no questions.** The listener can't answer, so the radio asks nothing it would need an answer to (`SessionConfig.canReply`):
- no "want the full story?" teasers (the full story is told);
- no detour offers: a worth-a-stop story describes the detour without offering to navigate;
- no trip or preference questions.

## 37. Real Non-Stop Radio: 50 Angles, Steering and Instant Replies

- **Non-stop by default.** Unless stopped, the stories keep coming. Existing installs move to non-stop once; a pacing chosen after that is kept.
- **The endless loop.** When the nearby places run out, the radio researches the next untold angle for the listener's town, then the region, then the country (landscape and culture angles only at country level).
  - It uses `AngleScout`: the conversation model with web search returns "found / not found", a title and 3–8 sentences of sourced factual notes.
  - The notes are narrated by the normal story prompt, so the grounding, retelling, language and dignity rules apply unchanged.
  - One angle is researched ahead of time, so there is no dead air.
  - At most 24 lookups an hour, each angle once per scope.
  - Angles matching the listener's interests come first; a theme narrows to it; avoided topics are left out; consecutive angles vary.
- **The 50 angles** (`StoryAngle`):
  - History & heritage: origins and the name, turning points, everyday life in the past, work heritage, architecture, castles and defence, religious heritage, Jewish heritage and Israel, war and remembrance, archaeology, borders and rulers, migration and communities, royal links, documented scandals and mysteries, disasters and recovery.
  - People: famous natives, famous visitors and residents, local characters, inventions and firsts, women who shaped the place.
  - Arts & culture: literature, film and TV, music, painting, legends and folklore, festivals and customs, dialect and place names, crafts.
  - Food & drink: signature dishes, wine, beer and spirits, historic cafés and food institutions, food origin stories.
  - Nature: geology, water and spas, mountains and viewpoints, wildlife, ancient trees and gardens, natural phenomena, climate and seasons, protected areas.
  - Modern life & quirks: records, quirky facts, transport history, what the place lives from today, sports, science and the sky, street names, links abroad, hidden gems, then and now.
- **Steering by voice (mic open).** The listener can interrupt at any time; the story stops and the host listens.
  - "Tell me about that church" → `tell_about` a listed place.
  - "Tell me about the local wine / the fish in the lake" → `steer`: the host says a few words ("Ooh, let me dig into that") while the radio researches the request, then the story airs.
  - "Only nature for a while" → theme.
  - "Shorter stories" → a remembered style.
- **Instant replies.** As in ChatGPT voice, the live host is a speech-to-speech model that starts talking about half a second after the listener stops.
  - It opens with a short, varied reaction ("Oh, good one —") and says a brief preamble before any tool.
  - There are no local canned clips: they would talk over the model.
- **Travel mode is always automatic** (speed and activity recognition). It is shown in the top bar and can't be chosen in the menu.

## 38. Tiers, Better Voice and a Continuous Stream

- **Tiers.**
  - Tier 1, headliners: legends, mysteries, firsts, records, quirks, film, natives, visitors, royals, food origins, dishes, turning points, natural phenomena, hidden gems, literature, origins, Jewish heritage.
  - Tier 2, strong: architecture, defence, disasters, music, painting, customs, drinks, historic cafés, water, mountains, wildlife, geology, characters, war memory, then and now, work heritage.
  - Tier 3: the rest.
- **How tiers are used.**
  - An angle matching the listener's interests moves up one tier.
  - The loop starts at the top tier and takes angles in random order within a tier, so each trip starts differently.
  - Order of scopes and tiers: town tier 1 → town tier 2 → region tier 1 → town tier 3 → region tier 2 → country tier 1 → …
- **Only if interesting.** The researcher rates each find 1–5 for a curious visitor, and only 4–5 is told. "Nothing specific here" and dull finds are skipped.
- **In the story prompt.** The same catalogue, headliners first, tells the narrator which angle to pick from a place's facts.
- **Models.**
  - Stories and conversation: `gpt-4.1` (better prose than mini).
  - Web research (angles, events, visit checks, live web answers): `gpt-4.1-mini`, which is fast; its notes are retold by the story model.
  - Live voice: `gpt-realtime`.
  - Existing installs move to the new defaults once.
- **Style (story prompt, "Craft").**
  - Engaging: a hook in under 15 words, one "wow, really?" moment, and an ending on a payoff, not a summary.
  - Dense: every sentence carries a name, number, date, image or cause. No generic praise ("rich history", "nestled", "charming", "boasts", "testament to"…).
  - Clear: spoken sentences of about 15 words or fewer, one idea each, and terms explained.
  - Fun: one witty aside or vivid comparison, never about tragedies.
  - Checked by a live test.
- **A continuous stream.**
  - Non-stop leaves about 2 s between segments (4 s while driving; junctions still mean silence).
  - While a segment plays, the next is prepared: text and voice, a place story or else the next researched story. Researched stories are kept two ahead.
  - While driving, the next story is prepared for where the car will be when it airs, and only for a place still ahead.
- **Fast reaction to movement.**
  - GPS updates every 5 s or 8 m on foot, 3 s by bike, 2 s by car.
  - Prepared stories are re-checked against where the listener actually is before they air.
- **Fast reaction to steering.**
  - The live host answers straight away with a short reaction.
  - Research for a steer runs on the fast model, and the story follows in a few seconds.

## 39. The Listener's Voice Is Always High Quality

The listener's own voice is always sent at full quality: 16-bit PCM at 24 kHz. Russian is the default language, and accurate recognition of it, with foreign place names mixed in and in noisy cars, matters more than the data saved.

- **Nothing may lower it.** No setting, including any future data saver, may downsample it or switch it to telephone codecs (G.711).
- **Data is saved elsewhere.**
  - The mic already sends audio only while the listener speaks (on-device speech gate, §33).
  - Recognition gets the session language as a hint (Russian by default).
  - Any data saver works on the downstream side: story voice format, photos and map tiles.
