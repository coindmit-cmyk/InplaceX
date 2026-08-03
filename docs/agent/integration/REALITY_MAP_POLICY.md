# Project Reality Map Policy

## Purpose

Project Reality Map tracks what exists, what is planned, what is implemented, what is integrated, what has evidence and what is missing.

## Core Principle

```text
Project Design starts from reality.
Integration proves reality.
Project Reality Map stores reality.
```

## Preferred Model

Use `PROJECT_MAP.json` as the single machine-readable source of truth. It should include reality sections for entities, documents, requirements, architecture, tasks, implementation, integration surfaces, evidence, gaps and backfill tasks.

A generated or human-readable report may be added later, but it must not become a competing source of truth.

## Entity Fields

```yaml
id:
title:
type:
status:
  design:
  requirements:
  architecture:
  backlog:
  implementation:
  integration:
  evidence:
source_docs:
architecture_docs:
requirement_refs:
task_refs:
implemented_paths:
integration_surfaces:
checks:
evidence_refs:
commits:
prs:
owner:
next_owner:
gaps:
```

## New Work Rule

New entities created after this policy must have map entries immediately when the map mechanism exists.

```text
new entity + no map entry = integration_incomplete
```

Artifact Discovery findings with `missing_project_map_coverage` are valid map
gap evidence. For new or current-scope entities, they must be resolved before
final integration. For legacy unrelated entities, they create
`reality_map_backfill` task candidates.

## Legacy Backfill Rule

Legacy entities without map coverage are migration debt, not default blockers.

```text
legacy missing map + non-critical = create reality_map_backfill task and continue
legacy missing map + current safety impact = blocked_reality_gap
```

## Blocking Criteria

Missing map coverage blocks when:

- current change modifies that entity;
- source of truth is unclear;
- routing, automation or release safety depends on the missing map;
- the entity is new;
- final status would otherwise be misleading.

## Backfill Task Template

```yaml
id: MAP-BACKFILL-<ENTITY-ID>
type: reality_map_backfill
status: planned
priority:
complexity:
title:
entity:
reason:
blocking_current_work:
input_refs:
allowed_paths:
acceptance_criteria:
checks:
next_owner:
```

## Gap Types

```text
design_gap
requirements_gap
architecture_gap
backlog_gap
implementation_gap
integration_gap
evidence_gap
routing_gap
documentation_gap
automation_gap
release_gap
map_gap
orphan_gap
```
