# S17 integrator review: c09bf40

## Verdict

`REJECT / needs_worker_fix`.

Candidate `c09bf40e0162778dab9743005183366a34f2e485` is preserved in a local Git ref and bundle. It must not be integrated.

## Blocking findings

1. The strict retry contract from `S17_INTEGRATOR_REVIEW_1270470_20260726.md` was not implemented. Compared with `1270470`, CI contract code and mutation fixtures are unchanged.
2. The only material CI workflow change is `set -euo pipefail` plus an emulator cleanup command. Required commands are still discovered with the same fail-open parser and raw-text searches.
3. The complete workflow with `true || ./gradlew lint` still exits `0` from the validator.
4. The complete workflow with `echo bash scripts/ci/artifact_identity.sh` still exits `0`.
5. The complete workflow with `echo "avdmanager create avd"` still exits `0`.
6. Repository-owned instrumentation script, bounded `adb wait-for-device`, exact diagnostic fixtures, heredoc/comment rejection, fake-apksigner execution tests and `actionlint` were not added.

## Mandatory retry contract

- Start from current `develop`; use `c09bf40` and `1270470` only as read-only negative evidence.
- Route the retry to a stronger worker profile.
- Put instrumentation behavior in an executable repository script and make the workflow invoke exact anchored commands.
- Reject control flow, heredocs, echo/printf/comment labels and raw-text matches in required steps.
- Require each hostile fixture to mutate one valid full baseline and assert the exact expected diagnostic.
- Execute fake-apksigner tests and `actionlint`, then prove the exact candidate SHA with a completed GitHub Actions instrumentation run.
