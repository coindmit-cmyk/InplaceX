# Integration And Finalization Protocol

Date: 2026-06-09
Status: reusable Phase 1 contract

## Purpose

This document defines the two governance layers that sit after worker execution:

- `Auto Integrator`: assembles branches and PRs into one coherent integration order and concrete unblock path.
- `Auto Finalizer`: returns verified safe Integrator packages to `develop` when the finalizer merge gate passes, then synchronizes accepted state. Owner approval is required only for blocked, risky, ambiguous, production/release or owner-only decisions.

These roles are not worker complexity levels. They do not take `S/M/L/XL` implementation tasks. They protect the project from PR drift, stale locks, premature `done` statuses and undocumented final state.

## Auto Integrator

The detailed run procedure is defined in `AUTO_INTEGRATOR_OPERATING_CONTRACT.md`.

Main job:

- take a fresh GitHub snapshot before integration decisions: Git remotes, Issues, open PRs, recent commits, task queue, locks and changed paths;
- compare ready worker/director/architect/module PRs against the integration branch;
- detect path overlap, merge order, stale branches, missing checks and conflicting task statuses;
- prepare a dedicated integrator branch/PR, integration report or structured handoff when the project workflow allows it;
- convert "blocked" stacks into an ordered route: merge as-is, rebase, split, rebuild, close as superseded or send to owner decision;
- own PR readiness before finalization by resolving stale base, `DIRTY`/`CONFLICTING` state, oversized or unrelated diffs and missing PR evidence;
- update task/PR reports with integration readiness and blockers.
- consume worker draft PRs as source artifacts for a package branch/PR when they
  have task ID, worker evidence, changed paths, base/head evidence, no forbidden
  paths and no unreviewed high-risk scope.

Allowed outputs:

```text
integration_ready
integration_package_ready
partial_package_ready
integration_blocked
needs_rework_routed
cleanup_candidates_found
no_ready_items
merge_order
conflict_report
stale_branch_report
checks_required
rebuild_order
split_plan
superseded_pr_report
cleanup_candidate_report
finalizer_handoff
pr_ready_for_review
```

`integration_blocked` is not a stopping point by itself. It must include:

- canonical integration base branch and SHA;
- inspected PRs/branches and current heads;
- exact conflict files or scope drift;
- the smallest safe next branch/PR actions;
- owner decisions required, if any;
- work intentionally left for Auto Finalizer.

Before `Auto Integrator` commits, pushes, marks `integration_package_ready` or hands work to `Auto Finalizer`, it must refresh GitHub again. If the integration base, candidate PR heads, queue state or locks changed, it must recompute the plan or record a stale-snapshot blocker instead of handing off an outdated package.

Limits:

- does not invent product or architecture decisions;
- does not approve owner-only decisions, merge production/release branches or bypass Finalizer gate authority;
- does not overwrite another role's work to resolve conflicts silently;
- does not perform finalizer migrations such as marking `done`, releasing locks after acceptance or synchronizing final release state;
- does not mark `agent_done` as `done`;
- does not leave a messy stack as only "blocked" when a safe integration branch/report can reduce uncertainty;
- does not block safe package items because an unrelated branch needs rework, worker fix, dispatcher routing, architect decision, human input or cleanup;
- sends unclear merge order, behavior conflict or ownership conflict to `needs_human`.

## PR Readiness Before Finalization

If a task is `agent_done` but finalization is blocked by PR state, `Auto Integrator` must resolve the PR state before `Auto Finalizer` runs.

Required actions:

- update the PR branch from the current integration branch;
- resolve `DIRTY` worktree state, `CONFLICTING` merge state, stale base and oversized or unrelated diffs;
- preserve the previous PR head in a backup branch before rewriting history or force-pushing;
- link orphan task commits to the accepted PR, integration branch or handoff evidence;
- record local checks and GitHub check/status evidence in the PR body and task records;
- move the PR from draft to ready for review when it is clean, scoped and review-ready.

`agent_done` remains `agent_done` until owner approval, merge evidence or accepted-state evidence from a gate-approved Finalizer return gives `Auto Finalizer` authority to record `owner_approved` or `done`.

## Package Flow

The package flow is defined in `INTEGRATOR_FINALIZER_PACKAGE_FLOW_CLARIFICATION.md`.

Integration blockers are item-level by default.

Only these blockers may block the whole package:

- shared-path conflicts;
- failed global checks;
- broken integration base;
- unclear merge authority;
- package-wide ownership conflict.

If unrelated safe items can continue, `Auto Integrator` should write an `integration_package_ready` handoff:

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
  "finalizer_authority_required": "finalizer merge gate for develop; owner decision only for risky/ambiguous/blocked items"
}
```

If only part of the stack can continue, use `partial_package_ready`:

```json
{
  "integration_status": "partial_package_ready",
  "package_branch": "integrator/<BATCH-ID>-short-name",
  "base_branch": "develop",
  "base_sha": "<sha>",
  "ready_to_finalize": ["TASK-1"],
  "needs_human": ["TASK-2"],
  "blocked": [],
  "needs_rework": ["TASK-3"],
  "needs_worker_fix": ["TASK-4"],
  "needs_dispatcher": ["TASK-5"],
  "needs_architect": ["TASK-6"],
  "cleanup_candidates": [
    {
      "branch": "codex/old-worker/foo",
      "pr": 12,
      "reason": "superseded by TASK-1 package",
      "evidence": ["duplicate task", "conflicting stale base"],
      "close_pr": "candidate",
      "delete_remote_branch": "candidate"
    }
  ],
  "excluded_from_package": ["TASK-2", "TASK-3", "TASK-4", "TASK-5", "TASK-6", "codex/old-worker/foo"],
  "branch_dispositions": [
    {
      "branch": "remote/aistudio/auto-worker/TASK-1",
      "task_id": "TASK-1",
      "disposition": "ready_to_finalize",
      "reason": "checks and scope are clean",
      "next_owner": "finalizer"
    },
    {
      "branch": "remote/aistudio/auto-worker/TASK-3",
      "task_id": "TASK-3",
      "disposition": "needs_rework",
      "reason": "conflicts with package queue state",
      "next_owner": "worker",
      "rejection_detail": {
        "summary": "TASK-3 cannot enter this package because it changes stale coordination state and conflicts with the package branch.",
        "blocking_reasons": ["task_queue.json conflict with current integration base"],
        "evidence": ["merge-tree conflict: AiStudio/Task_manager/task_queue.json"],
        "checked_alternatives": ["coordination-only files were excluded, but the branch still needs a rebuild"],
        "recommended_next_action": "Rebuild TASK-3 from the current integration base and resubmit with only task-scoped changes.",
        "next_owner": "worker",
        "owner_decision_needed": null
      }
    }
  ],
  "checks": [],
  "finalizer_authority_required": "finalizer merge gate for ready_to_finalize only; owner decision only for risky/ambiguous/blocked items"
}
```

Excluded items remain non-final and must not stop finalization of unrelated safe package items.
`Auto Finalizer` may consume only `ready_to_finalize` items from a partial
package. It must ignore `needs_rework`, `needs_worker_fix`, `needs_dispatcher`,
`needs_architect`, `needs_human`, `cleanup_candidates` and
`excluded_from_package` except to preserve their routing evidence. Routed-away
items must keep `rejection_detail` so the next Worker, Dispatcher, Architect or
human reviewer can understand why Auto Integrator rejected them without rerunning
the whole integration.

Typical branch:

```text
integrator/<BATCH-ID>-short-name
```

## Auto Finalizer

Main job:

- run after `Auto Integrator` provides a verified package, after merge evidence exists, or after owner approval/risk waiver for owner-only cases;
- take a fresh GitHub snapshot before finalization: fetch/prune remotes, read current PR/merge/approval evidence, current task queue, locks and recent commits;
- confirm accepted commits are present on the integration branch before stronger task statuses are recorded;
- synchronize task records, task pages, locks, changelog/release notes and final reports to match the accepted Git state;
- move tasks from `agent_done` or `review` to `owner_approved` or `done` only when the finalizer merge gate, merge evidence, owner approval or accepted-state evidence supports that exact status;
- release completed locks, or mark stale/invalid locks with a clear residual-risk note;
- mark completed task, worker, integrator, staging, experiment or finalizer branches as `cleanup_candidate` only after accepted commits are present on the integration branch;
- treat worker draft PRs as consumed/superseded source artifacts after their
  changes are safely represented by the accepted package;
- archive transient run artifacts after a successful package return so only code, durable documentation and current coordination state remain in the active project view;
- record remaining risks and the next recommended task after finalization.

When the workflow grants authority, `Auto Finalizer` may return a verified `integration_package_ready` package to the project integration branch. After package return or merge evidence exists, it synchronizes accepted state for ready package items only. Excluded items remain in `needs_human`, `blocked` or another evidence-backed non-final status.

Auto Finalizer must not merge worker draft PRs directly. Worker draft PRs are
source artifacts for Auto Integrator. The merge target for automatic
finalization is the verified integrator package branch/PR.

When a strong or manual Integrator review has already produced and merged a
separate integration PR, Finalizer must reconcile that accepted merge instead
of replaying the Worker branch. `accepted_integration_reconciler.py` is the
standard bridge. It is dry-run-first and requires an exact task ID, Worker
result SHA, source SHA when present, a merged non-draft PR on the configured
integration branch, passing CI, merge-commit ancestry and the required
Integration Manifest evidence. Strong/high-risk tasks additionally require
the manifest to bind the exact source and Worker commits and to contain passing
Capability Preservation and Integration Protection results. Missing,
ambiguous or failed evidence leaves the task unchanged.

After the gate passes, the reconciler records `integration_recorded` and
`finalization_recorded`, updates only the exact linked task, releases its task
lock and leaves repository-hygiene source-PR closure to the normal next cycle.
The Status Orchestrator runs this reconciliation before Full Intake so a
finalized clean-rebuild child can unblock its parent without manual queue
editing. When reconciliation applies at least one task, the orchestrator must
commit and push the queue, event and lock state before Full Intake or any other
downstream command may fetch, rebase or realign the managed checkout. Only a
non-deferred successful state sync opens that barrier. Failed, ambiguous or
archived/deferred sync stops the cycle so accepted Finalizer evidence cannot be
silently replaced by older canonical state.

Allowed outputs:

```text
finalized
integration_package_returned
finalization_blocked
owner_approved_recorded
done_recorded
cleanup_candidate_recorded
stale_lock_report
release_note_ready
post_finalizer_artifacts_archived
residual_risk_report
next_task_recommendation
risk_waiver_recorded
```

## Post-Finalizer Artifact Cleanup

After `Auto Finalizer` has safely merged or confirmed accepted work on
`develop`, it must clean the active project view:

- keep product code, tests, durable documentation, `.agent` rules, current
  `task_queue.json`, locks and owner directives visible;
- archive transient evidence such as `integration_handoff.json`,
  `integrator_preflight.json`, readiness reports, integration batches and
  process logs under `old/agent-runs/finalized/<timestamp>/`;
- write a cleanup manifest into the archive so the run can be audited later;
- commit and push the cleanup/archive state to GitHub.

Use deterministic cleanup first:

```powershell
python scripts\agent_control\post_finalizer_cleanup.py `
  --project-root D:\Work\Project `
  --json

python scripts\agent_control\post_finalizer_cleanup.py `
  --project-root D:\Work\Project `
  --apply
```

The script defaults to dry-run. Destructive deletion is not the default cleanup
mode; archive first unless the artifact is explicitly known to be disposable.

## Post-Run Cleanup Contract

All agents must follow `AGENT_CLEANUP_CONTRACT.md` before reporting completion.
Cleanup is part of the role result, not optional housekeeping.

Agents should remove or archive only artifacts they can prove are no longer live
evidence. Active locks, open PRs, unresolved review items, non-final task
evidence, owner notes, source files and artifacts referenced by handoffs or
blockers must stay visible.

Every run report must state what was archived, deleted, recorded as a cleanup
candidate and intentionally left behind.

## Branch Cleanup Candidates

`Auto Finalizer` does not delete branches directly. It records or consumes
cleanup candidates after finalization evidence exists, then a deterministic
cleanup script verifies merge state and performs the optional deletion.

`Auto Integrator` may also propose cleanup candidates for old/stale/conflicting
PRs and branches, but those are proposals only. They require cleanup-script,
Finalizer or owner verification before PR closure or branch deletion.

Finalizer may mark a branch as `cleanup_candidate` only when all of these are true:

- the task or package item is finalized, owner-approved or has merge evidence;
- the branch belongs to a temporary lane such as `remote/`, `local/`, `integrator/`, `staging/`, `experiment/` or `finalizer/`;
- accepted commits are present on the configured integration branch;
- no open PR, unresolved review, active lock or non-final task still depends on that branch.

Finalizer must not mark stable branches, integration branches, release branches, tags or `old/*` archive references as cleanup candidates.

Recommended activity-state signal:

```json
{
  "signal": "cleanup_candidate",
  "source": "auto_finalizer",
  "created_at": "2026-06-10T00:00:00Z",
  "task_id": "TASK-001",
  "branch": "remote/<machine-id>/<worker-id>/<task-id>",
  "branch_scope": "remote",
  "base_branch": "develop",
  "merge_evidence": "accepted commits present on develop",
  "summary": "Branch can be checked by cleanup script after finalization."
}
```

The cleanup script must default to dry-run mode. Destructive deletion requires an explicit flag and a successful merge/protection check.

Before `Auto Finalizer` records final task status, releases locks or records cleanup candidates, it must refresh GitHub again and compare the current snapshot with the evidence it started from. If the integration branch, accepted PR, approval/merge evidence, task queue or locks changed, it must stop the final write and record `finalization_blocked` or recompute from the fresh state.

Limits:

- does not mark `owner_approved` or `done` before owner approval, merge evidence or explicit owner risk waiver supports that exact status;
- does not release production or tag a version unless project rules and owner approval explicitly allow it;
- does not hide failed checks, unresolved PR comments, missing docs or stale locks;
- does not repair PR cleanup, mergeability, draft state, missing check/status evidence or orphan commit linkage as finalization work;
- does not delete branches directly; it only records cleanup candidates for deterministic script verification;
- records `finalization_blocked` and routes unresolved PR readiness or evidence issues back to `Auto Integrator` or the owner;
- sends missing approval, failed checks, release authority or unclear acceptance to `needs_human`.

Typical branch:

```text
finalizer/<BATCH-ID>-short-name
```

## Lifecycle Placement

```text
Director / Architect
  -> Dispatcher / Auto Make Tasks
  -> Auto Workers
  -> Auto Integrator
  -> Auto Finalizer
  -> needs_human only for blocked / risky / ambiguous work
  -> release branch / production only when project rules allow
```

## Required Checks

Auto Integrator should verify:

- fresh GitHub snapshot time, base SHA and candidate head SHAs before integration work starts;
- final pre-handoff GitHub snapshot time, base SHA and candidate head SHAs before package handoff;
- PR base branch and target integration branch;
- changed path overlap against open PRs and recent commits;
- changed path overlap uses product/runtime paths from `integration_changed_paths`; coordination sync paths such as queue, locks, events and process logs are recorded separately and do not block unrelated product items by themselves;
- task IDs, branch names, commits and PR links;
- status is `review` or explicitly ready for integration;
- required checks are present and their current result is recorded;
- queue and locks do not show duplicate active work;
- dirty local work is isolated before any integrator branch is prepared;
- broad PRs are narrowed, split or explicitly routed to rebuild instead of being accepted as-is.

Auto Finalizer should verify:

- fresh GitHub snapshot time and accepted-state evidence before finalization starts;
- final pre-write GitHub snapshot time before statuses, locks or cleanup candidates are recorded;
- finalizer merge gate passes for safe `develop` package return, or owner approval / merge evidence / explicit risk waiver exists for owner-only cases;
- PR state is clean and review/merge-ready; otherwise return PR readiness work to Auto Integrator;
- accepted commits are present on the integration branch before stronger task statuses are recorded;
- cleanup candidates are temporary branches and are merged into the integration branch before being recorded;
- worker draft PRs included in a finalized package are marked consumed,
  superseded or cleanup candidates only after the package is present on the
  integration branch;
- task queue, task pages and PR state agree;
- locks are released, stale or explained;
- changelog/release notes exist when behavior, architecture, API, workflow or release state changed;
- final status is not stronger than the evidence.

## Status Authority

| Status | Auto Integrator | Auto Finalizer |
| --- | --- | --- |
| `planned` | read-only | read-only |
| `in_progress` | may report stale/duplicate | may report stale/duplicate |
| `review` | may confirm integration readiness | may record `owner_approved` or `done` only when finalizer-gate, merge or accepted-state evidence supports that exact status |
| `agent_done` | owns PR readiness and evidence, but must not mark final | may record `owner_approved` or `done` only when finalizer-gate, merge or accepted-state evidence supports that exact status |
| `owner_approved` | read-only unless owner explicitly asked to record approval | may record with owner approval, merge evidence or explicit owner risk waiver |
| `done` | no | may record only after merge evidence or owner-approved final acceptance evidence |
| `blocked` | may set for integration blockers | may set for finalization blockers |
| `needs_human` | may set for owner/merge/authority questions | may set for owner/release/acceptance questions |

## Final Rule

Auto Finalizer merge target:

- target branch for safe automatic finalization is `develop`;
- target can be overridden only by explicit project owner gate and recorded authority.

Auto Finalizer may auto-merge a verified package into `develop` when all gates pass:

- handoff validation is clean (`validate_integration_handoff.py` with no blocking errors);
- `integration_status` is `integration_package_ready` or `partial_package_ready`;
- `package_branch` starts with `integrator/`;
- `base_branch` is `develop` (or explicit owner-approved override);
- `ready_to_finalize` is non-empty;
- required acceptance checks are present and passed (`checks` and/or explicit status evidence);
- no unresolved merge blockers (`mergeable != false`, `merge_conflicts` empty);
- owner/release policy and finalizer authority are satisfied (no unrecorded high-risk, ambiguous or owner-only decisions);
- all non-ready routed items are explicit in `needs_human`, `blocked`, `needs_rework`, `needs_worker_fix`, `needs_dispatcher`, `needs_architect`, `cleanup_candidates`, or `excluded_from_package`.

If any gate fails, route blocked/risky/ambiguous work to `needs_human` with concrete evidence and next action.

The final project structure is the accepted Git tree plus synchronized task state. A task is not final merely because a worker finished it; it is final only when integration evidence, accepted-state evidence and documentation state agree.
