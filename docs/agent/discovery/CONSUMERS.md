# Artifact Discovery Consumers

Artifact Discovery findings are consumed by existing roles and workflows. This layer does not create a new agent role.

## Consumers

- Dispatcher: creates task routes and `reality_map_backfill` tasks.
- ProjectMapPlanner: updates `PROJECT_MAP.json` and related map views from map findings.
- Integrator: repairs missing integration surfaces and handles cleanup candidates.
- Doctor: diagnoses policy drift, stale rules and legacy state references.
- UX Design: creates UX contracts or waivers for human-facing findings.
- Finalizer: blocks finalization only for unresolved current/new significant findings.
- Human/Security: reviews possible secrets and protected-risk findings.

## Consumer Contract

```yaml
consumer:
input_findings:
action_allowed:
action_not_allowed:
outputs:
blocking_rules:
```
