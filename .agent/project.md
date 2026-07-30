# InplaceX Project Rules

## Purpose

InplaceX is a logic game built around guessing a hidden digit sequence. The project is Android-first, but the architecture is intended to remain reusable for future clients and related game variants.

The product direction is both:

- a standalone mobile game with PvE, PvP, bot, and future online modes;
- a reusable game platform with shared match, bot, provider, localization, and shell contracts.

## Technology Stack

- Language: Kotlin.
- Build system: Gradle Kotlin DSL.
- Android client: Android application module with Jetpack Compose and Material 3.
- Shared domain/runtime: JVM modules.
- Tests: JUnit unit tests, Android unit tests, and Android instrumented tests.
- Configuration: Android provider ids are read from `local.properties` and exposed through `BuildConfig`; safe placeholders live in `InplaceX-android/provider-config.example.properties`.

## Canonical Documentation

Use these docs as the stable source of truth:

- `InplaceX-docs/Game/Human/` for human product and UX documentation.
- `InplaceX-docs/Game/GPT/` for machine-oriented architecture and contract documentation.
- `InplaceX-docs/Game/GPT/ADR/` for architecture decisions.
- `docs/` for short root-level onboarding and command references.

Do not duplicate long canonical documents in root docs. Link to them and summarize only the parts needed for local work.

## Architecture Boundaries

Current physical modules:

- `:app` at `InplaceX-android/app`: Android UI, shell, navigation, Android integration, and current local game runtime wiring.
- `:InplaceX-bot-core` at `InplaceX-bot-core`: shared match lifecycle and
  contracts, campaign rules, evidence deduction, bot logic, validation,
  scoring, secret generation, mode definitions, and `GameConfig`.
- `:InplaceX-logging` at `InplaceX-logging`: shared logging contract, sinks, levels, and sanitization.
- `:InplaceX-test-support` at `InplaceX-test-support`: shared JVM test sinks and test-only helper infrastructure.
- `:InplaceX-backend` at `InplaceX-backend`: authoritative JVM duel/session
  runtime, matchmaking, private invites, membership, persistence, transport,
  identity routes, and server-side bot participation.
- `InplaceX-docs`: canonical documentation, not application runtime code.

Allowed dependency direction:

- Android app may depend on platform contracts, game core contracts, and `InplaceX-bot-core`.
- Android app, backend, and shared JVM modules may depend on `InplaceX-logging` for logging contracts.
- Test source sets and benchmark runners may depend on `InplaceX-test-support`.
- Backend may depend on `InplaceX-bot-core`.
- Shared game and bot logic must not depend on Android UI or Android framework APIs.
- Backend concerns must not leak into `InplaceX-bot-core`.

## Game Rules And Contracts

The canonical match lifecycle is:

1. start match;
2. submit guess;
3. validate guess;
4. calculate exact-position score;
5. update match phase;
6. expose snapshot.

Important invariants:

- `codeLength` is expected to stay in `4..20`.
- no-duplicates games cannot exceed the ten-symbol decimal alphabet.
- `attemptLimit` must be positive.
- configured turn limits must be positive.
- Win condition is exact-position score equal to code length.
- Lose condition is exhausting the attempt limit.
- Fixed, generated, restored, and submitted secrets use the same validation
  rules from `GameConfig` / mode-specific configuration.

New modes should be added through `GameModeDefinition`, `OpponentProvider`, shell/UX configuration, and orchestration. Do not copy the engine for a new mode.

## Bot Rules

Bot logic is a shared domain entity, not a UI helper. New bot behavior should be implemented in `BotAgent` and exposed through existing facades only when needed for compatibility.

The backend bot is modeled as a match participant. `ServerBotPlayer` must not leak the hidden secret in normal snapshots.

`nextTurnOrNull()` in backend bot flows is expected to be idempotent while feedback for the pending turn is missing.

## Provider And Secret Handling

- Use `local.properties` for local Android provider ids.
- Keep real provider ids, tokens, secrets, passwords, signing keys, and credentials out of the repository.
- Use `InplaceX-android/provider-config.example.properties` for safe placeholders.
- UI and gameplay should depend on provider contracts and entitlements, not hardcoded provider ids.

## Logging And Tests With Code

- New behavior should be designed with logging and tests in the same change, not as a later cleanup pass.
- Use `InplaceX-logging` for shared logging contracts and redaction rules.
- Add module-level tests for new logging behavior before wiring it into app/backend code.
- Do not log raw secrets, provider ids, tokens, passwords, cookies, private keys, hidden secrets, or personal data.
- When adding code in a module, add the narrowest relevant test in the same module or explain why this is not practical.

## Dev/Test And Production Safety

- Developer screens, debug slots, bot labs, diagnostics, benchmark runners, and local captures are internal tooling.
- Keep them isolated through debug-only dependencies, internal navigation, explicit developer screens, test source sets, or Gradle tasks.
- Release and production user flows must not expose unsafe diagnostics, mock services pretending to be live services, seed controls, or hidden secrets.

## Change Discipline

- If a change affects a canonical contract, update `InplaceX-docs/Game/GPT/` first or in the same patch.
- If a change affects human-facing behavior, update `InplaceX-docs/Game/Human/` or root `docs/` when appropriate.
- If a change is meaningful for setup, architecture, behavior, tests, or workflows, add a `CHANGELOG.md` entry.
