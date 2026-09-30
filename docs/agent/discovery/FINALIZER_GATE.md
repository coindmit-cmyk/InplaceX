# Artifact Discovery Finalizer Gate

Finalizer uses Artifact Discovery findings to avoid accepting work that introduced unresolved significant artifacts.

## Rule

New/current significant unresolved Artifact Discovery findings block finalization.

Legacy unrelated findings do not block when they have route/task/backfill disposition.

## Finalizer Check

```yaml
artifact_discovery_finalizer_check:
  blocking_findings:
  route_records:
  safe_deferrals:
  decision: finalizer_ready|blocked_discovery_required
```
