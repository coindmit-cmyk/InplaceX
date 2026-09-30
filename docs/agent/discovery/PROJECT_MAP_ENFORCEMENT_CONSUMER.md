# Project Map Enforcement Consumer

## Purpose

This consumer operationalizes existing Project Map rules through Artifact Discovery findings.

## Existing Rule

Project Design already requires Project Map creation/update for new work. Artifact Discovery makes missing coverage visible and routable.

## Enforcement Policy

```yaml
new_significant_artifact:
  missing_project_map_coverage: blocks integration/finalization until mapped or explicitly routed
legacy_artifact:
  missing_project_map_coverage: creates reality_map_backfill task, non-blocking by default
current_scope_artifact:
  missing_project_map_coverage: blocks relevant gate unless owner/Integrator records a safe deferral
```

## Dispatcher Task

Dispatcher creates `reality_map_backfill` tasks from map findings. ProjectMapPlanner owns map updates.
