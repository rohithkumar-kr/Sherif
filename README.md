# SHERIF — AI Interview Coach

SHERIF is an offline-first Android mock-interview coach. It runs realistic,
role-specific mock interviews with live AI feedback powered by **Google Gemini**,
scores every answer, and produces a shareable PDF report.

> **Note:** The repository/project identifiers still use the `com.example.aiinterviewapp`
> package and `AIInterviewApp` class names from the original scaffold. They are internal
> identifiers only and are kept to avoid churn — the user-facing product is **SHERIF**.

---

## Features

- **AI-driven mock interviews** — Gemini generates role-specific questions (Technical,
  Behavioral, HR, Coding, Mixed), adapts difficulty/experience, and asks natural follow-ups.
- **Real-time evaluation** — every answer is scored 1–10 on confidence, communication,
  technical depth, and grammar, with strengths, weaknesses, and actionable suggestions.
- **Save & Pause / Resume / Discard** — progress is persisted locally; you can resume a
  paused session from the home screen or the new-interview screen.
- **Performance report** — per-question breakdown, animated overall score, dimension bars,
  and a **Share PDF** export.
- **Resume-aware questions** — upload a PDF resume and Gemini personalises questions from it.
- **Voice input + text-to-speech** — answer by microphone and have questions read aloud
  (requires device speech services and microphone permission).
- **History, stats, profile & settings** — past interviews, average/best scores, dark mode.
- **SHERIF brand design** — dedicated indigo/teal color system, typography, and component
  library (`ui/common/SherifComponents.kt`).

## Architecture

Clean architecture with MVVM + Hilt:

```
ui/            Compose screens + ViewModels (interview, home, report, history,
               resume, profile, settings, login, splash)
domain/        Models (Interview, InterviewQuestion, QuestionEvaluation),
               repository interface, use cases
data/          Room (interviews), DataStore (auth + resume), Retrofit/OkHttp
               (Gemini), services (Voice, Resume/PDFBox, PdfExport)
di/            Hilt modules (network, database, repository, app services)
```

- **Gemini** — Retrofit + kotlinx.serialization, structured output via `responseSchema`
  with an `AiResponseParser` fallback that tolerates markdown fences, prose, and malformed
  JSON. Errors are mapped to friendly messages (`GeminiErrorMapper`).
- **Persistence** — Room DB `interviews` (migration 1→2 adds a `date` index); Preferences
  DataStore for login state, dark mode, and resume text.
- **Retry/robustness** — OkHttp retries 429/5xx with backoff; the interview ViewModel has
  a manual retry path, duplicate-question rejection, and can never get stuck in Loading/
  Evaluating (every async branch resolves to Success, Error, or Completed).

## Tech stack

Kotlin 2.2 · Jetpack Compose (Material 3) · Hilt · Retrofit/OkHttp · kotlinx.serialization ·
Room · DataStore · Navigation Compose · PDFBox (tom_roush) · Coil.

## Configuration

### 1. GEMINI_API_KEY (required for AI features)

1. Create a key at <https://aistudio.google.com/apikey>.
2. Add it to your **local** `local.properties` file (do **not** commit this file):

```properties
GEMINI_API_KEY=AIza...
```

> `local.properties` is git-ignored. If the key is missing or blank the app builds fine,
> but every Gemini request will fail with a clear "authentication failed" message. The key
> is compiled into `BuildConfig.GEMINI_API_KEY` and sent as the `x-goog-api-key` header.
> For a production release, prefer proxying Gemini through your own backend so the key is
> not embedded in the APK.

### 2. Google Sign-In (optional, currently a safe fallback)

`GOOGLE_CLIENT_ID` may also be set in `local.properties`:

```properties
GOOGLE_CLIENT_ID=your-oauth-client-id
```

Until a real Credential Manager integration is wired, **both** login buttons start a local
guest session. There are no fake credentials; when the client ID is blank the app fails
safely to guest login. See `ui/screens/login/LoginViewModel.kt`.

## Building

Requires JDK 17+ and Android Studio / SDK with API 37.

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (minified + resource-shrunk, R8)
./gradlew assembleRelease

# All JVM unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug

# Compile the instrumented (device) tests without a device
./gradlew compileDebugAndroidTestKotlin
```

`app/build/outputs/apk/` contains the produced APKs.

## Testing

- **JVM unit tests** (`app/src/test`) cover AI parsing, error mapping, repository
  (including schema/mime verification), resume-state logic, scoring, and interview
  ViewModel retry/duplicate/restore behavior. They run without a device.
- **Instrumented Compose tests** (`app/src/androidTest`) verify the Create Interview
  screen ("Start AI Session", `role_field`, default count 5). They are compiled
  (`compileDebugAndroidTestKotlin`) but only execute on a device via
  `connectedDebugAndroidTest`.

## Known limitations

- **Live Gemini not verified in this environment** — all AI paths are tested with fake
  responses; end-to-end correctness needs a real network + key.
- **Device-dependent features not device-tested** — voice input, text-to-speech,
  microphone permission, PDF picker, share sheet, Google Sign-In UI.
- **Release R8** is enabled with rules for kotlinx.serialization, Room, Retrofit and
  PDFBox (`app/proguard-rules.pro`), but the release APK has not been exercised on a device.
- **Google Sign-In** currently falls back to a local guest session (no real OAuth flow).

## License / status

Pre-release. Ready for final device/demo verification.