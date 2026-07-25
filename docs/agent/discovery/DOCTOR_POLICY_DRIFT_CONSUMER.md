# Doctor Policy Drift Consumer

## Purpose

Doctor consumes discovery findings related to stale rules, legacy state references, broken packet assumptions and migration drift.

## Routes

```yaml
legacy_state_reference:
  owner: Doctor
  task_type: policy_drift_review
policy_drift:
  owner: Doctor
  task_type: policy_drift_review
lost_task_candidate:
  owner: Dispatcher
  task_type: task_import_or_triage
```

## Boundary

Doctor diagnoses and routes. Doctor does not silently rewrite policy or project state unless explicitly assigned repair work.
