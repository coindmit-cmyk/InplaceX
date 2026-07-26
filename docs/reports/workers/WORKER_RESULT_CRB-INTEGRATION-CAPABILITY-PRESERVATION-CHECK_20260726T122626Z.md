# Worker result: CRB-INTEGRATION-CAPABILITY-PRESERVATION-CHECK

- Role: `worker`
- Result: `integration_requested`
- Check status: `passed`
- Immutable execution base: `e99d56826ded2853cabfcd2a70ea89884f0f1f7b`
- Worker HEAD before implementation: `e99d56826ded2853cabfcd2a70ea89884f0f1f7b`

## Implemented

- Added the read-only `capability_preservation_check.py` CLI with deterministic
  JSON, resolved ref SHAs, complete changed-path reporting, preserved moves,
  potential removals, fail-closed errors and distinct allow/review/error exits.
- Detects functions, classes, CLI flags, JSON/YAML fields and statuses, public
  Markdown headings, executable entrypoints and raw file deletion.
- Computes every base path's head presence independently.
- Proves an inter-file move only when the destination token is new relative to
  that destination at the base ref, or when Git supplies an explicit rename or
  copy mapping. A pre-existing duplicate function or class cannot mask removal.
- Added isolated temporary-Git-repository coverage, including paths with spaces,
  invalid refs/parsing, deletion/modify/rename/move permutations and hostile
  duplicate function/class fixtures.

`docs/automation/SCRIPTS_CATALOG.md` already contains the active
`capability_preservation_check` entry at the required path, so it did not need
another edit.

## Checks

- `python scripts/agent_control/test_capability_preservation_check.py`
  - Passed: 10 tests.
- `python scripts/agent_control/test_integrator_guard_regressions.py`
  - Passed: 6 tests.
- `python scripts/agent_control/capability_preservation_check.py --base-ref HEAD^ --head-ref HEAD --all-changed --json`
  - Passed with `status=preserved`, `exit_decision=allow`.
  - Resolved base SHA:
    `2219bd584e5c55e8c833bcd5426524e8ee1f6b5b`.
  - Resolved head SHA:
    `e99d56826ded2853cabfcd2a70ea89884f0f1f7b`.
  - Compared changed paths:
    `AiStudio/Task_manager/agent_locks.json` and
    `AiStudio/Task_manager/task_queue.json`.
- `git diff --check`
  - Passed with no output.

## Conservative limitations

- Unsupported or non-UTF-8 changed files, malformed supported structured files,
  incomplete Git comparisons and unresolved refs return `error`; they are never
  treated as preservation success.
- The checker identifies capability-shaped declarations and public contract
  tokens, not arbitrary semantic behavior inside function bodies.
- A changed source file with no extractable capability evidence can require
  review rather than silently passing.
- Potential removals are an Integrator review gate; this script does not
  authorize a removal or merge.

## Handoff

Integrator must rerun the preservation command against the exact committed
worker candidate SHA before acceptance.
