# Project Map Consumer

## Purpose

Project Map Consumer turns `unmapped_artifact` and `missing_project_map_coverage` findings into map update work.

## Flow

```text
Artifact Discovery finding
  -> Dispatcher creates reality_map_backfill task or marks current-scope blocker
  -> ProjectMapPlanner updates PROJECT_MAP.json / PROJECT_MAP.md
  -> validator confirms coverage
```

## Rules

- New/current-scope significant artifacts without map coverage block the relevant gate.
- Legacy unrelated artifacts create non-blocking backfill tasks.
- ProjectMapPlanner owns map updates, not the scanner.
- Dispatcher owns task creation.

## Backfill Task Fields

```yaml
type: reality_map_backfill
entity_path:
artifact_type:
reason:
blocking_current_work:
suggested_map_section:
next_owner: ProjectMapPlanner
```
