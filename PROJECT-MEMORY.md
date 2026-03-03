# Project Memory (Primary)

## 1) Project Goal
- Graduation project: Android intelligent digital-human voice interaction system.
- Delivery strategy: keep internship project interaction style, but replace paid/vendor speech stack with open-source offline stack.

## 2) Repository Mapping
- Active development repo: `D:\AndroidStudioProjects\virhuman`.
- Legacy reference repo: `D:\AndroidStudioProjects\vhuman\ztjyaimetahumant` (for old business flow and interaction patterns).
- Important: avoid confusing the two paths in future sessions.

## 3) Current Implemented Demo (2026-02-28)
- App flow: `LoginActivity -> MainActivity`.
- Main page functions:
  - STT button: start/stop recognition, streaming subtitle updates.
  - TTS button: play preset Chinese text.
- Speech stack:
  - STT: Sherpa OfflineRecognizer (SenseVoice model).
  - TTS: Sherpa OfflineTts (VITS/Melo model assets).

## 4) Major Changes Completed
- Replaced platform `SpeechRecognizer/TextToSpeech` route with Sherpa offline engines.
- Fixed historical crashes around login->main and speech init timing.
- Added STT auto-stop behaviors and UI stop-state sync (`onStopped` + toast).
- Tuned STT end-detection heuristics multiple rounds:
  - adaptive threshold,
  - silence timeout,
  - stable-text timeout,
  - max utterance timeout,
  - no-speech timeout.
- Simplified repo to core demo:
  - removed legacy pages (`Bind/Download/Setting/Splash`),
  - removed Android native STT/TTS engine classes,
  - trimmed unused strings/dependencies/assets.

## 5) Known User Preference / Constraints
- Core functionality first, minimal architecture complexity.
- Keep behavior close to prior internship project where practical.
- Speech tuning is real-device driven (user device: Redmi Note 11T Pro, CN environment).

## 6) Session Bootstrap Rules
- On every new Codex session in this repo:
  1. Read `AGENTS.md`.
  2. Read `PROJECT-MEMORY.md`.
  3. If behavior questions arise, compare with `D:\AndroidStudioProjects\vhuman\ztjyaimetahumant`.
- If user provides new logs (`error.txt`), treat logs as source of truth and update this memory after fixes.

## 7) Latest Session Notes (Day 2)
- User confirmed next priority: implement AI dialogue chain first (before device-id/binding redesign).
- Explicit requirement: do NOT use OpenAI route for now; follow old project AI approach.
- Old-project AI path reviewed from `D:\AndroidStudioProjects\vhuman\ztjyaimetahumant`:
  - `ASR final -> chatWithAli()`
  - `DashScopeManager.streamCall()` for streaming output
  - `ChatSession` / `StreamingTextProcessor` for incremental text + TTS feed.
- Next implementation target in `virhuman`:
  - Keep current Sherpa STT/TTS.
  - Add DashScope-style AI manager and connect `STT -> AI -> TTS` end-to-end.

## 8) Latest Session Notes (Day 3)
- AI chain implemented in active repo (`virhuman`) based on old-project pattern:
  - `STT final -> DashScope stream call -> subtitle updates -> TTS`.
- User provided DashScope credentials; wired via `BuildConfig` fields:
  - `AI_APP_ID`
  - `AI_API_KEY`
- Added operational logs for debugging streaming and speech timing:
  - `MainVoiceChain`: `AI回复(chunk/merged/final)`, `TTS播报(segment)`.
  - `SherpaTts`: `TTS播报(text/samples/rate)`.
- Root-cause learned from `logcat.txt`:
  - AI is truly streaming.
  - perceived lag mainly came from TTS segmentation + synth/play scheduling.
- TTS stabilization updates completed:
  - playback rate fixed to `1.0` (no playback stretch),
  - model `lengthScale` tuned to `1.08` for natural speed,
  - silent warmup synthesis on startup (`generate("你好")`, no playback),
  - sentence chunk playback queue (synthesis/playing decoupled),
  - punctuation-only chunk boundary with minimal short-sentence guard.
- User validation status:
  - current effect accepted for now ("效果不赖").

## 9) Next Session Starting Point
- Continue from current stable baseline; do not revert to Android system STT/TTS.
- Keep referencing old project `D:\AndroidStudioProjects\vhuman\ztjyaimetahumant` for interaction logic only.
- If continuity issues reappear, tune in this order:
  1. `minSpeakChars` in `MainActivity`.
  2. `lengthScale` in `SherpaTtsEngine`.
  3. model replacement evaluation (speed vs quality A/B).
