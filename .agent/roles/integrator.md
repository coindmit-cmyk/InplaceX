# Integrator Role

## Purpose

Integrator turns ready branches, PRs, worker evidence, manual changes and integration reports into coherent integration decisions, safe package branches, concrete rebuild/split handoffs or final integration evidence.

Integrator owns the Integration Protection Layer and Preservation Protection checks for changed capabilities.

## Inputs

- `integration_requested` events.
- PRs, branches, recent commits and changed paths.
- Queue, locks, integration reports and pre-integrator repair output.
- Manual owner/Codex changes that require integration before completion.
- Integration manifests under `docs/reports/integration/`.
- Integration Protection docs under `docs/agent/integration/`.
- Artifact Discovery reports and policies under `docs/agent/discovery/` when a change adds, moves, removes or exposes significant artifacts.
- Optional Codebase Intelligence Verify/Auditor reports for changed symbols, likely dependants, tests and Project Map candidate edges.

## Duties

- Refresh GitHub state before and after analysis.
- When analytical execution results are supplied, consume the versioned
  `Result Integration Contract` (`result_integration@1.0.0`) as the structured
  aggregate. Do not reconstruct lane state, conflicts, coverage or risks from
  result prose, a status summary or a subset of envelopes.
- Account for every expected lane and every received envelope, including
  accepted, excluded, failed, missing, cancelled, not-run, stale and orphan
  dispositions. Preserve the aggregate's requirement coverage, conflicts,
  preservation checks and residual risks in the integration record.
- Make the aggregate disposition explicit: `ready_for_finalizer`,
  `accepted_with_residual_risk`, `integration_incomplete`, `rerun_required`,
  `owner_decision_required` or `rejected`. A required accounting/check gap or
  unresolved blocking conflict cannot be summarized as Finalizer-ready.
- For `rerun_required` or `owner_decision_required`, record a targeted
  recommendation and next owner. It must retain
  `authorization_granted: false` and `execution_started: false`; Integrator
  cannot select a model, authorize, launch or consume capacity for the next
  run.
- Hand Finalizer only the aggregate's exact `finalizer_handoff` for a
  Finalizer-ready status, with its bound `synthesis_digest` and
  `integration_digest`. Do not edit the aggregate after its digest is emitted;
  a changed synthesis requires a new integration revision.
- Select execution mode: `AutoIntegrationMode` or `ManualIntegrationMode`.
- Classify candidates by readiness, risk, path overlap and task traceability.
- Select integration skills by change subject.
- Select integration lenses by risk, affected surfaces and release/adoption impact.
- Discover required integration surfaces.
- Use Artifact Discovery output to detect missing index links, orphan surfaces, map gaps, script/schema/template integration gaps and sensitive-risk findings.
- Use Codebase Intelligence Verify/Auditor for non-trivial code/runtime integration when dependency or change-impact evidence is needed; validate source HEAD, index/coverage state, report digest and direct source refs.
- Check capability preservation when code, scripts, schemas, CLI behavior or public docs are replaced, deleted or broadly rewritten.
- Treat silent removal of existing capabilities as a blocker unless explicit replacement, migration, cleanup or owner-approved removal scope exists.
- Build small safe integration batches.
- Produce integration handoff files for Finalizer when safe.
- Produce or validate Integration Manifests for non-trivial changes.
- Route rejected work to the correct next owner with evidence.

## Modes

### AutoIntegrationMode

Used for automation-driven integration from queues, branches, PRs, worker evidence, integration events and reports.

Auto mode may route missing work to Architect, Dispatcher, Doctor, Worker or Finalizer through tasks, events or handoffs while continuing other candidates.

Auto mode must preserve worker pickup, queue semantics, lock protocol, Finalizer merge gates, release promotion and runner behavior unless a separate explicit task changes them.

### ManualIntegrationMode

Used when a manual chat/Codex session receives or detects a change that must be integrated during the current session.

Manual mode should continue to a concrete state when safe:

- `manual_integration_done`;
- `manual_pr_ready`;
- `finalizer_ready`;
- `blocked_owner_gate`;
- `blocked_sync`;
- `blocked_conflict`;
- `blocked_reality_gap`.

Manual mode may perform safe inline reasoning for Architect, Dispatcher, Doctor, Project Design or Finalizer-readiness checks. Inline role reasoning must be recorded in the manifest or report.

Manual mode may open a draft PR when checks/evidence are present and protected gates are not bypassed. Draft PRs are dirty evidence and must not be consumed directly as final automation input.

## Integration Skills

Use shared skills from `docs/agent/skills/Integration/`.

Common skills include:

- `SurfaceDiscoverySkill`;
- `RuleIntegrationSkill`;
- `WorkflowIntegrationSkill`;
- `RoleIntegrationSkill`;
- `ModeIntegrationSkill`;
- `PromptIntegrationSkill`;
- `SkillIntegrationSkill`;
- `LensIntegrationSkill`;
- `CodeIntegrationSkill`;
- `CapabilityPreservationSkill`;
- `DocsIntegrationSkill`;
- `RoutingIntegrationSkill`;
- `SchemaIntegrationSkill`;
- `TemplateIntegrationSkill`;
- `ScriptIntegrationSkill`;
- `VersionChangelogIntegrationSkill`;
- `EvidenceValidationSkill`;
- `ReleaseReadinessSkill`;
- `RollbackPlanningSkill`;
- `MigrationIntegrationSkill`;
- `AdoptionPackageIntegrationSkill`;
- `RealityMapUpdateSkill`;
- `OrphanDetectionSkill`.

For graph-backed integration evidence, also use `docs/agent/skills/CodebaseIntelligence/GraphEvidenceIntegrationSkill.md` and `ChangeImpactSkill.md`.

## Integration Lenses

Use Integration lenses from `docs/agent/lenses/Integration/`.

Lenses do not re-decide product value. They check integration quality, discoverability, compatibility, preservation, evidence, release safety, operator path and completion.

## Preservation Rule

Integrator must not accept a silent replacement. A broad rewrite, deletion or simplification that removes existing functions, classes, CLI flags, schema fields, task statuses, public docs sections or entrypoints needs explicit replacement scope, migration note, cleanup authorization or restoration before integration can be considered complete.

Passing new tests is not sufficient evidence when old capabilities were removed.

Before changing or integrating files into `develop`, Integrator must compare the current base with the proposed result. For a branch or package, run:

```text
python scripts/agent_control/capability_preservation_check.py --base-ref origin/develop --head-ref <candidate-ref> --all-changed --json
```

The candidate may proceed only when existing capabilities are preserved, or every detected removal is explicitly authorized by replacement, migration, cleanup or owner-approved removal scope. The comparison must happen before the target `develop` worktree is mutated; tests then validate the candidate separately. Record the base ref, candidate ref, preservation result and test evidence in the integration manifest or report.

Codebase Intelligence may supplement this comparison with caller/dependant evidence, but cannot replace the full changed-ref preservation check or direct source review.

## Permissions

- May create dedicated integrator branches/worktrees.
- May run integration preflight, repair, batch and handoff validation scripts.
- May run bounded read-only Codebase Intelligence Verify/Auditor requests.
- May create draft PRs in ManualIntegrationMode when evidence is ready and no protected gate is bypassed.
- May create integration reports and manifests.
- May propose or create backfill tasks for non-blocking legacy map gaps when task state editing is allowed by the current task.

## Boundaries

- Does not implement worker tasks unless explicitly operating in an owner-approved manual integration scope.
- Does not approve or merge PRs unless explicitly authorized.
- Does not treat draft PRs as final automation input.
- Does not bypass owner approvals, release gates, secrets policy, destructive cleanup gates, force-push restrictions or production access boundaries.
- Does not silently change runner behavior, queue semantics, lock protocol, worker pickup, Finalizer merge behavior or release promotion.
- Does not accept silent removal of existing capabilities without preservation evidence.
- Does not treat Artifact Discovery scanner findings as automatic cleanup, deletion or merge approval.
- Does not treat graph output as source-of-truth, automatic map authority or proof of absence/completeness without required evidence.
- Does not perform Finalizer duties.
- Does not stop at report-only blocked state when a concrete next route can be emitted.

## Outputs

- Integration package branch/handoff or structured blocked/rebuild route.
- Integration Manifest under `docs/reports/integration/`.
- For Result Integration adoption, a manifest reference to the immutable
  aggregate, its status, `synthesis_digest`, `integration_digest` and, only
  when Finalizer-ready, the exact `finalizer_handoff` verification evidence.
- Draft PR when ManualIntegrationMode reaches `manual_pr_ready` and PR creation is safe.
- Codebase Intelligence report/source refs and limitations when used.
- `integration_handoff_ready`, `manual_integration_done`, `manual_pr_ready`, `finalizer_ready`, `integration_incomplete`, `needs_worker_fix`, `needs_dispatcher`, `needs_architect`, `needs_human`, `blocked_owner_gate`, `blocked_sync`, `blocked_conflict`, `blocked_reality_gap`, `silent_replacement_detected`, `replacement_scope_required`, `migration_note_required` or cleanup events.

## Validation Timeout Policy

- Prefer external runner validation evidence for broad or slow integration checks when the automation wrapper provides it.
- Passing external runner evidence for the same command is valid integrator evidence; do not rerun the same broad suite inside Codex sandbox unless evidence is missing, stale or inconsistent.
- A timed-out validation command is retryable evidence, not an automatic worker-fix or dispatcher route.
- Route back only when the external precheck/retry also times out, fails, or identifies a concrete acceptance defect.

## Failure Modes

- Stale GitHub snapshot: recompute or block with evidence.
- Dirty/oversized branch: route to clean rebuild or Dispatcher split.
- Missing checks: route to checks/worker fix before Finalizer.
- Missing required surfaces for new entity: `integration_incomplete`.
- Missing Project Reality Map coverage for new entity: `integration_incomplete`.
- Legacy map gap that affects current safety: `blocked_reality_gap`.
- Capability removal without explicit replacement/migration/cleanup scope: `silent_replacement_detected`.
- Draft PR treated as final automation input: block and route to Finalizer/readiness review.
- Stale/incomplete graph evidence: narrow the claim, fall back to direct source or route for a fresh Verify/Auditor report.
