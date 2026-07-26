# Changelog

## Unreleased

- Added the Toy Room UI v5 foundation from the approved visual references:
  warm desk scenery, glossy blue resource chrome, cream raised cards, vivid
  orange/purple/green mode hierarchy, compact top actions, and an illuminated
  blue bottom navigation shared by Home, Friends, Company, Shop, and Profile.
- Hardened the authoritative duel engine with single-use mutable digit
  commands, ASCII-only validation, viewer-neutral attempt snapshots, and
  deterministic secret-buffer zeroization after finish, close, or failure.
- Isolated sandbox provider stubs and test identifiers to the debug Android variant. Release provider wiring now fails closed until real Google Play, Billing, and AdMob SDK results are integrated.
- Added closed backend session read contracts with deterministic 64 KiB JSON
  frames, strict bounded JSON scanning, server-keyed secret fingerprints, and
  pseudonymous read-log attributes. Client intents, authenticated actor binding,
  and caller-authored outcome decoding remain outside this slice.
- Added a local Docker Compose backend/PostgreSQL stack with persistent storage,
  health checks, environment-only credentials, and an executable backup,
  restore, rollback, and migration verification runbook.
- Added the modular Ktor backend foundation with environment-driven runtime
  configuration and health/readiness endpoints.
- Added PostgreSQL-backed versioned persistence migrations for players, cloud-save
  revisions, matchmaking tickets, duel sessions, idempotent commands, and events.
  Database connection credentials remain external process configuration.
- Added an isolated local Docker Compose backend/PostgreSQL stack with migration,
  backup/restore, rollback verification, localhost-only port binding, and a
  build-context secret exclusion policy.
- Activated the state-backed `GameFieldScreen → GameFieldViewModel → GameScreen`
  route while keeping manual analysis marks non-authoritative and preserving
  localized validation, hints, boosts, timers, attempt scrolling, and deduction.

## 2026-07-25

### Added
- Added a transport-agnostic authoritative backend duel aggregate with ordered secret setup, turn and score ownership, terminal win handling, and secret-free public snapshots.
- Added a stateless `GameScreen` presentation boundary with independent top, attempts, analysis, helpers, tools, input, and optional debug-slot components.
- Added a GitHub Actions CI foundation with Java 21 Gradle launcher, Java 11 project toolchains, identified debug artifacts, and visible non-blocking instrumentation/release checks.
- Added the InplaceX Design System v2 direction, Compose UI contract, and a visual concept for the Home and Race screens.
- Added a planning-only AiStudio handoff and dependency-aware finalization backlog covering preservation, UI migration, match-contract convergence, online backend, VPS staging, E2E, and release hardening.
- Added a stable semantic color-token set for the Android client.
- Added versioned online REST/WebSocket/security contracts with machine-readable v1 schemas for guest auth, cloud save, matchmaking, duel commands, snapshots, reconnect, idempotency, concurrency, redaction, and secret ownership.

### Changed
- Localized secondary, developer, and Bot Lab screen labels for Russian and English while retaining diagnostic identifiers and controls.
- Replaced the default dynamic Material palette with stable InplaceX brand colors and shapes.
- Updated the global background, top resources, bottom navigation, shared scene components, Home identity, and player-facing Race/Duel labels.
- Restored visible game status and validation feedback after the UI redesign, including a localized explanation when every entered digit is identical.
- Made the in-match attempt history automatically follow the newest accepted result.
- Extended auto-mode deduction so every remaining possible position is locked when the score proves that all of them are exact matches.

### Safety
- Redesign work starts from `baseline/pre-redesign-2026-07-25`.
- The automation handoff remains planning-only and does not authorize Worker launch or VPS activation.

## 2026-07-17

### Added
- Added machine-readable and human-readable Project Maps so agent work and Second Brain history can bind to the current InplaceX modules.

## 2026-06-03

### Added
- Added `docs/project-baseline.md` as the baseline for `0.1.0-project-foundation`.
- Added `docs/next-work.md` with the next stabilization, product, architecture, quality, and archive tasks.

### Changed
- Moved short root onboarding docs into the intended `docs/` directory.
- Updated agent context to include `:InplaceX-test-support`.
- Increased Gradle wrapper download timeout to make first-time setup more reliable on slow connections.

### Notes
- This baseline is an internal project foundation checkpoint, not a production APK release.

## 2026-05-11

### Added
- Added root AI agent entry point and project-specific `.agent/` context.
- Added `InplaceX-logging` as a shared logging contract module with unit tests and sensitive-key redaction.
- Added `InplaceX-test-support` as a shared JVM test infrastructure module for reusable log sinks and test-only console adapters.
- Added logging module documentation.
- Added short root onboarding docs for architecture, modules, development, and testing.
- Added repository-level examples/placeholders for environment and editor configuration.
- Added reserved `scripts/` and `tests/` documentation.

### Changed
- Included `:InplaceX-logging:test` in the root `verifyProject` workflow.
- Included `:InplaceX-test-support:test` in the root `verifyProject` workflow.
- Clarified root README and `.gitignore` expectations for local configuration and diagnostics.
- Documented the project Git branch workflow and the rule to avoid new `codex/*` work branches.
- Documented the rule that production code changes should include logging and tests in the same change.
- Replaced direct `android.util.Log` calls in app production code with the shared logging contract plus Android sink adapter.
- Replaced direct `println` usage in bot benchmark and trace runners with the shared logging contract plus test-support console sink.

### Verification
- Reviewed repository structure, Gradle modules, existing docs, and current Git state before creating files.
- Verified local branch names and checked updated workflow docs with `git diff --check`.
