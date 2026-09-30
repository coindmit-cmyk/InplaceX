# S17 integrator review: e3f6e42

## Verdict

`REJECT / needs_worker_fix`.

Candidate `e3f6e4269fb2ca67c18501f9dc9d9ee84ad1a69a` is preserved as salvage evidence and must not be integrated.

## Blocking findings

1. The required `bash gradlew lint` check fails on current `develop` with `UnusedBoxWithConstraintsScope` in `CompanyRootScreen.kt`, `HomeScreen.kt`, and `RaceSetupScreen.kt`. The worker report itself records a partial check result.
2. The contract validator accepts conditional skips and expression-valued `continue-on-error`, including `${{ github.ref == 'refs/heads/develop' }}` and `${{ true }}`.
3. Echo/comment/no-op and short-circuit substitutions still satisfy required emulator, boot wait, instrumentation, release, and signing checks.
4. Signing evidence remains fail-open when `apksigner` is unavailable (`signing_status=unknown`, exit 0), and static signing labels/comments satisfy the validator.
5. KVM permissions are neither configured nor contract-checked. There is no real GitHub Actions run for this SHA proving emulator boot and `connectedDebugAndroidTest`.
6. The mutation suite omits the required hostile fixtures and self-test does not require a complete category set.

## Independent evidence

- `bash gradlew verifyProject`: passed.
- `bash gradlew lint`: failed.
- `bash gradlew :app:assembleDebugAndroidTest`: passed.
- `bash gradlew assembleRelease`: passed.
- `python3 scripts/ci/validate_ci_contract.py --self-test`: passed, but the hostile mutations above were incorrectly accepted.
- `git diff --check`: passed.

## Mandatory retry contract

- Start from current `origin/develop`; use e3f6e42 only as read-only salvage evidence.
- Fix all current lint failures without blanket suppression.
- Parse executable semantics and reject job/step conditional skips, all truthy or expression forms of `continue-on-error`, echo/comment/label/no-op substitutions, and short-circuit bypasses.
- Require fail-closed APK signature verification by a real Android signing tool.
- Configure and verify Linux KVM access or use a maintained emulator action; make boot timeout truly bounded and avoid `yes | avdmanager` under `pipefail`.
- Require a complete named mutation fixture set covering every acceptance category.
- Provide a real GitHub Actions run for the exact candidate SHA with emulator boot and `connectedDebugAndroidTest` passing.
- Report success only when every mandatory Gradle, contract, mutation, release, signing, and Actions check is green.
