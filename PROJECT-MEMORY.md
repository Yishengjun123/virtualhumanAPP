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
