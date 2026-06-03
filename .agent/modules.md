# InplaceX Module Notes

## `:app`

- Path: `InplaceX-android/app`
- Type: Android application.
- Owns: `MainActivity`, Compose UI, shell, navigation, Android resources, platform service wiring, local repositories, and current screen-level orchestration.
- Tests: `InplaceX-android/app/src/test` and `InplaceX-android/app/src/androidTest`.
- Important packages:
  - `ui.*` for Compose screens, shell, navigation, theme, and layout.
  - `core.*` for Android-local game contracts still living inside the app.
  - `platform.*` for configuration, localization, online boundaries, navigation, and services.

## `:InplaceX-bot-core`

- Path: `InplaceX-bot-core`
- Type: JVM shared library.
- Owns: transport-agnostic bot brain, bot rules, grid catalog, scoring, validation, secret generation, and shared `GameConfig`.
- Tests: `InplaceX-bot-core/src/test`.
- Important packages:
  - `core.bot`
  - `core.engine`
  - `core.model`

## `:InplaceX-backend`

- Path: `InplaceX-backend`
- Type: JVM backend module.
- Owns: backend-facing runtime contracts and the server-side bot participant adapter.
- Tests: `InplaceX-backend/src/test`.
- Important package:
  - `backend.bot`

## `:InplaceX-logging`

- Path: `InplaceX-logging`
- Type: JVM shared library.
- Owns: shared logging levels, log event contract, sinks, and sensitive-key redaction.
- Tests: `InplaceX-logging/src/test`.
- Important package:
  - `logging`

## `:InplaceX-test-support`

- Path: `InplaceX-test-support`
- Type: JVM shared test library.
- Owns: reusable test sinks, test-only console log adapters, and shared helper infrastructure for module tests and manual runners.
- Tests: `InplaceX-test-support/src/test`.
- Important package:
  - `testsupport`

## `InplaceX-docs`

- Path: `InplaceX-docs`
- Type: canonical documentation.
- Owns: product vision, UX flows, game rules, architecture contracts, ADRs, migration notes, and legacy docs.
- Read order starts at:
  - `InplaceX-docs/Game/Human/README.md`
  - `InplaceX-docs/Game/GPT/README.md`

## Root Documentation

- Path: `docs`
- Type: short onboarding and command reference.
- Purpose: quick entry points that point back to canonical docs instead of replacing them.
