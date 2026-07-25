# Integrator / Finalizer Package Flow Clarification

Date: 2026-06-09
Status: reusable core clarification
Source project: `coindmit-cmyk/e-shop`

## Owner Direction

`Auto Integrator` prepares a package that is ready to return to the project integration branch. It should not block the whole package when only some items need a human decision, rework, worker fix, dispatcher routing, architect decision or cleanup review.

`Auto Finalizer` may take the verified safe package from `Auto Integrator` and return it to the project integration branch when the project workflow grants that authority.
For safe package routes this is `auto_merge_to_develop`.

## Core Rule

```text
Integration blockers are item-level by default.
Only shared-path conflicts, failed global checks, broken integration base,
unclear merge authority or package-wide ownership conflict may block the whole package.
```

## Required Integrator Handoff

```json
{
  "integration_status": "integration_package_ready",
  "package_branch": "integrator/<BATCH-ID>-short-name",
  "base_branch": "develop",
  "base_sha": "<sha>",
  "ready_to_finalize": ["TASK-1", "TASK-2"],
  "needs_human": ["TASK-3"],
  "blocked": ["TASK-4"],
  "excluded_from_package": ["TASK-3", "TASK-4"],
  "checks": [],
  "finalizer_authority_required": "owner-authorized integration-branch return"
}
```

For mixed stacks, `integration_status = partial_package_ready` is valid when at
least one item is ready and the rest are routed away through:

```text
needs_rework
needs_worker_fix
needs_dispatcher
needs_architect
needs_human
cleanup_candidates
excluded_from_package
branch_dispositions
```

`Auto Integrator` must not mark excluded items as final. It records the smallest next action for each excluded item and lets the safe package continue.

Cleanup candidates are proposals only. `Auto Integrator` may record stale,
duplicate, superseded or no-op PRs/branches as `cleanup_candidate` with evidence,
but it must not close PRs or delete branches directly.

## Required Finalizer Behavior

`Auto Finalizer` may return a verified `integration_package_ready` or
`partial_package_ready` package to the project integration branch only when the
workflow is owner-authorized.

After package return or merge evidence exists, it synchronizes task queue, task pages, locks, changelog/release notes, final reports and residual risks.

Excluded items remain in `needs_human`, `blocked`, `needs_rework`,
`needs_worker_fix`, `needs_dispatcher`, `needs_architect`,
`cleanup_candidate` or another evidence-backed non-final status. They must not
stop finalization of unrelated safe package items.

## Suggested Core Files To Align

```text
agent-core/.agent/agents.md
agent-core/.agent/workflows.md
agent-core/.agent/prompts/auto-integrator.md
agent-core/.agent/prompts/auto-finalizer.md
agent-core/docs/automation/AI_AUTOMATION_LAYER.md
agent-core/docs/automation/GPT_AGENT_WORK_TOPOLOGY.md
agent-core/docs/automation/BRANCH_COMMIT_INTEGRATION_PROTOCOL.md
agent-core/docs/automation/INTEGRATION_FINALIZATION_PROTOCOL.md
agent-core/docs/automation/AUTO_INTEGRATOR_OPERATING_CONTRACT.md
agent-core/docs/automation/LOCAL_CODEX_DESKTOP_RUNBOOK.md
agent-core/docs/automation/WORKER_PROFILES.md
agent-core/docs/automation/TASK_TRACEABILITY_CONTRACT.md
```

Project templates should keep production/release branch returns disabled unless a project-specific owner directive grants that authority.
