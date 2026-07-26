# Phase 2 Agent Runner Contract

Date: 2026-06-09
Status: reusable Phase 2 runner contract

## Purpose

Phase 2 adds a runner layer around the existing shared queue, lock and PR workflow.

This document is distributed with Phase 2 active metadata by default. The runner may select, lease and start eligible agent work only after the project explicitly starts or schedules runner execution.

The default execution environment is a dedicated remote automation host. Local Codex Desktop remains a supported manual launcher, but routine automation is expected to run from the remote PC once its host policy and scheduler are configured.

The runner does not approve owner-only decisions, merge production releases, overwrite project-owned state without an explicit apply flag, read secrets, or replace required owner review. Safe `develop` package return belongs to Auto Finalizer and only runs when the finalizer merge gate passes.

## Default Active State

Default adopted state:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

`phase2_reference = true` means Phase 2 documents, schemas and templates are present.

`phase2_active = true` means runner or scheduler execution is allowed.

Runner or scheduler execution is still not started by adoption. Worker profiles ship with `enabled = true` and `autostart_enabled = false`.

Reference-only adoption is an explicit opt-out:

```text
--phase2-reference-only
```

## Phase 2 Topology

```text
Owner/GitHub/docs -> shared queue -> Remote Automation Host -> Phase 2 Runner -> Auto Workers -> Auto Integrator -> Auto Finalizer -> needs_human only for blocked/risky/ambiguous work
```

The durable source of truth is still the repository and GitHub state. Runner state is operational metadata, not a second task queue.

Remote host boundaries are defined in `REMOTE_AUTOMATION_HOST_CONTRACT.md`.

## Runner Inputs

When Phase 2 is active, the runner must read fresh state before every cycle:

```text
.agent/START_HERE.md
.agent/agents.md
.agent/routing.md
.agent/permissions.md
.agent/worker_profiles.json
docs/automation/PHASE_2_RUNNER_CONTRACT.md
docs/automation/REMOTE_AUTOMATION_HOST_CONTRACT.md
docs/automation/PHASE_ACTIVATION_POLICY.md
docs/automation/LOCK_PROTOCOL.md
docs/automation/WORKER_PROFILES.md
docs/automation/AUTOSTART_FRESHNESS_POLICY.md
AiStudio/Task_manager/task_queue.json
AiStudio/Task_manager/agent_locks.json
AiStudio/Task_manager/owner_directives.json
AiStudio/Task_manager/agent_runner_state.json
AiStudio/Task_manager/agent_activity_state.json
AiStudio/Task_manager/agent_events.jsonl
AiStudio/Task_manager/agent_process_state.json
AiStudio/Task_manager/model_budget_state.json
AiStudio/Task_manager/process_locks.json
GitHub Issues, PRs and recent commits when available
```

If `phase2_active` is false, the runner may only produce dry-run/reference reports.

If `AiStudio/Task_manager/agent_runner_state.json` records `automation_host.kind = remote_pc`, the runner must also follow `REMOTE_AUTOMATION_HOST_CONTRACT.md`.

## Runner Outputs

When running in apply mode, the runner may update:

```text
AiStudio/Task_manager/task_queue.json
AiStudio/Task_manager/agent_locks.json
AiStudio/Task_manager/agent_runner_state.json
AiStudio/Task_manager/agent_activity_state.json
AiStudio/Task_manager/agent_events.jsonl
AiStudio/Task_manager/agent_process_state.json
AiStudio/Task_manager/model_budget_state.json
AiStudio/Task_manager/process_locks.json
```

It may only create missing Phase 2 templates during project bootstrap or controlled update. It must not replace an existing queue, lock file, owner directives file, runner state, worker profile file or project-owned docs without owner approval.

## Dry-run First

Every runner command must support dry-run mode.

Dry-run mode may:

- list enabled worker profiles;
- report eligible, skipped and blocked tasks;
- run the worker-ready promotion helper in dry-run mode before Worker routing;
- propose the next task and branch;
- report stale locks and missing worker-ready fields.
- report whether each scheduled role has fresh upstream input or should skip.

Dry-run mode must not:

- write queue, lock or runner state files;
- create branches;
- start agents;
- push commits;
- open or merge PRs.

## Claim Rules

Before Worker routing, the runner should run `scripts/agent_control/promote_worker_ready_tasks.py`.
In dry-run it reports complete `needs_task_packet` rows that can be promoted.
In apply mode it may promote only rows whose packet is already complete. Rows
that fail promotion remain Dispatcher work with `next_action = dispatcher_review`.

A runner may claim a task only when all are true:

- `phase2_reference` is true;
- `phase2_active` is true;
- activation approval or active-by-default adoption approval is recorded;
- task status is `planned` or `needs_stronger_agent`;
- `worker_ready = true`;
- `dispatcher_decision = worker_ready`;
- no active lock exists for the task;
- worker profile is enabled;
- task complexity matches the worker profile selection order;
- task type is allowed and not forbidden by the worker profile;
- task access requirement is allowed by the worker profile;
- explicit `allowed_paths`, `forbidden_paths`, `acceptance_criteria` and `checks` exist;
- every `script_actions[].command` is an executable command recognized by the
  queue-readiness gate; prose-only validation intent remains in `checks` or
  `acceptance_criteria` and must never be passed to a shell;
- `eligible_worker_profiles`, when present, includes the selected worker;
- dependencies and blockers are empty or already resolved.
- for 5.5-family profiles, remaining model limit is at least 15% before claiming a new task.
- `AiStudio/Task_manager/agent_activity_state.json` does not show a newer manual queue or role input that must be reconciled first.

An applied worker-pool lane always acquires a host execution lease.
`run_worker_cycle.py` forwards that exact lease id to `claim_next_task.py`.
The claim validates the lease project, worker, model and expiry, then records
the sanitized binding in both the task row and lock entry. A missing, expired
or mismatched execution lease fails before the claim commit.

Expired locks are not silently cleared. They are reported as stale and require owner or dedicated lock-maintenance handling.

## Apply-lock Behavior

When the owner or scheduler runs the runner with an explicit apply flag, the runner may:

1. choose the next eligible task from the shared queue;
2. write `status = in_progress`;
3. set branch and runner metadata on the task;
4. append an active lock with `state = in_progress`;
5. append/update `AiStudio/Task_manager/agent_runner_state.json`;
6. append/update `AiStudio/Task_manager/agent_activity_state.json`;
7. record the runner host id or host mode when available;
8. print the prompt or command needed to start the matching agent.

The worker remains responsible for implementation, checks, commit, push and draft PR.

## Central Claim And Worker Cycles

Worker task selection belongs to the runner, not to the LLM worker session.
Parallel workers must not start from the same queue snapshot and independently
choose tasks.

The runner cycle is:

```text
fresh GitHub snapshot
-> claim_next_task.py creates one lock commit and pushes it
-> launch_isolated_worker.py starts Codex with the assigned task id
-> wait for worker completion
-> worker emits integration_requested or task_packet_defect event
-> sync_worker_results.py copies task status evidence back to the central queue
-> fetch fresh GitHub state
-> claim the next task for the same worker profile
```

Use the cycle runner for normal worker automation:

```text
python scripts/agent_control/run_worker_cycle.py \
  --project-root /path/to/project \
  --base-ref origin/develop-or-queue-branch \
  --worker-id auto-worker-5.3 \
  --fetch \
  --watch
```

The claim step updates `AiStudio/Task_manager/task_queue.json` and
`AiStudio/Task_manager/agent_locks.json`, commits the lock, and pushes it before the worker
starts. If another runner pushed first, the claim push is rejected and the runner
must retry from fresh GitHub state. A rejected claim push is a retryable
coordination race, not a terminal lane failure. The runner fetches the queue
branch again, reselects against the updated locks, creates a new claim commit
and retries the push. Only after the configured retry budget is exhausted should
the lane report `claim_failed`.

Workers receive a strict assigned task prompt. They must not pick a different
task from the queue during that launch. If the assigned task cannot be completed
safely, they update only that task with blocker/return/human/escalation evidence
and stop. Continuing to the next task is the runner's job.

The cycle may process many tasks, but only one claim is active for a worker
launch at a time. Between tasks the runner refreshes GitHub and applies model
limit gates.

Worker lanes are bounded by `max_tasks_per_session` from
`.agent/worker_profiles.json` unless the launcher explicitly overrides the
limit. When a lane reaches that batch limit, it stops claiming new tasks and
emits `task_worker_done` for `Auto Integrator`. Normal automation should then
integrate or route the accumulated worker branches before creating another large
worker batch.

Worker branches are not product-code merges into the central queue branch.
Before Auto Integrator builds candidates, `sync_worker_results.py` must recover
result evidence from the worker branch and copy only task status metadata back
to the central `task_queue.json` and `agent_locks.json`. Hardened workers may
remove launcher-owned queue snapshots from their final diff. In that case sync
may use an immutable task-specific Worker Result only when the branch exactly
matches the active task lease. This is the canonical transition from
`in_progress` to `agent_done`, `review`, `needs_task_packet`,
`needs_dispatcher_repair`, `needs_human`, `needs_stronger_agent` or `blocked`
on the shared queue. It must not merge application files, and packet
normalization must not rewrite a task while its active lease is unresolved.

Event-driven scheduling is the default selection model:

```text
task_packet_defect / human_answered -> Dispatcher
worker_ready_available -> Worker
integration_requested -> Integrator
integration_handoff_ready / finalization_requested -> Finalizer
integration_blocked / finalization_blocked -> needs_human or Dispatcher routing
```

Intervals are fallback recovery windows, not the main reason to start agents.

Normal automation should enter through:

```text
python scripts/agent_control/status_orchestrator.py \
  --project-root /path/to/project \
  --base-ref origin/develop \
  --apply
```

The orchestrator owns process state and calls bridge scripts before external
agent lanes. Worker implementation is still performed by external Codex/LLM
workers launched through isolated worktrees.

## Isolated Worker Worktrees

Parallel Auto Workers must not run in the same project checkout. A shared
worktree lets one worker switch branches, rewrite queue state or leave dirty
files while another worker is selecting work, which can hide worker-ready tasks
or cause commits with another worker's locks.

Before launching a worker, the runner must create a dedicated git worktree from
the freshly claimed queue/base branch and run Codex inside that worktree:

```text
python scripts/agent_control/launch_isolated_worker.py \
  --project-root /path/to/project \
  --base-ref origin/develop-or-queue-branch \
  --worker-id auto-worker-5.3 \
  --prompt "Auto Worker 5.3" \
  --fetch
```

The main checkout is coordination state only. It may be dirty because another
manual or recovery operation is in progress, but routine worker execution uses
isolated worktrees under the project-local worktree root:

```text
<project-root>/agent-worktrees/<worker-id>/<task-id>-<run-id>
```

Example:

```text
/mnt/d/DevOps/E-SHOP/agent-worktrees/auto-worker-5.3/COM-CIE-1-20260611T000000Z
```

Do not create routine worker worktrees next to the project root such as
`/mnt/d/DevOps/E-SHOP-agent-worktrees/...`; those paths are harder to inspect
and clean up per project.

Direct `codex exec --cd <main project checkout>` is allowed only for an explicit
manual emergency run with owner awareness. It is not valid scheduler behavior.

Phase 2.1 branch names should include both machine and worker identity:

```text
remote/<machine-id>/<worker-id>/<task-id>-short-title
local/<machine-id>/<role>/<task-id>-short-title
```

## Stop Conditions

The runner must stop instead of claiming or starting work when it sees:

- dirty or conflicting project state that affects target paths;
- missing task packet fields, including Worker Packet v2 fields
  (`worker_instructions`, `traceability`, `doc_refs`, `input_refs`,
  `output_contract`, `script_actions`);
- a prose-only, empty or otherwise non-executable `script_actions` entry;
- protected paths without explicit task permission;
- live, secret or production access outside the worker profile;
- ambiguous owner or architecture decision;
- existing PR or branch that appears to cover the same changed paths;
- task status is `human_working` or `needs_replan_after_manual_work`;
- unavailable launcher/tooling;
- stale lock that would need manual cleanup.
- remote host policy missing or disabled when the run is scheduled on the remote PC.
- model limit below the worker profile's new-task threshold.
- another worker is already running in the same checkout and no isolated
  worktree has been created for this run.

## Governance Roles

`Auto Integrator` and `Auto Finalizer` are not implementation worker profiles.

Phase 2 may schedule them as governance runs, but they still follow their own contracts:

- `Auto Integrator` organizes ready PRs/branches and concrete unblock routes.
- `Auto Finalizer` returns verified safe Integrator packages to `develop` when gate evidence is clean, and routes blocked/risky/ambiguous work to `needs_human`.

Neither role may approve owner-only decisions or merge production/release branches unless the owner explicitly grants that authority. Auto Finalizer may merge only safe Integrator-approved packages into `develop` under the documented merge gate.

## Final Rule

Phase 2 automates the start of safe runner work. It does not automate trust.
