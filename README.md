# GPS Radio — Interactive Location-Based Radio

An audio-first, location-aware mobile app that turns the user's surroundings into a continuously generated, conversational radio program: it narrates nearby history, stories and places, and lets the user interrupt with natural-language questions at any time.

## Specifications

| Doc | Markdown | Original |
|---|---|---|
| A. Product Requirements & Concept of Operations | [docs/A_Product_Requirements_ConOps.md](docs/A_Product_Requirements_ConOps.md) | [.docx](docs/source/A_Product_Requirements_Concept_of_Operations_v0.2.docx) |
| B. System Design, Architecture & Internal Interface Specification | [docs/B_System_Design_Architecture.md](docs/B_System_Design_Architecture.md) | [.docx](docs/source/B_System_Design_Architecture_Internal_Specification_v0.2.docx) |

The `.docx` files are the source of truth; the Markdown versions are converted copies for easy reading and diffing.

**Architecture decision:** the MVP is app-only, with no backend — see [docs/C_Decision_App_Only_Architecture.md](docs/C_Decision_App_Only_Architecture.md).

## Project layout

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin (JVM) logic, unit-tested without Android: location processing and travel-mode detection, area discovery (Wikipedia + OpenStreetMap), editorial ranker, OpenAI client, narration/conversation agent, session orchestrator and state machine. |
| `app/` | Android app (Kotlin, Jetpack Compose): foreground service with adaptive GPS, audio playback with focus handling, push-to-talk recording, encrypted settings, UI. |

## Getting the app

Every push builds a debug APK on GitHub Actions (**Actions → Android build → Artifacts → `gpsradio-debug-apk`**). Install it on an Android 8.0+ phone (allow installing from unknown sources).

On first launch, paste your own OpenAI API key. It is stored encrypted on the phone only and is never part of this repository. A dedicated key with a monthly spending limit is recommended.

## Building locally

Requires JDK 17+ and the Android SDK (API 35).

```sh
./gradlew :core:test            # unit tests for the core logic
./gradlew :app:assembleDebug    # APK in app/build/outputs/apk/debug/
```

The core module also builds standalone without the Android SDK: `./gradlew -p core test`.

## Using it

- **Play** starts the radio (location permission required). It listens quietly and speaks when something nearby crosses the editorial threshold.
- **Hold the mic** to interrupt and ask anything ("tell me more", "is that true?", "where is it?", "anything about WWII?", "continue in Russian"). Or type.
- **Skip / Repeat / Pause / What's nearby?** controls; tap any item in the **Nearby** tab to hear about it.
- Mode chips override walk/drive/still detection. Settings: language (or Auto), interests, model names, voice.
