# Project Template Adoption

## Purpose

Explains how another project adopts `ai-project-agent`.

## Copy Set

```text
agent-core/.agent/** -> .agent/**
agent-core/docs/automation/** -> docs/automation/**
docs/agent/discovery/** -> docs/agent/discovery/**
scripts/agent_control/artifact_discovery_*.py -> scripts/agent_control/artifact_discovery_*.py
schemas/agent-control/artifact_discovery_*.schema.json -> schemas/agent-control/artifact_discovery_*.schema.json
templates/agent-control/artifact_discovery_* -> templates/agent-control/artifact_discovery_*
templates/.agent/agent_version.json -> .agent/agent_version.json
templates/.agent/worker_profiles.json -> .agent/worker_profiles.json only when missing
templates/AiStudio/Task_manager/task_queue.json -> AiStudio/Task_manager/task_queue.json only when missing during bootstrap
templates/AiStudio/Task_manager/agent_locks.json -> AiStudio/Task_manager/agent_locks.json only when missing during bootstrap
templates/AiStudio/Task_manager/owner_directives.json -> AiStudio/Task_manager/owner_directives.json only when missing
templates/AiStudio/Task_manager/agent_runner_state.json -> AiStudio/Task_manager/agent_runner_state.json only when missing
```

`agent-core/docs/automation/BRANCH_COMMIT_INTEGRATION_PROTOCOL.md` is part of the required automation copy set. It defines how Director, Architect, Auto Make Tasks, Module Companion, worker, integrator and finalizer commits become durable project state through role branches and PRs.

`agent-core/docs/automation/INTEGRATION_FINALIZATION_PROTOCOL.md` is also required. It defines how ready PRs are assembled and how accepted results are closed without premature `done` status.

`agent-core/docs/automation/AGENT_UPDATE_PROTOCOL.md` defines safe update and migration rules. Prefer using `scripts/dev-only/update_project_agent.py` instead of manually copying files.

`agent-core/docs/automation/AGENT_UPDATE_FLOW.md`, `AGENT_PHASE2_FULL_ARCHITECTURE.md`, `PHASE_ACTIVATION_POLICY.md` and `REMOTE_AUTOMATION_HOST_CONTRACT.md` define the Phase 2 active package. Adoption sets `phase2_active = true`, records the remote PC as the default automation host and keeps runner autostart disabled.

Artifact Discovery files are adopted as reusable capability files only. Adoption
copies documentation, scripts, schemas and examples so the project can run its
own discovery review later, but the update path must not execute scanners,
classifiers, routers, report builders, router `--apply`, schedules or release
promotion. Do not copy `docs/reports/**`; reports are evidence, not reusable
policy. Project-owned discovery policy and Task_manager state stay local and
must be preserved by protected-path, missing-only or dirty-file behavior.

## Replace

```text
project name
repository name
local repository path
base branch
module names
task types
worker profiles
checks
owner decision rules
```

## Checklist

1. Copy reusable files.
2. Fill project templates.
3. Define worker profiles.
4. Open adoption PR.
5. Validate JSON, protected paths and phase activation gates.
6. Test Director -> Architect -> Auto Make Tasks -> Auto Worker -> Auto Integrator -> Auto Finalizer flow.
7. Test Module Companion manual-task routing: `human_working` blocks workers and `needs_replan_after_manual_work` routes to Dispatcher/Architect.
8. Review Artifact Discovery as a local project capability; do not run scanner/router automation from the adoption update.
9. Start or schedule Phase 2 remote runner execution only after project-specific dry-run review.

## Update Command

```bash
python scripts/dev-only/update_project_agent.py --project-root /path/to/project --apply
```

Use `--bootstrap-missing-state` only for a new project where `AiStudio/Task_manager/task_queue.json` and `AiStudio/Task_manager/agent_locks.json` do not exist yet.

Use `--create-branch --apply` to create a controlled update branch and write update report/PR body files.
