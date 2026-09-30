# Agent Update Protocol

Date: 2026-06-09
Status: reusable Phase 2 active update contract

## Purpose

This document defines how application projects receive updates from `ai-project-agent` without overwriting project-owned state.

The agent repository is the source for reusable coordination rules. Each application project remains the owner of its queue, locks, module docs, local decisions and application code.

## Stable Source Branch

Application projects must update from the stable release branch:

```text
coindmit-cmyk/ai-project-agent:release/main
```

`develop` is the agent-core development and test branch. Projects must not use `develop` as their normal update source.

The Update Manager fetches `origin/release/main`, resolves its exact commit and
copies from an immutable temporary archive of that commit. It must never check
out, pull, reset or otherwise switch the caller's Agent Core development
worktree. A non-release source is rejected unless an operator explicitly uses
`--allow-unstable-agent-source` for an isolated Agent Core test; that flag is
forbidden in application-project schedulers and normal adoption.

Every role must verify `.agent/agent_version.json` against GitHub
`release/main` before substantive work. If the adopted project version is behind
`release/main`, stop normal work and route the project through Agent Update
Manager before continuing. Implementation, dispatch, worker pickup, integration
and finalization must not continue while agent versions differ.

## Agent Repository Release Gate

All new `ai-project-agent` rules, scripts, schemas, templates and updater changes must land in `develop` first. `release/main` is reserved for tested stable versions that application projects may pull.

Before promoting `develop` to `release/main` or creating a stable tag, run and record the release gate:

- `python -m py_compile` for changed Python scripts;
- `python -m json.tool` or schema-aware validation for changed JSON templates and schemas;
- `git diff --check`;
- `update_project_agent.py` dry-run against a temporary project root that is not the agent repository;
- smoke tests for changed helper scripts;
- changelog and `VERSION` review;
- final `git status` and remote-ref check before tagging.

If any check fails, do not update `release/main` and do not create a stable tag. Fix the issue in `develop`, rerun the gate, then promote only after the gate passes.

## Code Version Numbering

Code-changing project work uses four numeric points:

```text
r.m.t.f
```

Meaning:

- `r` is the MVP/global-release line. Increment it only for major project-level milestones or serious global updates.
- `m` is a completed large module, phase or package of tasks. Treat `0..9` as the normal package scale. When `m` changes, reset `t` and `f` to `0`.
- `t` is completed task count. Each completed code task merged into `develop` increments `t` by `1`; it may grow beyond `9`. When `t` changes, reset `f` to `0`.
- `f` is a fix count. Increment it for code fixes that do not complete a new task; it may grow beyond `9`.

Any worker, integrator, finalizer or human-mode agent that changes product/runtime code must ensure the project version is updated as part of migration/finalization into `develop`.

Use the smallest correct point:

- major MVP/global update: increment `r`, reset `m.t.f` to `0.0.0`;
- completed large module/phase/task package: increment `m`, reset `t.f` to `0.0`;
- completed code task: increment `t`, reset `f` to `0`;
- code-only fix without a completed task: increment `f`.

Documentation-only work uses the documentation versioning policy. It does not force a product code version bump unless the project explicitly says that documentation versioning is coupled to product versioning.

Phase 2 updates activate Phase 2 by default, target the remote automation host and leave runner autostart disabled:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

## Active Source Set

Copy/update these reusable paths from `ai-project-agent`:

```text
agent-core/.agent/** -> .agent/**
agent-core/docs/automation/** -> docs/automation/**
docs/agent/discovery/** -> docs/agent/discovery/**
scripts/agent_control/artifact_discovery_*.py -> scripts/agent_control/artifact_discovery_*.py
schemas/agent-control/artifact_discovery_*.schema.json -> schemas/agent-control/artifact_discovery_*.schema.json
templates/agent-control/artifact_discovery_* -> templates/agent-control/artifact_discovery_*
templates/.agent/agent_version.json -> .agent/agent_version.json
templates/.agent/worker_profiles.json -> .agent/worker_profiles.json only when missing
templates/AiStudio/Task_manager/task_queue.json -> AiStudio/Task_manager/task_queue.json only when missing
templates/AiStudio/Task_manager/agent_locks.json -> AiStudio/Task_manager/agent_locks.json only when missing
templates/AiStudio/Task_manager/owner_directives.json -> AiStudio/Task_manager/owner_directives.json only when missing
templates/AiStudio/Task_manager/agent_runner_state.json -> AiStudio/Task_manager/agent_runner_state.json only when missing
```

Do not copy archived documents from `old/` into active projects.
Do not copy `docs/reports/**` into adopted projects as reusable policy. Reports
are evidence from specific runs, not update payload.

## Artifact Discovery Rollout

Artifact Discovery is distributed as an optional capability package:

- reusable docs under `docs/agent/discovery/**`;
- scripts under `scripts/agent_control/artifact_discovery_*.py`;
- schemas under `schemas/agent-control/artifact_discovery_*.schema.json`;
- example/config templates under `templates/agent-control/artifact_discovery_*`.

Agent Update Manager may copy those files through the same protected update path
as the rest of the reusable agent package. It must not execute the scanner,
classifier, router or report builder during adoption/update. It must not run
router `--apply`, write project queues, start services, register schedules or
promote releases.

Project-owned Artifact Discovery policy remains local to the adopted project.
If a project already carries its own discovery policy or task state, the updater
must preserve it through protected paths, missing-only behavior or dirty-file
skips instead of replacing it silently.

## Protected Project State

Never overwrite these paths from the upstream repository:

```text
.agent/project.md
.agent/modules.md
.agent/workflows.md
.agent/context.json
.agent/worker_profiles.json
AiStudio/Task_manager/task_queue.json
AiStudio/Task_manager/agent_locks.json
AiStudio/Task_manager/owner_directives.json
AiStudio/Task_manager/agent_runner_state.json
AiStudio/Task_manager/tasks/**
docs/reports/**
application code
migrations
.env
.env.*
production config
secrets
```

## Update Tool

Use:

```text
scripts/dev-only/update_project_agent.py
```

The tool acts as the Agent Update Manager:

- fetches GitHub `release/main` and materializes an immutable snapshot of its
  exact remote commit without switching the local Agent Core checkout;
- compares upstream and adopted versions;
- optionally creates an update branch;
- copies reusable files and missing Phase 2 templates only;
- updates `.agent/agent_version.json`;
- validates JSON and Phase 2 activation gates;
- writes update report and PR body files on apply;
- optionally opens a draft PR with `gh`.

Safe dry-run:

```bash
python scripts/dev-only/update_project_agent.py --project-root /path/to/project
```

Apply update:

```bash
python scripts/dev-only/update_project_agent.py --project-root /path/to/project --apply
```

Create update branch and write report/PR body:

```bash
python scripts/dev-only/update_project_agent.py --project-root /path/to/project --create-branch --apply
```

Optional remote refresh before copying:

```bash
python scripts/dev-only/update_project_agent.py --project-root /path/to/project --pull-agent-repo --agent-source-branch release/main --apply
```

The updater:

- backs up changed target files before overwriting;
- does not overwrite protected project-owned state;
- copies Artifact Discovery docs/scripts/schemas/templates as reusable files
  without executing Artifact Discovery tooling;
- creates missing queue/lock templates only when `--bootstrap-missing-state` is used;
- creates missing Phase 2 templates unless `--skip-reference-templates` is used;
- updates `.agent/agent_version.json` unless `--skip-version-metadata` is used;
- sets `phase2_active = true` by default and enables worker profiles with `autostart_enabled = false`;
- writes `automation_host.kind = remote_pc` and `automation_host.mode = phase_2_remote_runner` into runner state;
- supports `--phase2-reference-only` for inactive reference adoption;
- validates queue, locks, owner directives, runner state and activation gates unless `--skip-validation` is used;
- prints a summary of copied, skipped, backed-up and protected files.

## Auto Update

Projects may schedule the updater through:

```text
scripts/dev-only/setup-agent-update-task.ps1
```

Scheduled updates should still run on a branch and go through PR review. Do not auto-merge updater results.
The scheduled command pins `--agent-source-branch release/main`; registration
fails for any other source.

## Migration Checklist

1. Run the updater in dry-run mode.
2. Review protected file skips.
3. Refresh from `release/main` when needed.
4. Run the updater with `--apply` on an update branch.
5. Validate JSON files and run `git diff --check`.
6. Review `.agent/agent_version.json`.
7. Review generated update report and PR body.
8. Confirm `phase2_active = true`, `automation_host.kind = remote_pc` and `autostart_enabled = false`.
9. Open a draft PR in the application project.
10. Use `Auto Integrator` if multiple PRs overlap.
11. Use `Auto Finalizer` after Integrator provides a validated safe package, merge evidence exists, or owner approval is required for blocked/risky/ambiguous items.

## Final Rule

The upstream agent can update reusable coordination files, but it must not become an invisible owner of project state.
