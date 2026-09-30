# Agent Phase 2 Full Architecture

Status: reusable Phase 2 active package

## Purpose

Phase 2 makes `ai-project-agent` a controlled update source for reusable agent standards, not a hidden owner of project execution.

The repository distributes Phase 2 active files to projects such as MyVPN, e-shop and future repositories. The execution host is a dedicated remote PC, while runner autostart remains disabled until a project explicitly registers or starts it.

The owner's laptop is a control and manual-work surface only. It may inspect GitHub, give tasks, review reports, make small manual edits and run lightweight checks, but it is not the automation execution host for worker cycles, integrator/finalizer cycles or local LLM runs.

## Package Identity

```text
AGENT-UPD-001: Phase 2 active update package
```

The package contains:

```text
agent-core/.agent/**
agent-core/docs/automation/**
templates/.agent/agent_version.json
templates/.agent/worker_profiles.json
templates/AiStudio/Task_manager/owner_directives.json
templates/AiStudio/Task_manager/agent_runner_state.json
schemas/agent_version.schema.json
schemas/owner_directives.schema.json
schemas/agent_runner_state.schema.json
agent-core/docs/automation/REMOTE_AUTOMATION_HOST_CONTRACT.md
```

`task_queue.json` and `agent_locks.json` remain project-owned live state and are created only during bootstrap when missing.

## State Model

Projects must distinguish reference from execution:

```json
{
  "phase": "Phase 2 active coordination",
  "phase2_reference": true,
  "phase2_active": true
}
```

Phase 2 reference means the project has received documents, schemas, templates and activation rules.

Phase 2 active means a runner, dispatcher or scheduler may claim and start work after its own run gate passes. Adoption does not start a runner or register a scheduler.

Default host policy:

```json
{
  "automation_host": {
    "kind": "remote_pc",
    "mode": "phase_2_remote_runner",
    "execution_host_required": true,
    "local_llm_host": "remote_pc",
    "owner_laptop_role": "control_and_manual_work_only",
    "enabled": true,
    "autostart_enabled": false,
    "scheduler_enabled": false
  }
}
```

Default update/adoption already writes active metadata. `PHASE2_ACTIVATION_FLOW.md` and `scripts/dev-only/activate_project_phase2.py` are for older or explicitly reference-only projects.

## Update Manager Responsibilities

The Agent Update Manager must:

- check upstream repository version;
- compare adopted project version;
- prepare a safe update branch when requested;
- copy only reusable files and missing reference templates;
- preserve protected project-owned state;
- update `.agent/agent_version.json`;
- validate JSON and activation gates;
- write an update report and PR body;
- optionally open a draft PR through `gh` when requested.

It must not:

- overwrite live task queues or locks;
- start runners or register schedules;
- touch application code, secrets or runtime config;
- auto-merge update PRs.

## Project Readiness Examples

`e-shop` may receive Phase 2 active metadata while leaving autostart disabled because it has an open PR stack.

`MyVPN` and `e-shop` may receive Phase 2 active metadata with the remote host policy enabled while leaving autostart and scheduler disabled.

These examples are not hardcoded project rules; each project records its actual owner directives locally.

## Activation Gates

Activation requires:

- owner approval;
- valid `task_queue.json`;
- valid `agent_locks.json`;
- valid `owner_directives.json`;
- worker profiles reviewed and enabled intentionally;
- runner config reviewed;
- remote automation host policy reviewed;
- worker, integrator, finalizer and local LLM execution are running on the remote PC, not on the owner's laptop;
- dry-run report showing what would execute;
- no protected-path overwrite in the update report.

See `PHASE_ACTIVATION_POLICY.md`.

## Activation Manager Responsibilities

The Phase Activation Manager must:

- require owner approval and approval source;
- validate project queue, locks, owner directives, worker profiles and runner state;
- write a dry-run report;
- set `phase2_active = true` in `.agent/agent_version.json`, `AiStudio/Task_manager/owner_directives.json` and `AiStudio/Task_manager/agent_runner_state.json`;
- enable only owner-selected worker profiles;
- avoid starting runners, registering schedules, claiming tasks or creating locks.

## Final Rule

Phase 2 can be distributed broadly as active metadata. It becomes actual execution only when a runner or scheduler is explicitly started on the approved remote PC. A laptop-side run may be used for owner control or manual repair evidence, but it must not be recorded as remote automation or local LLM execution success.
