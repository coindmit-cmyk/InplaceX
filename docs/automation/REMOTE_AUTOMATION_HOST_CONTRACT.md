# Remote Automation Host Contract

Date: 2026-06-10
Status: reusable Phase 2 remote runner contract

## Purpose

Phase 2 automation must run from a dedicated remote PC instead of the owner's laptop.

The remote automation host is the execution environment for runner cycles, worker launches, local LLM runs, checks, branch pushes and draft PR creation. It is not a second source of truth and it does not own project state.

The owner's laptop is a control and manual-work surface only. Laptop-side Codex sessions may direct work, inspect state and make explicit manual repairs, but they must not claim autonomous worker execution or local LLM success.

The source of truth remains:

```text
Git repository
GitHub Issues/PRs/checks
AiStudio/Task_manager/task_queue.json
AiStudio/Task_manager/agent_locks.json
AiStudio/Task_manager/owner_directives.json
AiStudio/Task_manager/agent_runner_state.json
```

## Default Host State

Adopted projects may record the default remote host policy:

```json
{
  "automation_host": {
    "kind": "remote_pc",
    "mode": "phase_2_remote_runner",
    "authority": "runner_cycles",
    "execution_host_required": true,
    "local_llm_host": "remote_pc",
    "owner_laptop_role": "control_and_manual_work_only",
    "enabled": true,
    "autostart_enabled": false,
    "scheduler_enabled": false
  }
}
```

`enabled = true` means the project permits a remote PC to run Phase 2 automation after all runner gates pass.

`autostart_enabled = false` and `scheduler_enabled = false` mean the update package has not registered a schedule or started a runner.

## Allowed Work

The remote automation host may:

- refresh Git and GitHub state;
- read the shared queue, locks, directives and runner state;
- run local LLM requests through the remote PC LLM backend;
- run dry-run eligibility scans;
- claim a `planned` or `needs_stronger_agent` task only through the Phase 2 runner contract;
- create or update a task lock for its own active run;
- start an Auto Worker with the matching worker profile;
- run local checks required by the task packet;
- commit, push and open a draft PR when the worker contract allows it;
- run Auto Integrator or Auto Finalizer only under their own contracts.

## Forbidden Work

The remote automation host must not:

- record laptop-side worker or LLM runs as successful automation evidence;
- store raw secrets, passwords, tokens or production credentials in Git;
- bypass task queue or lock files;
- overwrite project-owned state outside an explicit task/update contract;
- mark tasks `owner_approved` or `done` without accepted-state evidence;
- clean PRs as Auto Finalizer work;
- merge PRs, approve releases or deploy production changes without owner authority;
- silently clear stale locks;
- run on a dirty workspace without recording the risk and stop condition.

## Runner Gate

Before any apply-mode run, the remote host must verify:

```text
.agent/agent_version.json
AiStudio/Task_manager/owner_directives.json
AiStudio/Task_manager/agent_runner_state.json
.agent/worker_profiles.json
AiStudio/Task_manager/task_queue.json
AiStudio/Task_manager/agent_locks.json
```

Required state:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

The host must also confirm:

- activation or active-by-default adoption approval is recorded;
- the current machine is the approved remote PC execution host;
- selected worker profile is enabled;
- no active lock blocks the task;
- task packet has allowed paths, forbidden paths, acceptance criteria and checks;
- GitHub PR/check/status evidence is refreshed before integration or finalization.

## Scheduler Rule

A scheduler on the remote PC is allowed only when the project records:

```json
{
  "autostart_enabled": true,
  "scheduler_enabled": true
}
```

The scheduler must still execute dry-run-first cycles and must not claim work when stop conditions are present.

## Evidence

Every remote automation cycle must leave evidence in project records or PR body text:

- host mode and runner id;
- execution host identity class, recorded without secrets;
- selected worker profile;
- task id and branch;
- machine id and branch provenance;
- local checks run;
- GitHub check/status snapshot when available;
- links to draft PRs or integration reports;
- blockers, stale locks or residual risks.

## Final Rule

The remote PC can automate execution. The owner's laptop controls and reviews that execution. Neither can automate trust.
