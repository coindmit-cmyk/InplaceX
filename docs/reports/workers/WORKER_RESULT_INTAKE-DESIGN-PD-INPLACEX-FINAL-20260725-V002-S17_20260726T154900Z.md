# S17 worker result

`check_status=partial`

## Implemented

- Replaced the three unused `BoxWithConstraints` scopes with `Box` in
  `CompanyRootScreen`, `HomeScreen`, and `RaceSetupScreen` without changing
  their layout modifiers or child content.
- Made unit verification, lint, instrumentation, and release assembly blocking
  CI jobs. The instrumentation job now calls a repository-owned script that
  checks KVM access, creates an API 34 AVD, bounds device/boot waits, verifies
  readiness, cleans up the emulator, and directly runs
  `connectedDebugAndroidTest`.
- Added a fail-closed CI contract guard. It requires exact direct workflow
  commands and rejects disabled/non-blocking jobs and steps, command labels,
  heredocs, and short-circuit command substitutions. Its self-test covers 15
  hostile full-workflow mutations plus fake `apksigner` success/failure and a
  missing verifier.
- Added `apksigner`-derived signing evidence to artifact manifests. A missing
  verifier fails; an APK that does not verify is explicitly reported as
  `unverified`, never as an assumed signed label.

## Checks

Passed:

- `bash gradlew verifyProject`
- `bash gradlew lint`
- `bash gradlew :app:assembleDebugAndroidTest`
- `bash gradlew assembleRelease`
- `python3 scripts/ci/validate_ci_contract.py --self-test` — `15/15` hostile
  fixtures rejected or proved.
- `actionlint 1.7.7 .github/workflows/ci.yml` — passed from an isolated
  temporary download.
- `bash scripts/ci/artifact_identity.sh --apk InplaceX-android/app/build/outputs/apk/release/app-release-unsigned.apk --output-dir /tmp/inplacex-s17-artifact-check --artifact-type release` — manifest records version `1.0`, SHA-256 and `signing_status=unverified` from `apksigner`.
- `git diff --check`

## Remaining blocking evidence

The required real GitHub Actions instrumentation run cannot be produced in this
worktree: the central runner commits and pushes this worker branch after the
worker exits, so no candidate commit SHA exists yet. Do not emit
`integration_requested` yet. The next owner must push the candidate, run the
`InplaceX CI` workflow for that exact SHA, and attach evidence that the Linux
runner has KVM access, the API 34 emulator boots, and
`connectedDebugAndroidTest` passes. Until then route this task as
`needs_worker_fix` / pending remote CI evidence.
