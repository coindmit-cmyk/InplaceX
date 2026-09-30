# Artifact Discovery Dispatcher Gate

Dispatcher uses Artifact Discovery findings when deciding whether a task can become worker-ready.

## Phase 1 Gate

```yaml
artifact_discovery_gate:
  new_or_current_significant_blocking: true
  legacy_unrelated_blocking: false
  possible_sensitive_risk_blocking: true
  task_candidates_created_for_legacy: true
```

## Worker-ready Rule

A task can become `worker_ready` when:

- no blocking current-scope Artifact Discovery findings remain; or
- the owner/Integrator records an explicit safe deferral;
- non-blocking legacy findings have task candidates or route records.
