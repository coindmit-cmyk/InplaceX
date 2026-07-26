# S17 integrator review: 6965ee9

## Verdict

`REJECT / needs_worker_fix`.

Candidate `6965ee92e429a400fbe6683aece0bc61b90d85cc` is preserved as salvage evidence and must not be integrated.

## Blocking findings

1. With a valid local JDK 21 and Android SDK, the combined mandatory Gradle gate fails during Kotlin compilation. `RaceSetupScreen.kt` replaces `BoxWithConstraints` with `Box` but does not import `Box`, producing `Unresolved reference 'Box'` and a downstream composable-context error.
2. The worker did not use the exact JDK/SDK environment already specified in its packet and incorrectly reported the environment as unavailable.
3. The contract self-test prints `OK: self-test passed (2/8 fixtures correctly fail/pass)`. A complete required mutation suite must prove every named fixture category, not only two outcomes.
4. No real GitHub Actions run for the exact candidate SHA proves KVM/emulator boot and `connectedDebugAndroidTest`.
5. The candidate changes executable mode of `scripts/ci/artifact_identity.sh` from `100755` to `100644`, which would break direct execution on Linux.

## Independent evidence

- `gradlew verifyProject lint :app:assembleDebugAndroidTest assembleRelease --no-daemon`: failed at `:app:compileDebugKotlin` and `:app:compileReleaseKotlin`.
- `python scripts/ci/validate_ci_contract.py --self-test`: exited 0 but reported only `2/8`.
- `python scripts/ci/validate_ci_contract.py`: passed.
- `git diff --check`: passed.

## Mandatory retry contract

- Start from current `origin/develop`; use 6965ee9 only as read-only salvage evidence.
- Use the exact packet-provided JDK 21/JDK 11 toolchain and Android SDK environment before every Gradle command.
- Fix all lint findings while preserving valid imports and compilation.
- Preserve executable mode on shell entrypoints.
- Require the self-test to enumerate and prove every mandatory hostile fixture category; partial fixture counts must fail.
- Pass every Gradle and CI-contract command and provide the exact GitHub Actions emulator/KVM run before requesting integration.
