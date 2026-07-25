# Cleanup Consumer

## Purpose

Cleanup Consumer handles artifacts that may be stale, generated, duplicate, superseded or obsolete.

## Rule

Cleanup findings never auto-delete.

## Required Review Fields

```yaml
cleanup_candidate:
  path:
  reason:
  evidence:
  safe_deletion_conditions:
  suggested_owner: Integrator
  blocking_gate: none
```

## Actions

- create cleanup candidate review task;
- mark as retained with reason;
- archive through approved cleanup policy;
- delete only after owner/policy approval and evidence.
