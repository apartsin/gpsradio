# GPS Radio — Interactive Location-Based Radio

An audio-first, location-aware mobile app that turns the user's surroundings into a continuously generated, conversational radio program: it narrates nearby history, stories and places, and lets the user interrupt with natural-language questions at any time.

## Specifications

| Doc | Markdown | Original |
|---|---|---|
| A. Product Requirements & Concept of Operations | [docs/A_Product_Requirements_ConOps.md](docs/A_Product_Requirements_ConOps.md) | [.docx](docs/source/A_Product_Requirements_Concept_of_Operations_v0.2.docx) |
| B. System Design, Architecture & Internal Interface Specification | [docs/B_System_Design_Architecture.md](docs/B_System_Design_Architecture.md) | [.docx](docs/source/B_System_Design_Architecture_Internal_Specification_v0.2.docx) |

The `.docx` files are the source of truth; the Markdown versions are converted copies for easy reading and diffing.

## Planned MVP stack (per spec B)

- Native Android client — Kotlin / Jetpack Compose, Fused Location Provider
- Small backend API (Node.js/TypeScript or Python/FastAPI) holding provider credentials
- LLM-driven research, narration and conversation; places/geographic provider for grounded POI discovery
