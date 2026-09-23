# Interactive Location-Based Radio

Product Requirements & Concept of Operations

Working specification • Version 0.1 • 23 September 2026

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

- Android application with foreground location.

- Walking/driving detection or manual mode selection.

- Nearby discovery using a places/geographic source.

- OpenAI-generated short narration based on grounded place/fact context.

- Speech playback.

- Push-to-talk or tap-to-talk conversational follow-up.

- Session memory of active topic and already narrated items.

- Basic user interests (e.g., history, nature, architecture, culture).

- Simple controls: play/pause, ask, skip, repeat, and 'what is nearby?'.

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
