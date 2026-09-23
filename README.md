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

**📱 Download: [gpsradio.apk (latest tested build)](https://github.com/apartsin/gpsradio/releases/download/latest/gpsradio.apk)**

Open that link on an Android 8.0+ phone, allow installing from your browser when asked, and install.

| Link | What it is |
|---|---|
| [latest](https://github.com/apartsin/gpsradio/releases/download/latest/gpsradio.apk) | The newest build that passed unit, UI and emulator tests. It only changes when a new build has passed, so it keeps working while the next one is built and tested. |
| [previous](https://github.com/apartsin/gpsradio/releases/download/previous/gpsradio.apk) | The tested build before `latest`, to go back if a new build misbehaves. |
| [all builds](https://github.com/apartsin/gpsradio/releases) | Every push gets its own release `v0.5.<build>` (the newest 15 are kept). It is marked *pre-release (testing)* until its tests pass. |

- Any GPS Radio build installs over any other, newer or older, and keeps your settings: the signing key and version code are the same for all builds.
- The installed version is shown at the bottom of **Settings**, for example "GPS Radio 0.5.142 · build a1b2c3d · 2026-09-23".

The app ships with a built-in OpenAI key (the owner's choice), so it works out of the box. You can enter your own key in Settings. It is stored encrypted on the phone only and is never part of this repository. If the key runs out of credit, the app tells you and asks for another key.

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
