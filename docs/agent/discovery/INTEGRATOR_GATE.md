# Artifact Discovery Integrator Gate

Integrator uses Artifact Discovery findings during branch/PR/manual integration review.

## Rule

New significant artifacts introduced by a branch or PR must have map/index/surface/UX/discovery disposition before integration can be marked complete.

## Integration Check

```yaml
artifact_discovery_integration_check:
  changed_paths:
  findings:
  blocking_findings:
  non_blocking_findings:
  task_candidates:
  decision: integration_ready|integration_incomplete|blocked_discovery_required
```
