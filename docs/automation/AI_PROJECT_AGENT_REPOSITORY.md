# AI Project Agent Repository Model

## Purpose

`ai-project-agent` stores the reusable AI coordination agent for multiple projects.

Projects adopt the agent through PR-based updates while preserving project-owned state.

## Separation Model

```text
ai-project-agent
  -> reusable agent core, templates, schemas and examples

project repository
  -> project code, project-specific docs, live queue, locks, Issues and PRs
```

## Four Layers

### Agent Core

Reusable:

```text
agent-core/.agent/**
agent-core/docs/automation/**
agent-core/docs/automation/BRANCH_COMMIT_INTEGRATION_PROTOCOL.md
agent-core/docs/automation/AGENT_UPDATE_PROTOCOL.md
agent-core/docs/automation/AGENT_UPDATE_FLOW.md
agent-core/docs/automation/AGENT_PHASE2_FULL_ARCHITECTURE.md
agent-core/docs/automation/PHASE_ACTIVATION_POLICY.md
agent-core/docs/automation/REMOTE_AUTOMATION_HOST_CONTRACT.md
agent-core/docs/automation/INTEGRATION_FINALIZATION_PROTOCOL.md
schemas/**
templates/**
scripts/dev-only/**
```

### Project Overrides

Project-owned:

```text
.agent/project.md
.agent/modules.md
.agent/workflows.md
.agent/context.json
.agent/worker_profiles.json
AiStudio/Task_manager/tasks/**
```

### Project State

Never replaced by upstream:

```text
AiStudio/Task_manager/task_queue.json
AiStudio/Task_manager/agent_locks.json
AiStudio/Task_manager/owner_directives.json
AiStudio/Task_manager/agent_runner_state.json
GitHub Issues
Pull Requests
branches
commits
owner decisions
```

### Local Runtime

Machine-specific and usually not committed:

```text
local repository path
Codex Desktop contexts
local schedules
.env
.env.*
credentials
```

### Archived Sources

Historical and donor documents:

```text
old/**
```

These files are not active runtime documentation and must not be copied into projects by the updater.

## Update Rule

Agent updates must be pull requests in the project repository.

Never overwrite protected project files directly.

Use `scripts/dev-only/update_project_agent.py` for repeatable updates from this repository into application projects.

For Phase 2 active distribution, use it as the Agent Update Manager:

```bash
python scripts/dev-only/update_project_agent.py --project-root /path/to/project --create-branch --apply
```

This creates or updates reusable docs, missing templates, `.agent/agent_version.json`, remote automation host policy, update report and PR body while keeping autostart/scheduler disabled.

## Release Rule

New rules, scripts, schemas and templates for `ai-project-agent` must be developed in `develop` first.

`release/main` is the stable project update source and must receive only tested commits. Before promotion to `release/main` or stable tagging, run:

- Python compile checks for changed scripts;
- JSON validation for changed templates and schemas;
- `git diff --check`;
- update-manager dry-run against a temporary project;
- smoke tests for changed helper scripts;
- changelog and version review.

Failed checks block the release. Fix failures in `develop` and rerun the full gate.

## Protected Files

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

## Version Metadata

Projects should track the adopted upstream version in:

```text
.agent/agent_version.json
```

See `templates/.agent/agent_version.json`.
