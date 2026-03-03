# Repository Guidelines

## Project Structure & Module Organization
- Current target repo: `D:\AndroidStudioProjects\virhuman`.
- Active app is a simplified Android demo using offline Sherpa:
  - `app/src/main/java/com/example/virhuman/ui/login/LoginActivity.kt`
  - `app/src/main/java/com/example/virhuman/ui/main/MainActivity.kt`
  - `app/src/main/java/com/example/virhuman/ai/asr/SherpaAsrEngine.kt`
  - `app/src/main/java/com/example/virhuman/ai/tts/SherpaTtsEngine.kt`
  - `app/src/main/java/com/example/virhuman/data/SessionStore.kt`
- Models/assets are under `app/src/main/assets/sherpa/{stt,tts}`.
- Legacy reference project (do not edit by default, use for behavior/logic reference):
  - `D:\AndroidStudioProjects\vhuman\ztjyaimetahumant`

## Build, Test, and Development Commands
- `.\gradlew.bat :app:assembleDebug` : build debug APK.
- `.\gradlew.bat :app:installDebug` : install to connected device.
- `.\gradlew.bat :app:clean` : clean app outputs.
- Android Gradle Plugin requires JDK 17 in local Android Studio.

## Coding Style & Naming Conventions
- Kotlin, 4-space indentation, no tabs.
- Keep package names lowercase and class names `UpperCamelCase`.
- Keep XML resources `snake_case` (e.g. `activity_main.xml`).
- For this repo, prefer small, direct classes; avoid reintroducing removed legacy modules unless needed.

## Testing Guidelines
- Priority is device verification (real-phone STT/TTS behavior).
- Mandatory smoke flow after audio changes:
  1. Login page opens.
  2. Enter main page.
  3. STT button shows streaming subtitle and auto-stop.
  4. After STT final, text is auto-sent to AI; toast shows sent status.
  5. AI reply updates subtitle area; TTS speaks reply by sentence chunks.
  6. TTS test button still plays preset text.
- If STT regresses, capture `error.txt` in repo root.
- If AI/TTS latency sounds abnormal, capture `logcat.txt` and filter tags:
  - `MainVoiceChain`
  - `SherpaTts`

## Commit & Pull Request Guidelines
- Use focused commits with prefixes: `feat:`, `fix:`, `refactor:`, `chore:`.
- Include changed files and user-visible behavior in PR description.
- For speech tuning commits, include tested environment notes (device + noise condition).

## Memory Bootstrap (Required)
- At session start, read:
  1. `PROJECT-MEMORY.md` (primary memory)
  2. `PROJECT_MEMORY.md` (legacy snapshot)
- Always cross-reference old implementation ideas from:
  - `D:\AndroidStudioProjects\vhuman\ztjyaimetahumant`

## Current Runtime Baseline
- AI SDK: `com.alibaba:dashscope-sdk-java:2.22.9`.
- AI call path is enabled in `MainActivity` using `DashScopeManager`.
- TTS baseline for naturalness:
  - playback rate fixed at `1.0` (no time-stretch effect),
  - model `lengthScale = 1.08`,
  - startup silent warmup synthesis enabled.
