# Artifact Discovery Migration Policy

## Purpose

Artifact Discovery must improve visibility without freezing legacy projects.

## Phased Policy

```yaml
phase_1:
  scanner: read_only
  router_default: dry_run
  router_apply: Dispatcher-owned task routes only
  new_significant_artifacts: blocking
  legacy_unrelated_findings: non_blocking_task_or_report
  cleanup: never_auto_delete
  sensitive_risk_findings: blocking
```

## New Work

New significant artifacts must have discovery disposition, map coverage, index/surface coverage or explicit route before integration/finalization.

## Legacy Work

Legacy findings create backfill, triage, cleanup or policy-drift routes. They block only when they affect current safety, routing, automation, release, UX, source-of-truth or current implementation scope.

## Hardening Later

After backfill coverage improves, projects may raise policy strictness and make additional finding classes blocking.
