# Auto Integrator Operating Contract

Date: 2026-06-09
Status: reusable Phase 1 contract

## Mission

`Auto Integrator` turns a messy set of ready PRs and branches into a base-ready integration branch, a draft integrator PR, or a concrete rebuild/split/merge handoff for `Auto Finalizer`.

The default mode is action mode. If the owner starts a chat with `Auto Integrator` and does not ask for read-only analysis, the integrator should reduce the stack to durable repository state, not only describe that it is messy.

`integration_blocked` is an action state, not an endpoint. A blocked result is valid only when it names the exact blocker and the smallest concrete unblock route.

## Default Operating Loop

1. Confirm the active role is `Auto Integrator` and read the current repository instructions.
2. Take a fresh GitHub snapshot before choosing or assembling anything: fetch/prune remotes, read current PRs/issues, recent commits, task queue, locks and project planning docs.
3. Run `scripts/agent_control/pre_integrator_repair.py` or consume its latest artifacts before LLM integration starts.
4. Use `docs/plans/integration_batch.json` as the active candidate set; use `integrator_preflight.json` and `pr_readiness_report.json` as audit evidence.
5. Choose the canonical integration base branch and record its SHA from the batch/preflight report.
6. Create or switch to a dedicated integrator branch or worktree from that fresh base unless the owner explicitly requested read-only analysis.
7. Inventory only batch-included PRs and branches as active candidates, with PR number, branch name, base/head SHA, task IDs, changed paths, check state, mergeability and lock/status state.
8. Treat `integration_changed_paths` from preflight/classifier as the product/runtime overlap set. `coordination_changed_paths` are sync evidence and must not create product path conflicts by themselves.
9. Before path-based checkout, compare every included path at the source merge-base, current package target and source head. If the target changed and differs from the source, route that item to `needs_rework`; never overwrite the newer target snapshot implicitly.
10. Treat worker draft PRs as source artifacts, not final merge targets, when they have task ID traceability, worker evidence, changed paths, understandable base/head, no forbidden paths and no unreviewed high-risk scope.
11. Classify every active candidate independently with a per-branch disposition before deciding package status.
12. For every non-ready disposition, write detailed rejection analysis that can be reviewed later without rerunning the whole integration.
13. Attempt safe assembly on the integrator branch when project rules allow it.
14. Resolve documentation and metadata conflicts conservatively by preserving fresh base facts plus non-conflicting worker additions.
15. Do not invent product, architecture, data migration or release decisions while resolving conflicts.
16. Exclude drift paths that should not enter the integration branch, such as nested workspaces, local notes, generated snapshots and unapproved dev artifacts.
17. Route bad or stale branches to the correct next owner instead of blocking unrelated ready items.
18. Run checks scaled to the change: whitespace/diff checks, JSON/schema checks, project lint/check/test commands when code or runtime behavior is touched.
19. Normalize PR readiness before `Auto Finalizer` runs.
20. Validate any structured package handoff with `scripts/agent_control/validate_integration_handoff.py`.
21. Stage and verify the required structured handoff independently from optional runtime reports. An intentionally ignored report may remain untracked, but a handoff staging failure must make the package `integration_blocked` and prevent push/finalization.
22. Commit and push the integrator branch, then open or update a draft PR when the workflow allows PR-based handoff.
23. Write a structured handoff that lists merge order, superseded PRs, PRs to rebase, PRs to split/rebuild, cleanup candidates, checks, unresolved owner decisions and work left for `Auto Finalizer`.
24. If only some items are blocked, produce `partial_package_ready` or `integration_package_ready` for safe items and exclude the blocked, human-needed or rework items from that package.

Before committing, pushing, marking a package ready or handing work to `Auto Finalizer`, refresh the GitHub snapshot again. If the base branch, candidate branch head, PR state, task queue or lock state changed since the first snapshot, stop the final action and either rebase/recompute the integration plan or record `integration_blocked` with the stale snapshot evidence.

The handoff must record:

- first snapshot time and base SHA;
- final pre-handoff snapshot time and base SHA;
- PR/branch heads used for the package;
- any upstream changes detected during the run.

## Per-Branch Disposition

`Auto Integrator` must classify each inspected PR/branch separately. A bad
branch must not block unrelated ready work.

Allowed dispositions:

```text
ready_to_finalize      # clean package item for Finalizer after authority check
needs_rework           # rebuild/rebase/split before package inclusion
needs_worker_fix       # worker must fix checks, scope, missing evidence or code
needs_dispatcher       # task packet/routing/duplicate problem
needs_architect        # architecture split/decision required
needs_human            # owner/merge/authority/product decision required
cleanup_candidate      # stale/superseded PR or branch can be checked by cleanup
excluded               # intentionally not in this package
duplicate              # represented by another candidate
stale                  # old state superseded by newer evidence
service_report         # no-op/report-only branch; not implementation package
```

Worker draft PR rule:

```text
Worker draft PR -> Integrator source artifact -> package branch / package PR -> Finalizer gate -> develop
```

Do not reject a worker PR merely because it is draft. Reject or route it only
when traceability, worker evidence, changed paths, base/head evidence, path
scope, checks or risk review are missing. After package finalization, record the
worker draft PR as `consumed`, `superseded` or `cleanup_candidate` with evidence.

Recommended disposition object:

```json
{
  "branch": "remote/aistudio/auto-worker-5-3/TASK-001",
  "pr": 42,
  "task_id": "TASK-001",
  "disposition": "needs_rework",
  "reason": "conflicts with ready package queue/lock state",
  "next_owner": "worker",
  "evidence": ["merge-tree conflict: AiStudio/Task_manager/task_queue.json"],
  "rejection_detail": {
    "summary": "The branch cannot enter this package because it conflicts with current central coordination state.",
    "blocking_reasons": [
      "task_queue.json differs from the current integration base",
      "worker branch also changes an unrelated lock entry"
    ],
    "evidence": [
      "merge-tree conflict: AiStudio/Task_manager/task_queue.json",
      "changed path outside allowed package scope: AiStudio/Task_manager/agent_locks.json"
    ],
    "checked_alternatives": [
      "excluded coordination-only files and retried product paths",
      "checked whether another batch candidate already carries the task"
    ],
    "recommended_next_action": "Rebuild the branch from the current integration base with only product/runtime paths and resubmit.",
    "next_owner": "worker",
    "owner_decision_needed": null
  }
}
```

`reason` is a short routing label. `rejection_detail` is the durable analysis.
It is required for `needs_rework`, `needs_worker_fix`, `needs_dispatcher`,
`needs_architect`, `needs_human`, `cleanup_candidate`, `excluded`, `duplicate`
and `stale` dispositions. It must name the exact blocker, inspected evidence,
safe alternatives considered, the smallest next action and the next owner.
Do not write generic phrases such as "conflict", "bad branch" or "needs review"
without concrete evidence.

Package status should be:

```text
integration_package_ready   # all inspected package items are ready or intentionally out of scope
partial_package_ready       # at least one item is ready; other items are routed away
needs_rework_routed         # no ready items, but rework routes were assigned
cleanup_candidates_found    # only cleanup candidates/no-op items were found
integration_blocked         # package-wide blocker prevents any safe finalizer handoff
no_ready_items              # inspected stack has no package items for Finalizer
```

Whole-package blocking is the exception. Use it only for shared-path conflicts
that cannot be separated, failed global checks, broken integration base, unclear
merge authority or package-wide ownership conflicts.

Coordination-file churn is not a product conflict by itself. If worker branches
all touch `docs/plans/**`, `CHANGELOG.md`, locks, events or process logs, use
the central sync state as authoritative and classify the worker branch by its
`integration_changed_paths`. Route only branches with product/runtime overlap,
real merge conflicts, stale base, failed checks or missing evidence to rework.

## Required Outcomes

Every run must leave at least one durable artifact in Git unless the repository is unavailable:

- base-ready integration branch;
- draft integrator PR;
- integration report;
- rebuild/split plan;
- superseded PR report;
- finalizer handoff.
- integration package handoff.

If the stack can be assembled safely, prefer a base-ready branch with checks over a report-only result.

If safe items can be assembled while unrelated items need human input, rework,
worker fixes, dispatcher routing or architect decisions, prefer a
`partial_package_ready` handoff for safe items over blocking the whole package.

If no safe item can be assembled, write a routed handoff or blocked handoff with:

- canonical base branch and SHA;
- inspected PRs/branches and head SHAs;
- exact conflict files or ownership conflicts;
- excluded paths;
- smallest safe next actions;
- per-item `rejection_detail` objects with blocking reasons, evidence, checked alternatives and recommended next action;
- owner decisions required;
- rework/worker-fix/dispatcher/architect routes;
- cleanup candidates with evidence;
- checks that still need to pass.

## Cleanup Candidate Routing

`Auto Integrator` may mark stale, superseded, duplicate, no-op or conflicting
old PRs/branches as cleanup candidates, but it must not close PRs or delete
branches directly.

Cleanup candidate evidence must include why the branch is probably safe to
inspect for closure/deletion:

```json
{
  "branch": "codex/old-worker/foo",
  "pr": 12,
  "cleanup_status": "cleanup_candidate",
  "reason": "superseded by PR #34",
  "evidence": [
    "conflicting with current base",
    "task represented by FS-03 package",
    "last updated before accepted package"
  ],
  "safe_cleanup_after": "owner_review",
  "delete_remote_branch": "candidate",
  "close_pr": "candidate"
}
```

A deterministic cleanup script, Finalizer or owner review performs the actual
close/delete after checking merge state, unique commits, labels, activity,
protected branch rules and task evidence.

## PR Readiness Ownership

`Auto Integrator` owns PR readiness before `Auto Finalizer` runs.

If a task is `agent_done` but finalization is blocked by PR state, the integrator must:

- update the PR branch from the current integration branch;
- resolve `DIRTY` worktree state, `CONFLICTING` merge state, stale base and oversized or unrelated diffs;
- preserve the previous PR head in a backup branch before rewriting history or force-pushing;
- link orphan task commits to the accepted PR, integration branch or handoff evidence;
- record local checks and GitHub check/status evidence in the PR body and task records;
- move the PR from draft to ready for review when it is clean, scoped and review-ready.

An empty GitHub `statusCheckRollup` is not automatically fatal when the project
has no required CI. In that case, local check evidence from the task packet,
worker report or integrator rerun may satisfy readiness. If required CI exists
or a project policy demands GitHub checks, missing checks are `needs_worker_fix`
or `needs_rework` for that item only.

Backup branch naming should make the original branch and timestamp obvious, for example:

```text
codex/backup/<original-branch-slug>-<YYYYMMDD-HHMMSS>
```

`Auto Integrator` must not mark tasks `done`. `agent_done` remains `agent_done` until finalizer-gate, merge or owner-approval evidence gives `Auto Finalizer` authority to record `owner_approved` or `done`.

`DIRTY` and `CONFLICTING` PR states are readiness blockers owned by `Auto Integrator`, not final acceptance blockers owned by `Auto Finalizer`.

## Boundaries

`Auto Integrator` may:

- create integration branches, reports, handoffs and draft PRs;
- classify PRs as merge-as-is, rebase, split, rebuild, superseded or needs-human;
- run local checks and record evidence;
- update non-final integration status fields and reports;
- move clean PRs from draft to ready for review after readiness evidence is recorded.

`Auto Integrator` must not:

- approve owner-only decisions, merge production/release branches or bypass Finalizer gate authority;
- mark tasks `owner_approved` or `done`;
- release locks as final acceptance work;
- tag releases, push production or perform release migration;
- hide failed checks or unresolved review comments;
- silently overwrite another role's behavior or architecture decision.

## Done Definition

An `Auto Integrator` run is done only when the final answer can point to committed repository state that contains:

- the integration branch/report/handoff;
- base and head SHAs;
- PR disposition for every inspected candidate;
- checks performed and checks still missing;
- intentional Finalizer boundary.
