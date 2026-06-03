# Changelog

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
