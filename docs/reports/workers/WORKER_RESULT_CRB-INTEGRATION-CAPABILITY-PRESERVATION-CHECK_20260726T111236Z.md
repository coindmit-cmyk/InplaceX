# Worker result: CRB-INTEGRATION-CAPABILITY-PRESERVATION-CHECK

## Result

Implemented the fail-closed, read-only capability preservation gate at
`scripts/agent_control/capability_preservation_check.py`. It resolves Git
objects without checking out or changing refs/worktrees, compares the complete
changed-ref surface, and emits deterministic JSON with resolved SHAs, changed
paths, preserved moves, potential removals, status and exit decision.

The check returns `review_required` for potential removal or insufficient
extraction evidence, and `error` for unresolved refs, unreadable/non-text
changed blobs, or parse failures. It intentionally favors false-positive review
over a silent preservation success. It detects structural capability tokens;
semantic equivalence of a retained symbol still requires direct Integrator
review and tests.

## Verification

- `python scripts/agent_control/test_capability_preservation_check.py` — passed
  (8 tests).
- `python scripts/agent_control/test_integrator_guard_regressions.py` — passed
  (6 tests).
- `python scripts/agent_control/capability_preservation_check.py --base-ref HEAD^ --head-ref HEAD --all-changed --json` — passed:
  base `d4e31ee2c5e9aa8e45cb3d22ee72452da64d2cca`, head
  `0c825dbd738355108068ed2d2898216fe719ff6d`, status `preserved`.
- `git diff --check` — passed.
- `python -m py_compile scripts/agent_control/capability_preservation_check.py scripts/agent_control/test_capability_preservation_check.py` — passed.

## Handoff

`integration_requested`: Integrator must run the cataloged command against the
exact candidate SHA before integration and review every non-success result.
