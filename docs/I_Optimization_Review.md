# Optimization review: latency, token cost, API calls (23 Sep 2026)

Reviewer: an optimization agent, read-only. Prices are assumed OpenAI list prices; verify before relying on them. The report is condensed below; line references are to the code at review time. The **Status** column tracks follow-up.

## Ranked findings

| # | Finding | Impact | Effort | Status |
|---|---|---|---|---|
| 1 | Paid work continues while PAUSED (discovery, visit web checks, event scout) | up to ~$0.4/h and ~100 HTTP calls/h for nothing | tiny | planned |
| 2 | Visit web checks over-trigger: the regex has no word boundaries ("parking", "Newcastle" match) and fires on every new top candidate or detour | up to 20 searches/h ≈ $0.2–0.5/h; the top driving cost | small | planned |
| 3 | A Realtime session opens for every teaser, detour offer or quiz window | ~$0.02 and ~1 MB upstream per window | small | superseded by always listening (§43): one persistent connection, speech-only upload |
| 4 | TTS is 85–90% of per-segment cost, and it isn't streamed (whole MP3, then a temp file) | ~$0.010 per 40 s story; 3–6 s dead air when not prefetched | medium | planned |
| 5 | Prefetch skips rich candidates (`teaserEligible` instead of `shouldTease`), so nothing is prefetched in Non-stop | 6–12 s "Tuning in…" before most Non-stop segments | tiny | planned |
| 6 | A slow optional Wikidata query can void the whole discovery (one 30 s timeout wraps all sources) | up to 30 s, plus a 60 s retry of silence | small | planned |
| 7 | Discovery blocks the scheduler even when candidates exist | up to 30 s story delay per refresh | tiny | planned |
| 8 | Walking discovery re-fetches overlapping circles every 500 m; corridor cells depend on the heading | ~9–10 HTTP calls per refresh, ~50% avoidable | small | later |
| 9 | 8 s visit wait on the first or changed top candidate | ≤ 8 s dead air per affected story | tiny | planned (wait only for detours, cap 3 s) |
| 10 | Teaser plus full story means two narrations, and the full story isn't prefetched | 6–12 s wait after "yes" | small | later |
| 11 | Cache keys on the event and visit scouts are moot (prefix < 1024 tokens); `cachedTokens` is never logged | none | tiny | planned: debug counter |
| 12 | Walking GPS is 5 s / 8 m HIGH_ACCURACY; the 3 s tick reranks even when paused | battery | tiny | later |

**Not worth doing:** trimming `MAX_FACTS_CHARS`, dropping the `basis` JSON, or moving narration to a nano model. The LLM is under 10% of the cost.

## Cost per hour (estimates)

| Mode | Segments/h | LLM | TTS | Web-search scouts | Realtime | Total |
|---|---|---|---|---|---|---|
| Walking, balanced | 20–30 | $0.03 | $0.25 | ~$0.05 | ≤ $0.15 | $0.3–0.5 |
| Walking, Non-stop | ~60 | $0.06 | $0.60 | ~$0.05 | ~0 | ~$0.7 |
| Driving, balanced | 15–25 | $0.02 | $0.15 | up to $0.5 | $0.1–0.4 | $0.3–1.0 |

**Prompt cache:** verified clean. The narration (≈ 2.3k tokens) and conversation (≈ 1.5k tokens) instructions contain only the language and persona; all per-call data is in `input`.

## Latency chain (walking, cold start)

discovery (≤ 30 s) → tick → visit check wait (≤ 8 s) → narration (3–5 s, not streamed) → TTS (3–6 s, not streamed) → temp file and `MediaPlayer.prepare`. First audio arrives after about 15–50 s; between segments it is ~0 s when prefetched, otherwise 6–12 s.

The biggest latency win is streamed TTS: `response_format: "pcm"` played through the existing `AudioTrack` as bytes arrive, so first audio comes about 0.5–1 s after the request. PCM costs more bandwidth than MP3 (about 110 MB/h in Non-stop walking, versus 20 MB/h), so progressive MP3 is the alternative if data use matters.
