# Integration Consumer

## Purpose

Integrator consumes discovery findings that indicate missing integration surfaces, missing index links, missing script catalog entries, missing validator/template pairs or cleanup candidates.

## Routes

```yaml
missing_index_link:
  owner: Integrator
  task_type: integration_repair
missing_script_catalog_entry:
  owner: Integrator
  task_type: automation_surface_integration
missing_validator_template_pair:
  owner: Integrator
  task_type: schema_template_integration
cleanup_candidate:
  owner: Integrator
  task_type: cleanup_candidate_review
```

## Blocking

New/current-scope integration gaps block integration/finalization. Legacy unrelated findings create non-blocking routes.

## Boundary

Integrator repairs or routes. It does not treat scanner output as proof of deletion safety.
