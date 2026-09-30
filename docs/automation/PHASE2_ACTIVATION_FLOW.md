# Phase 2 Activation Flow

Status: reusable Phase 2 activation contract

## Purpose

Defines how a project moves from Phase 2 reference-only to Phase 2 active.

Default update/adoption already creates active metadata:

```text
Agent Update Manager -> phase2_reference=true, phase2_active=true
```

This activation flow is for projects that were explicitly installed with `--phase2-reference-only` or adopted an older reference-only package.

## Activation Authority

Phase 2 activation requires explicit owner approval.

The activation record must include:

```json
{
  "approved_by": "owner",
  "approved_at": "2026-06-09T00:00:00Z",
  "approval_source": "issue-pr-chat-or-doc-reference",
  "dry_run_report": "docs/agent-updates/example-phase2-activation-dry-run.md"
}
```

## Required Gates

Activation must verify:

- valid `.agent/agent_version.json`;
- valid `.agent/worker_profiles.json`;
- valid `AiStudio/Task_manager/task_queue.json`;
- valid `AiStudio/Task_manager/agent_locks.json`;
- valid `AiStudio/Task_manager/owner_directives.json`;
- valid `AiStudio/Task_manager/agent_runner_state.json`;
- `phase2_reference = true`;
- remote automation host policy reviewed;
- one or more worker profiles intentionally selected;
- dry-run report written;
- no runner start or scheduler registration performed by activation.

## Command

Dry-run:

```bash
python scripts/dev-only/activate_project_phase2.py --project-root /path/to/project --approved-by owner --approval-source issue-or-pr-url --enable-worker-profile auto-worker-5.3
```

Apply:

```bash
python scripts/dev-only/activate_project_phase2.py --project-root /path/to/project --approved-by owner --approval-source issue-or-pr-url --enable-worker-profile auto-worker-5.3 --apply
```

Enable all profiles intentionally:

```bash
python scripts/dev-only/activate_project_phase2.py --project-root /path/to/project --approved-by owner --approval-source issue-or-pr-url --enable-all-worker-profiles --apply
```

The command writes:

```text
.agent/agent_version.json
.agent/worker_profiles.json
AiStudio/Task_manager/owner_directives.json
AiStudio/Task_manager/agent_runner_state.json
docs/agent-updates/<timestamp>-phase2-activation-dry-run.md
```

## Boundaries

The activation manager does not:

- copy reusable files;
- overwrite project code;
- start a runner;
- register a scheduler;
- claim tasks;
- create locks;
- open or merge PRs.

## Final Rule

Active means the project has permission to run Phase 2 tooling. It does not mean the runner has already started.
