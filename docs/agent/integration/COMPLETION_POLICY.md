# Integration Completion Policy

## Purpose

Prevent false completion states where files are added but not fully integrated.

## Completion Levels

```text
files_added
files_updated
surfaces_detected
surfaces_updated
evidence_ready
integration_ready
manual_integration_done
manual_pr_ready
finalizer_ready
release_ready
done
integration_incomplete
blocked_owner_gate
blocked_sync
blocked_conflict
blocked_reality_gap
```

## Done Rule

A change cannot be `done` unless:

- required surfaces are updated;
- new entities have Project Reality Map coverage;
- evidence is recorded;
- version/changelog impact is reviewed;
- non-blocking legacy gaps have backfill tasks or explicit deferral;
- rollback note exists;
- next owner is clear;
- protected gates are resolved or explicitly recorded.

## New Entity Rule

```text
new entity + missing required surface = integration_incomplete
new entity + missing map entry = integration_incomplete
new entity + no manifest = integration_incomplete
```

## Legacy Entity Rule

```text
legacy map missing + not safety-critical = create reality_map_backfill task and continue
legacy map missing + safety/routing/release impact = blocked_reality_gap
```

## Manual Mode Rule

ManualIntegrationMode should continue until one of these states is reached:

- `manual_integration_done`
- `manual_pr_ready`
- `finalizer_ready`
- `blocked_owner_gate`
- `blocked_sync`
- `blocked_conflict`
- `blocked_reality_gap`

It should not stop at vague `needs_architect` or `needs_dispatcher` if safe inline reasoning can resolve the issue.

## Auto Mode Rule

AutoIntegrationMode may produce route/handoff/task outputs for another role and continue other integration candidates.

## Draft PR Rule

`manual_pr_ready` may include a draft PR. Draft PRs are dirty and not final automation input until promoted and final gates pass.
