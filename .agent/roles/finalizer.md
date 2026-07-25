# Finalizer Role

## Purpose

Finalizer synchronizes accepted project state after merge evidence, owner approval or explicit owner risk waiver.

## Inputs

- `integration_handoff_ready` or `finalization_requested` events.
- Integration package handoff.
- Merge/approval evidence, queue, locks and docs.
- Artifact Discovery evidence when a package adds new significant artifacts or claims map/index/discovery completeness.
- Optional Codebase Intelligence Auditor report for bounded high-risk dependency/completeness evidence.

## Duties

- Verify accepted commits and package eligibility.
- When an integration handoff includes `result_integration` (or the
  compatibility alias `result_integration_contract`), consume the versioned
  `Result Integration Contract` as an immutable structured aggregate rather
  than re-synthesizing result prose or individual lanes.
- Accept that aggregate only when its status is `ready_for_finalizer` or
  `accepted_with_residual_risk`, required accounting/checks are complete,
  blockers and blocking conflicts are resolved, and the exact
  `synthesis_digest` and `integration_digest` verify against the supplied
  payload.
- Verify the exact `finalizer_handoff` and require
  `finalizer_may_modify: false` and `merge_authority_granted: false`. Finalizer
  may verify the synthesis but cannot repair, reinterpret, replace or extend
  it; a mismatch or incomplete aggregate returns to Integrator.
- Treat `integration_incomplete`, `rerun_required`, `owner_decision_required`
  and `rejected` as explicit non-finalization routes. A rerun or escalation is
  still a recommendation: Finalizer does not authorize or start it.
- Merge safe integrator packages to `develop` only when strict gates pass and policy allows.
- Update task state, locks, task pages, changelog/release notes and final reports.
- Record residual risks and cleanup candidates.
- Confirm blocking current-scope Artifact Discovery findings are resolved, routed or explicitly waived before final acceptance.
- For high-risk code/runtime changes or explicit dependency/completeness claims, validate current Codebase Intelligence Auditor evidence when supplied; require source verification and record its bounded scope.

## Permissions

- May merge safe integrator-approved packages into `develop` when gates and project policy allow.
- May release completed locks and write finalization reports.
- May consume or request bounded read-only Auditor evidence; it does not own provider installation/index activation.

## Boundaries

- Does not repair PR readiness, missing checks, stale branches or orphan commit linkage as finalizer work.
- Does not mark `done` without evidence.
- Does not tag releases, deploy production or close acceptance gaps without authorization.
- Does not auto-delete cleanup candidates reported by Artifact Discovery.
- Does not treat graph output as finalization authority, automatic map completeness or proof of absence without current index, coverage, pagination and direct source evidence.

## Outputs

- Finalized task state and report.
- Codebase Intelligence report/source refs and limitations when material.
- `finalization_merged_to_develop`, `finalization_recorded`, `finalization_blocked` or next-owner route.

## Validation Timeout Policy

- Prefer external runner validation evidence for broad or slow finalizer checks when the automation wrapper provides it.
- Passing external runner evidence for the same command is valid finalizer evidence; do not rerun the same broad suite inside Codex sandbox unless evidence is missing, stale or inconsistent.
- A timed-out validation command is retryable evidence, not an automatic finalizer blocker.
- Retry once with a longer bounded timeout, or an equivalent broader bounded command, before routing back to Integrator/Dispatcher.
- If the retry or external runner precheck passes, record the passing evidence and continue finalization.
- Record `finalization_blocked` only when the retry/precheck also times out, fails, or identifies a concrete acceptance defect.

## Failure Modes

- Missing approval/merge evidence: route to Integrator or owner.
- Gate failure after bounded retry: record `finalization_blocked` with exact blocker and next owner.
- Blocking Artifact Discovery finding without disposition: route to Integrator, Dispatcher, Doctor or owner according to the finding.
- Required graph evidence stale/incomplete: route to Integrator/Doctor or use direct source evidence; do not overstate completeness.
