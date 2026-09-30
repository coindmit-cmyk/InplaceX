# InplaceX Workflows

## Setup

1. Use JDK 11 or a compatible Android Studio JBR.
2. Configure Android SDK through Android Studio, `ANDROID_HOME`, or `ANDROID_SDK_ROOT`.
3. Keep local machine settings in `local.properties`; do not commit it.
4. Copy safe provider placeholders from `InplaceX-android/provider-config.example.properties` into local configuration only when needed.

## Common Commands

Run from repository root:

```powershell
.\gradlew.bat verifyProject
.\gradlew.bat assembleDebug
.\gradlew.bat cleanLocalDiagnostics
```

On Unix-like shells, use `./gradlew` instead of `.\gradlew.bat`.

## Git Branch Workflow

Use project branch names:

- `production` for the final user-facing line.
- `develop` for the main development integration line.
- `feature/*` for new features and project setup work.
- `fix/*` for bug fixes.
- `test/*` for testing experiments.
- `debug/*` for diagnostics and debugging.
- `release/*` for release preparation.

Do not start new project work on `codex/*` branches. If a tool creates a `codex/*` branch, rename the local branch to the matching project pattern before continuing.

`master` currently exists because `origin/master` is still the remote default branch. Treat it as a legacy mirror until the GitHub default branch is intentionally changed.

## Verification Scope

- Shared bot/core change: run `.\gradlew.bat :InplaceX-bot-core:test`.
- Shared logging change: run `.\gradlew.bat :InplaceX-logging:test`.
- Shared test-support change: run `.\gradlew.bat :InplaceX-test-support:test`.
- Backend bot/runtime change: run `.\gradlew.bat :InplaceX-backend:test`.
- Android app logic or UI state change: run `.\gradlew.bat :app:testDebugUnitTest`.
- Cross-module change: run `.\gradlew.bat verifyProject`.
- APK/build configuration change: run `.\gradlew.bat assembleDebug`.

## Code + Logging + Tests Rule

When adding production behavior, handle these in the same change:

- code in the owning module;
- logging through `InplaceX-logging` when the behavior crosses runtime, integration, state transition, or failure boundaries;
- tests through the module's public inputs and outputs.

Do not add noisy logs for tight loops, rendering-only recompositions, or obvious local assignments. Do add logs for provider setup, backend/session transitions, unrecoverable validation failures, persistence/sync boundaries, and user-visible recovery paths.

## Documentation Workflow

- Update `InplaceX-docs/Game/GPT/` when contracts, layers, module boundaries, or bot/runtime rules change.
- Update `InplaceX-docs/Game/Human/` when product flows, UX, modes, monetization, or user-facing rules change.
- Update root `docs/` when onboarding, setup, command usage, or top-level architecture summaries change.

## Changelog Workflow

Add a `CHANGELOG.md` entry when a change affects:

- setup or configuration;
- architecture or module boundaries;
- public contracts;
- game behavior or UX;
- testing workflow;
- release or production safety.

Skip changelog entries for tiny internal edits that do not affect behavior or workflows, and mention the reason in the final response.
