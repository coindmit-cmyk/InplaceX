# Artifact Discovery Finding Model

## Finding Object

```yaml
id:
detected_at:
detector:
path:
artifact_type:
artifact_flags:
artifact_disposition:
category:
severity:
confidence:
current_scope:
evidence:
related_refs:
suggested_owner:
suggested_task_type:
suggested_action:
blocking_gate:
auto_task_allowed:
```

## Categories

```text
unmapped_artifact
missing_project_map_coverage
missing_index_link
missing_script_catalog_entry
missing_validator_template_pair
missing_integration_surface
missing_ux_contract_or_waiver
legacy_state_reference
cleanup_candidate
possible_secret_pattern
lost_task_candidate
lost_documentation
policy_drift
```

## Severity

```text
info
advisory
warning
blocking
critical
```

## Confidence

```text
low
medium
high
```

## Blocking Gates

```text
none
dispatcher
worker_ready
integration
finalization
release
human_security_review
```

## Disposition Rule

A finding is unresolved until it has one of:

- accepted map/index/surface update;
- Dispatcher-created task;
- Integrator repair route;
- Doctor diagnosis route;
- UX Design route;
- cleanup review route;
- Human/security route;
- explicit ignore with reason and owner.

## Artifact Flags

Findings should echo scanner inventory context through `artifact_flags` and
`artifact_disposition`, then add the specific gap flag that caused the finding.

Examples:

```yaml
artifact_flags:
  - significant
  - automation_surface
  - map_gap
artifact_disposition: needs_project_map_backfill
```

```yaml
artifact_flags:
  - significant
  - policy_surface
  - index_gap
artifact_disposition: needs_index_or_exception
```
