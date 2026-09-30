# InplaceX Module Notes

## `:app`

- Path: `InplaceX-android/app`
- Type: Android application.
- Owns: `MainActivity`, Compose UI, shell, navigation, Android resources, platform service wiring, local repositories, and current screen-level orchestration.
- Tests: `InplaceX-android/app/src/test` and `InplaceX-android/app/src/androidTest`.
- Important packages:
  - `ui.*` for Compose screens, shell, navigation, theme, and layout.
  - `platform.*` for configuration, localization, online boundaries, navigation, and services.

## `:InplaceX-bot-core`

- Path: `InplaceX-bot-core`
- Type: JVM shared library.
- Owns: transport-agnostic match lifecycle and contracts, campaign generation,
  rating and progression, evidence deduction, bot brain and rules, grid
  catalog, scoring, validation, secret generation, mode definitions, and
  shared `GameConfig`.
- Tests: `InplaceX-bot-core/src/test`.
- Important packages:
  - `core.bot`
  - `core.campaign`
  - `core.engine`
  - `core.match`
  - `core.model`

## `:InplaceX-auth-core`

- Path: `InplaceX-auth-core`
- Type: JVM shared library.
- Owns: provider identifiers, opaque provider-subject derivation, passwordless
  email-code policy, and Telegram login signature/freshness verification.
- Does not own: HTTP routes, SMTP delivery, provider secrets, Android UI, or
  player persistence.
- Tests: `InplaceX-auth-core/src/test`.

## `:InplaceX-ads-core`

- Path: `InplaceX-ads-core`
- Type: JVM shared library.
- Owns: provider-neutral ad placements, formats, entitlement-first eligibility,
  post-match cadence, and reward-completion policy.
- Does not own: an ad network SDK, Android views, or reward persistence.
- Tests: `InplaceX-ads-core/src/test`.

## `:InplaceX-backend`

- Path: `InplaceX-backend`
- Type: JVM backend module.
- Owns: backend-facing runtime, authoritative duel/session state, matchmaking,
  private invites, membership, persistence, transport, identity routes, and the
  server-side bot participant adapter.
- Tests: `InplaceX-backend/src/test`.
- Important packages:
  - `backend.bot`
  - `backend.domain`
  - `backend.online`
  - `backend.session`

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
