# Capability preservation integrator acceptance: 4e721c3

## Verdict

`ACCEPT / owner approval required`.

Candidate `4e721c31fb9f438d09c4ac322084c3fcd2832977` closes the fail-open duplicate-name move bug and is suitable for integration through draft PR #4. Direct merge remains unauthorized.

## Independent evidence

- `test_capability_preservation_check.py`: 10/10 passed on Linux.
- `test_integrator_guard_regressions.py`: 6/6 passed.
- Exact `HEAD^..HEAD` smoke returned `status=preserved`, `exit_decision=allow`, base `e99d56826ded2853cabfcd2a70ea89884f0f1f7b`, head `4e721c31fb9f438d09c4ac322084c3fcd2832977`.
- `git diff --check`: passed.
- The hostile fixtures independently cover a pre-existing same-name function and class in another changed file; both removals return review-required instead of a fabricated move.
- Changed paths remain within the worker packet.

## Integration gate

PR #4 is intentionally draft. It must not be merged until the owner explicitly authorizes that PR.
