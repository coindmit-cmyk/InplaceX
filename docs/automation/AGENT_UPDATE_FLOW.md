# Agent Update Flow

Status: reusable update manager contract

## Purpose

Defines how `ai-project-agent` distributes reusable coordination updates into application projects through controlled update PRs.

## Flow

```text
refresh ai-project-agent from GitHub release/main
check upstream version
compare adopted project version
stop normal work if versions differ
prepare safe update branch
copy reusable files
copy missing Phase 2 templates
preserve protected files
update agent_version
validate JSON and activation gates
write update report
write PR body
optionally open draft PR
```

## Agent Release Flow

Changes to the agent repository itself follow a stricter path:

```text
develop change
run release gate
fix failures in develop
promote tested commit to release/main
tag stable version
projects update from release/main
```

Do not move new behavior directly into `release/main`. The release gate must pass before a stable tag is created.

## Code Version Step

When code-changing work is migrated/finalized into `develop`, the responsible
agent must update the project code version in the same closure package.

Project code versions use:

```text
r.m.t.f
```

- `r`: MVP/global-release line; reset `m.t.f` when incremented.
- `m`: completed large module, phase or task package; normally `0..9`; reset `t.f` when incremented.
- `t`: completed code task count; increment by `1` for each completed task merged into `develop`; reset `f` when incremented.
- `f`: code fix count for fixes that do not complete a new task.

Documentation-only work follows its own version policy and does not require a
product code version bump unless the project couples documentation and product
versions explicitly.

Required release gate:

- compile changed Python scripts;
- validate changed JSON templates and schemas;
- run `git diff --check`;
- run `update_project_agent.py` in dry-run mode against a temporary project root;
- smoke-test changed helper scripts;
- review changelog and version metadata.
- run `scripts/agent_control/github_codex_review_gate.py` for the fix and
  release PRs; any unresolved Codex P0/P1 thread blocks merge;
- after resolving a blocking thread, rerun the gate on the exact PR head with
  `gh workflow run codex-review-gate.yml --ref <head-ref> -f pr_number=<pr-number>`;
  GitHub exposes thread resolution as an App webhook but not as a GitHub
  Actions trigger, so resolution without this supported dispatch is not
  acceptance evidence;
- record explicit review acceptance because ordinary green CI is necessary but
  not sufficient acceptance evidence.

## Protected State

The update manager must never overwrite:

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
application code
migrations
.env
.env.*
production config
secrets
```

Missing Phase 2 templates may be created when no project-owned file exists yet.

## Phase 2 Active Update

`AGENT-UPD-001` distributes Phase 2 files with active metadata by default:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

The update manager must not start runners, register schedulers, claim tasks or create locks.

The update manager writes the default remote automation host policy into runner state:

```json
{
  "automation_host": {
    "kind": "remote_pc",
    "mode": "phase_2_remote_runner",
    "enabled": true,
    "autostart_enabled": false,
    "scheduler_enabled": false
  }
}
```

Use `--phase2-reference-only` only when a project explicitly needs inactive reference files.

## Required Report Sections

Every update report should include:

- GitHub source branch refreshed;
- upstream version and commit;
- previously adopted project version, when known;
- copied, unchanged and protected-skipped paths;
- missing templates created or proposed;
- validation results;
- confirmation of the `phase2_active` state and runner autostart state.
- confirmation of the remote automation host policy.

## Draft PR Body

Use this structure:

```text
What changed
- added Phase 2 architecture reference
- added/update agent metadata
- added missing Phase 2 templates only
- Phase 2 active metadata enabled by default
- remote automation host policy enabled
- runner autostart remains disabled

Not changed
- project code
- runtime config
- secrets
- task queue live state
- locks

Checks
- JSON validation
- protected-path review
- phase2_active gate review
- remote automation host policy review
- runner autostart review
```
