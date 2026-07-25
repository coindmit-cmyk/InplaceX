# Changelog

## 2026-07-25

### Added
- Added the InplaceX Design System v2 direction, Compose UI contract, and a visual concept for the Home and Race screens.
- Added a planning-only AiStudio handoff and dependency-aware finalization backlog covering preservation, UI migration, match-contract convergence, online backend, VPS staging, E2E, and release hardening.
- Added a stable semantic color-token set for the Android client.

### Changed
- Replaced the default dynamic Material palette with stable InplaceX brand colors and shapes.
- Updated the global background, top resources, bottom navigation, shared scene components, Home identity, and player-facing Race/Duel labels.

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
