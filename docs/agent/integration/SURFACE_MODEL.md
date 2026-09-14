# Integration Surface Model

## Definition

An Integration Surface is any place that must be updated for a change to become discoverable, routable, usable, validated, mapped or releasable.

## Surface Categories

### Manual Discovery

- `.agent/START_HERE.md`
- `.agent/context.json`
- `.agent/skills/*/SKILL.md`
- `agent-core/.agent/START_HERE.md`
- `agent-core/.agent/agents.md`
- `agent-core/.agent/routing.md`
- `agent-core/.agent/roles/*`
- `agent-core/.agent/prompts/*`

### Agent Entity Docs

- `docs/agent/workflows/*`
- `docs/agent/roles/*`
- `docs/agent/modes/*`
- `docs/agent/prompts/*`
- `docs/agent/skills/*`
- `docs/agent/lenses/*`
- `docs/agent/integration/*`

### Repository Navigation

- `docs/AISTUDIO_INDEX.md`
- `README.md`
- `CHANGELOG.md`
- `PROJECT_VERSION.json`
- `VERSION`

### Automation

- `AiStudio/Task_manager/task_queue.json`
- `AiStudio/Task_manager/agent_locks.json`
- `AiStudio/Task_manager/agent_events.jsonl`
- `AiStudio/Task_manager/agent_process_state.json`
- `scripts/agent_control/*`
- `schemas/*`
- `templates/agent-control/*`
- `agent-core/docs/automation/*`

### Project Reality

- `PROJECT_MAP.json`
- `PROJECT_MAP.md`
- design sessions under `docs/00_dispatch/design-sessions/*`
- integration manifests under `docs/reports/integration/*`
- Artifact Discovery reports and routed findings under `docs/agent/discovery/*`
  or generated reports

### Release and Adoption

- `develop`
- `release/main`
- stable tags
- `scripts/dev-only/update_project_agent.py`
- agent version metadata
- adoption reports
- migration notes

## Required Surface Matrix

| Entity | Required surfaces |
| --- | --- |
| Role | role file, role router, role index, prompt if launchable, boundaries, outputs, changelog/version review, map entry |
| Workflow / Department | workflow docs, launch prompt, owning role/route, manual discovery if user-facing, boundaries, snapshots/versioning, docs index, map entry |
| Mode | owning role, purpose, triggers, inputs/outputs, boundaries, completion states, invocation rule |
| Skill | skill doc, usage conditions, consuming roles/modes, catalog/index entry, selection rule if automatic |
| Lens | lens doc, selection conditions, output fields, authority boundary, catalog entry, consuming roles/modes |
| Code / Script | implemented path, checks/tests, script catalog if user/agent-facing, version review, rollback note, map entry for new capability |
| Schema / Template | schema/template, examples, validator/adoption docs, version/migration notes |
| Policy | policy doc, enforcement owner, discovery/index link, manifest/evidence expectation |
| Discovery Layer | policy docs, scanner/classifier/router/report builder, schemas/templates, tests, index entry, role consumers, changelog/version review, runtime safety statement |

## Blocking Logic

- New entity missing required surfaces: `integration_incomplete`.
- Legacy entity missing map coverage: create `reality_map_backfill` unless current safety depends on it.
- Surface conflict or source-of-truth conflict: `blocked_reality_gap` or `blocked_conflict`.
