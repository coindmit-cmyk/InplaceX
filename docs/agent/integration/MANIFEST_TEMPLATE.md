# Integration Manifest Template

## Purpose

The Integration Manifest records what was integrated, how it was checked, which surfaces were affected, what evidence exists and whether the change is complete.

## Storage

Primary manifests live under:

```text
docs/reports/integration/<INTEGRATION_ID>.yaml
```

When integration is part of a Project Design session, the session should link to the report or keep a copy under its versioned context.

## Template

```yaml
integration_id:
created_at:
updated_at:
repository:
base_branch:
working_branch:
mode: auto|manual
change_summary:
change_type:
changed_files:
changed_entities:
owner_intent_refs:
current_reality_refs:
delta_refs:
skills_used:
lenses_used:
affected_surfaces:
required_surfaces:
updated_surfaces:
missing_surfaces:
project_reality_map:
  checked:
  required_for_new_entities:
  new_entities_mapped:
  legacy_gaps:
  blocking_gaps:
inline_roles_invoked:
  - role:
    mode_or_reasoning:
    reason:
    result:
automation_compatibility:
  auto_mode_affected:
  runner_behavior_changed:
  queue_semantics_changed:
  lock_behavior_changed:
  finalizer_behavior_changed:
  release_flow_changed:
  adoption_flow_changed:
  notes:
checks:
  - name:
    command_or_method:
    result:
    evidence_ref:
result_integration:
  # Record a reference to the immutable structured aggregate; do not copy a
  # mutable prose-only summary in place of the versioned contract.
  contract_kind: result_integration
  contract_version:
  integration_id:
  status: ready_for_finalizer|accepted_with_residual_risk|integration_incomplete|rerun_required|owner_decision_required|rejected
  aggregate_evidence_ref:
  synthesis_digest:
  integration_digest:
  required_accounting_complete:
  required_checks_passed:
  blocking_conflicts_resolved:
  residual_risks:
  next_run_or_escalation:
    recommendation_only: true
    next_owner:
    authorization_granted: false
    execution_started: false
  finalizer_handoff:
    # Present only for ready_for_finalizer or accepted_with_residual_risk.
    evidence_ref:
    finalizer_may_modify: false
    merge_authority_granted: false
evidence:
  commits:
  prs:
  reports:
  logs:
  manual_review_notes:
rollback_plan:
  rollback_scope:
  files_to_revert:
  state_to_restore:
  risks:
final_status:
next_owner:
next_action:
open_questions:
blocked_by:
```

## Done Requirements

`done` requires:

- required surfaces updated;
- new entities mapped;
- checks/evidence recorded;
- version/changelog impact reviewed;
- non-blocking legacy gaps backfilled, task-created or explicitly deferred;
- rollback note present;
- next owner clear;
- protected gates resolved or recorded.

## Result Integration Adoption

When a package uses Result Integration, its manifest records the exact
structured aggregate by reference together with the status and digests above.
`ready_for_finalizer` and `accepted_with_residual_risk` must reference the
digest-bound `finalizer_handoff`; Finalizer verifies it without changing the
synthesis. `integration_incomplete`, `rerun_required`,
`owner_decision_required` and `rejected` must identify the next route instead
of presenting a Finalizer-ready handoff. A rerun or escalation remains a
recommendation and cannot grant authorization or start execution.

## Draft PR Rule

A draft PR may be evidence for ManualIntegrationMode, but it is dirty by default and must not be consumed as final automation input until promoted out of draft and final gates pass.
