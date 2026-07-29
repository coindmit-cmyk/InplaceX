# Agent Cleanup Contract

Date: 2026-06-12
Status: reusable Phase 2 contract

## Purpose

Every agent run must leave the project easier to read than it found it.

Agents may create temporary branches, worktrees, logs, reports and generated
state while doing useful work. Those artifacts must not remain in the active
project surface after the run has finished unless another agent still needs
them as live evidence.

## Required End-Of-Run Cleanup

Before reporting completion, every write-capable agent must:

- stop or record any child process it started;
- release or route its own stale locks when work is complete, blocked or handed off;
- remove local scratch files, temp exports and failed partial outputs that are not evidence;
- remove local worktrees that are no longer needed and whose commits are safely pushed or recorded;
- record cleanup candidates for temporary branches that are finalized, superseded, duplicated or no-op;
- archive bulky generated reports, preflight snapshots, process logs and one-shot integration artifacts under `old/agent-runs/<scope>/<timestamp>/` after their useful result is recorded in the live queue/report;
- keep only live coordination files in the active surface: queue, locks, current handoff/candidates, current process state and the latest human-readable report;
- commit and push cleanup changes when the run made durable repository changes.

## What Must Stay

Do not delete or archive:

- unmerged product code;
- worktrees or branches with active locks or fresh in-progress Codex task leases;
- branches with open PRs, unresolved reviews or non-final tasks;
- artifacts referenced by an active handoff, active lock, active dashboard row or current human review;
- evidence required to explain a `needs_human`, `needs_worker_fix`, `needs_dispatcher` or `blocked` route;
- owner notes, manual changes or project source files.

## Role-Specific Ownership

- Workers clean their own local worktree, scratch files and failed partial outputs after pushing or routing the task result.
- Integrator cleans no-op, duplicate and superseded candidates from the integration surface, but does not delete remote branches directly.
- Finalizer closes accepted tasks, releases completed locks, archives transient integration artifacts and records cleanup candidates after merge evidence exists.
- Cleanup scripts perform deletion only after deterministic protection checks and must default to dry-run.

## Deterministic Branch Cleanup

Use `scripts/agent_control/cleanup_merged_branches.py` for branch cleanup candidates.
The scheduled repository-wide entrypoint is
`scripts/agent_control/repository_hygiene_cycle.py`; it inventories open PR
topology, writes Task Manager routes and delegates destructive work to guarded
cleanup primitives.

Unmerged old branches must be archived under `archive/branches/<date>/` and
verified at the exact source SHA before the source ref is deleted. A failed
push, missing verification, wrong SHA, active worktree, active lock, open PR or
active task reference keeps the source branch. Worker-like unmerged branches
must go through salvage and clean-rebuild routing instead of deletion.

Dry-run:

```bash
python scripts/agent_control/cleanup_merged_branches.py \
  --project-root . \
  --fetch \
  --include-prefix-candidates
```

Apply local branch cleanup only after reviewing the dry-run report:

```bash
python scripts/agent_control/cleanup_merged_branches.py \
  --project-root . \
  --fetch \
  --apply \
  --delete-local
```

Apply remote branch cleanup only when project policy allows it:

```bash
python scripts/agent_control/cleanup_merged_branches.py \
  --project-root . \
  --fetch \
  --apply \
  --delete-remote
```

The script must block deletion when a branch is protected, not merged into the integration base, referenced by a non-final task, referenced by an active lock, checked out in a worktree, or still has an open PR. If GitHub PR state cannot be checked, remote cleanup must stay blocked unless the owner explicitly allows bypassing that guard.

Repository-wide cleanup must also block destructive apply when configured Codex
hosts do not have fresh activity leases. A terminal task status alone never proves
that its source tip is integrated; unresolved unique tips go back to
Dispatcher/Integrator through a stable recovery row.

## Worktree Retirement And Cold Archive

`scripts/agent_control/worktree_retirement.py` closes the gap between branch
classification and deletion. Repository Hygiene includes its dry-run plan on
every cycle.

A worktree is old enough to retire only when its exact branch tip is already an
ancestor of a canonical base, its retention window has expired, it is clean,
and it has no open PR or protected/current classification. A registered
worktree alone is not proof of live execution; current Codex activity, locks,
process state and open PR evidence remain the liveness authority.

Apply is explicit and bounded. It requires named branches and performs this
order without shortcuts:

1. create a Git bundle containing the exact source SHA;
2. copy the bundle and manifest to the configured cold archive;
3. verify the checksum and bundled head at the destination;
4. remove the clean worktree without `--force`;
5. delete the exact local branch only when requested;
6. leave remote deletion to a separate flag or later hygiene cycle.

The remote AiStudio HDD target is
`/srv/aistudio-hdd/AiStudioData/archive/git-branches/`. It stores immutable
bundles only; active worktrees must remain on M.2. Dirty, unmerged, open-PR,
current, protected or changed-after-plan branches fail closed and stay live.

`scripts/agent_control/branch_lifecycle_scanner.py` is the compact decision
layer above branch inventory. It emits exactly one outcome per logical branch:
`archive_ready`, `work_required`, or `keep`. Archive-ready tips are passed to
verified cold storage, including explicitly selected branches that no longer
have a worktree. Work-required tips use the deterministic
`repository_hygiene_branch_recovery` key, so repeated scans update or cover one
task instead of creating duplicates. Missing Codex host coverage blocks archive
decisions but does not hide recovery work. New recovery tasks are staged in a
bounded batch (ten per cycle by default); later scans continue from uncovered
branches without duplicating existing tasks.

When stale local refs exist only in a relay or release clone, use
`--task-project-root` to write their recovery rows into a separate clean
canonical Task Manager checkout. Branch evidence still comes from
`--project-root`; archive actions never target the Task Manager checkout.

Scheduled retirement requires the combined explicit gate
`--apply --apply-safe-cleanup --apply-worktree-retirement`, a configured archive
root, bounded count and complete fresh Codex host coverage. The cycle archives
and retires the local worktree first; guarded remote-ref deletion is evaluated
on the next fresh hygiene cycle.

`full_intake_automation_cycle.py` passes the same gate from
`AISTUDIO_APPLY_WORKTREE_RETIREMENT`, with the retention, archive root, optional
SSH host, batch limit and Codex activity coverage supplied by the corresponding
`AISTUDIO_WORKTREE_*` and `AISTUDIO_CODEX_*` environment settings. This keeps
scheduled cleanup disabled unless the runtime operator explicitly configures
the complete archive contract.

The planner must use one ref inventory per namespace by default. Per-branch
ahead/behind and changed-path metrics are diagnostic deep-scan behavior, not a
requirement for every scheduled cycle. Local and remote copies of the same
logical branch must not inflate dashboard branch totals.

## Cleanup Report

Every run report must include:

- artifacts kept live and why;
- artifacts archived or deleted;
- worktrees removed or intentionally kept;
- branches marked as cleanup candidates;
- locks released, renewed or left active;
- residual cleanup blockers.

If cleanup cannot be completed safely, the agent must record the blocker and
the next owner instead of silently leaving stale state behind.
