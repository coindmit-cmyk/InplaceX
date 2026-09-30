# Worker result: CRB-INTEGRATION-CAPABILITY-PRESERVATION-CHECK

- Generated: `2026-07-26T12:05:45Z`
- Worker: `auto-worker-5.5max`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5max/crb-integration-capability-preservation-check/implement-fail-closed-capability-preservation-in-retry-20260726T115941Z`
- Immutable execution base: `1fb730e01aa5d1a935c4c980107ec56ed53fa99d`
- Result: `integration_requested`
- Check status: `passed`

## Result

Implemented the cataloged, read-only capability preservation gate. It resolves
both refs, parses the complete NUL-delimited Git changed-path surface, and
produces deterministic JSON with the resolved SHAs, changed paths, preserved
moves, potential removals, status and exit decision.

The gate fails closed for unresolved refs, incomplete scans, unreadable or
unsupported changed blobs, parse errors and calls without `--all-changed`.
For each base path it independently resolves whether that exact path exists at
the head before deciding `file_removed`; no state is shared between changed
entries. It detects functions, classes, CLI flags, JSON/YAML fields and task
statuses, public Markdown headings and executable entrypoints. Potential
removals return `review_required` and a non-zero exit code.

Conservative limitation: preserved structural tokens do not prove semantic
equivalence of a retained implementation; Integrator must still directly review
a candidate's changed code and every non-success result.

## Verification

- `python scripts/agent_control/test_capability_preservation_check.py` — passed
  (8 tests, including all permutations of a delete/modify/rename/move fixture).
- `python scripts/agent_control/test_integrator_guard_regressions.py` — passed
  (6 tests).
- `python scripts/agent_control/capability_preservation_check.py --base-ref HEAD^ --head-ref HEAD --all-changed --json` — passed: base
  `a09ee2515732f79d9e188458e64d54c688bc77eb`, head
  `1fb730e01aa5d1a935c4c980107ec56ed53fa99d`, status `preserved`.
- `git diff --check` — passed.

## Handoff

`integration_requested`: Integrator must run
`python scripts/agent_control/capability_preservation_check.py --base-ref origin/develop --head-ref <candidate-ref> --all-changed --json`
against the exact candidate SHA before acceptance and review every
`review_required` or `error` result. Runner-owned Task Manager state was not
modified.
