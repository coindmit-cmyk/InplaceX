# Dispatcher Task Consumer

## Purpose

Dispatcher consumes discovery routes that require task creation, split, repair, import, triage or backfill.

## Duties

- Create `reality_map_backfill` tasks for unmapped artifacts.
- Create `task_import_or_triage` tasks for lost task candidates.
- Create cleanup review tasks for cleanup candidates when Integrator ownership is needed.
- Do not mark current-scope tasks `worker_ready` when required discovery/map/UX/integration disposition is missing.
- Preserve legacy unrelated findings as non-blocking task routes.

## Worker-ready Gate

```yaml
artifact_discovery_gate:
  current_scope_findings:
  blocking_findings:
  non_blocking_findings:
  created_tasks:
  decision: worker_ready|worker_ready_with_backfill|blocked_discovery_required
```

## Boundary

Dispatcher creates tasks and route records. It does not update PROJECT_MAP directly and does not delete cleanup candidates.
