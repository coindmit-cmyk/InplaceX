# S17 integrator review: db7123e

Mode: `ManualIntegrationMode`

Disposition: `rejected`

Candidate `db7123e77e9146e8d3032b051f945d318b8f724e` is preserved on its worker
branch and must not be integrated into `develop`.

## Blocking finding: the CI contract guard accepts no-op labels

The workflow itself adds blocking lint, instrumentation and release jobs, boots
an emulator and derives APK signing state with `apksigner`. The new
`validate_ci_contract.py` guard, however, validates concatenated `run` text with
substring searches.

An isolated mutation replaced:

- `emulator -avd ci-api-34 ...` with `echo emulator -avd ci-api-34 ...`;
- `./gradlew :app:connectedDebugAndroidTest` with
  `echo :app:connectedDebugAndroidTest`.

The validator still returned:

```text
OK: CI workflow contract checks passed
workflow_mutation_exit=0
```

This violates the acceptance criterion that the repository-owned check prevent
silent regression to labels or no-device execution.

## Required retry contract

- Parse workflow jobs and steps as executable actions rather than searching a
  combined text blob.
- Require a real emulator start command and a real Gradle
  `connectedDebugAndroidTest` invocation; `echo`, comments and unrelated labels
  must not satisfy either requirement.
- Require a bounded boot wait that fails on timeout.
- Inspect `artifact_identity.sh` semantically enough to reject a static
  `signing_status=signed` implementation and require an Android verifier call
  against the produced APK.
- Add mutation tests proving that replacing emulator start, device wait,
  instrumentation execution, release assembly or signing verification with
  labels/no-ops makes the guard fail.
- Preserve the useful lint fixes, blocking job structure and `apksigner`
  implementation from this candidate as salvage evidence.

## Evidence

- Base: `6ae009cbae8fd9eea1223a9de0d5a4b2104c3123`
- Candidate: `db7123e77e9146e8d3032b051f945d318b8f724e`
- Candidate branch:
  `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s17/s17-aistudio-sdk-aware-lint-and-blocking-quality-retry-20260726T113716Z`
- Worker checks passed: `verifyProject`, `lint`,
  `assembleDebugAndroidTest`, `assembleRelease`, current contract script and
  `git diff --check`.
- Independent negative mutation demonstrates that the current guard can be
  bypassed without executing an emulator or instrumentation tests.
