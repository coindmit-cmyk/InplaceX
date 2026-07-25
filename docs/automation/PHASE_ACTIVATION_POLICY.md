# Phase Activation Policy

Status: reusable safety policy

## Purpose

Defines the difference between Phase 2 active metadata and actually starting runner/server automation on a local or remote host.

## Required Metadata

Default Phase 2 active update:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

Reference-only opt-out:

```json
{
  "phase2_reference": true,
  "phase2_active": false
}
```

## Activation Requirements

Phase 2 activation requires all of:

- active-by-default adoption approval or explicit owner approval recorded in project-owned metadata;
- valid `AiStudio/Task_manager/task_queue.json`;
- valid `AiStudio/Task_manager/agent_locks.json`;
- valid `AiStudio/Task_manager/owner_directives.json`;
- reviewed `.agent/worker_profiles.json`;
- reviewed runner config/state path;
- reviewed remote automation host policy when execution runs from a remote PC;
- dry-run report;
- update report proving no protected state was overwritten.

Use `PHASE2_ACTIVATION_FLOW.md` and `scripts/dev-only/activate_project_phase2.py` for activation. Do not use the update manager to enable active execution.
The update manager enables Phase 2 metadata by default, but does not start runner execution.

## Active Does Not Mean Started

When `phase2_active` is true:

- worker profiles may be enabled;
- runner/scheduler execution is allowed by policy;
- the default execution host may be the remote PC recorded in `AiStudio/Task_manager/agent_runner_state.json`;
- no runner starts automatically;
- no recurring execution is registered automatically;
- no task is claimed until a runner command or scheduler actually runs.

When `phase2_active` is false, runner/scheduler commands may produce dry-run reports only.

## Activation Command

```bash
python scripts/dev-only/activate_project_phase2.py --project-root /path/to/project --approved-by owner --approval-source issue-or-pr-url --enable-worker-profile auto-worker-5.3 --apply
```

## Validation Rule

If any file says `phase2_active = true` without `phase2_activation_approval`, validation must fail.

If `owner_directives.json` and `.agent/agent_version.json` disagree, validation must fail until the owner resolves the conflict.

If a remote host is used, `AiStudio/Task_manager/agent_runner_state.json` must record the host policy and the runner must follow `REMOTE_AUTOMATION_HOST_CONTRACT.md`.
