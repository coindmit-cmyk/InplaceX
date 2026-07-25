# Integration Surface Consumer

## Purpose

Integration Surface Consumer maps discovery findings to Integration Protection policies.

## Consumes

- `missing_index_link`
- `missing_script_catalog_entry`
- `missing_validator_template_pair`
- `missing_integration_surface`
- `unmapped_artifact` when it affects integration surfaces

## Output

```yaml
integration_surface_consumer:
  finding_id:
  affected_surface:
  required_update:
  owner: Integrator
  blocking: true|false
```

## Rule

New/current integration-surface gaps block integration. Legacy unrelated gaps create repair/backfill tasks.
