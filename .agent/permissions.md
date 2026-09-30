# AI Agent Permissions

## Global Rules

Agents must:

- avoid printing or committing secrets;
- avoid changing `.env`, `.env.*` and production credentials;
- avoid direct pushes to stable branches;
- preserve manual changes and unrelated local work;
- update docs and task state when workflow changes.

## Forbidden By Default

```text
.env
.env.*
*.pem
*.key
*.p12
production secrets
private tokens
database dumps with real data
customer personal data exports
application code unless a task explicitly allows it
migrations unless a task explicitly allows it
production config unless a task explicitly allows it
```

## Merge Permission

Only owner or explicitly approved release manager may merge PRs.

Exception: `Make human` may merge/migrate ordinary task PRs into `develop` when the owner explicitly selected Make human for that work, the PR targets `develop`, required checks pass, no protected-path/secret/production blocker remains and merge evidence is recorded in task state.

`Make human` may not merge to `master`, `main`, `release/*`, `production` or production deployment branches unless the owner separately grants release-manager authority for that specific release.

## Phase 2 Activation Permission

Phase 2 active docs and templates grant policy permission for runner execution, but do not start runners or schedules.

Runner, dispatcher or scheduler execution requires:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

The activation approval must be recorded in `.agent/agent_version.json` and `AiStudio/Task_manager/owner_directives.json`.

Remote automation host permission also requires `AiStudio/Task_manager/agent_runner_state.json` to record an enabled `automation_host` policy. Scheduler/autostart may run only when that policy explicitly enables them.

Without that active gate, Phase 2 tools may run in dry-run/report mode only.

## Protected Project Files

Upstream agent updates must not overwrite project-owned files:

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
```

## External Queue Imports

External GPT/Codex patches may propose tasks, but they must be mapped into the existing shared queue.

Do not blindly replace:

```text
AiStudio/Task_manager/task_queue.json
AiStudio/Task_manager/agent_locks.json
.agent/*tasks*.json
```

Preserve task IDs, statuses, locks, owner approvals and project-specific history unless the owner explicitly approves a migration.
