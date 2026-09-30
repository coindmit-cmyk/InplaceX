# Artifact Discovery Routing Policy

## Purpose

Routing turns findings into owner-specific next actions.

## Routing Matrix

| Finding category | Owner | Task/action type | Blocking default |
| --- | --- | --- | --- |
| `unmapped_artifact` | Dispatcher | `reality_map_backfill` | new/current yes, legacy no |
| `missing_project_map_coverage` | Dispatcher -> ProjectMapPlanner | `reality_map_backfill` | new/current yes, legacy no |
| `missing_index_link` | Integrator | `integration_repair` | new/current yes |
| `missing_script_catalog_entry` | Integrator | `automation_surface_integration` | new/current yes |
| `missing_validator_template_pair` | Integrator | `schema_template_integration` | warning unless current gate depends on it |
| `missing_integration_surface` | Integrator | `integration_repair` | current yes |
| `missing_ux_contract_or_waiver` | UX Design | `ux_contract_or_waiver` | human-facing current yes |
| `legacy_state_reference` | Doctor | `policy_drift_review` | warning |
| `cleanup_candidate` | Integrator | `cleanup_candidate_review` | no |
| `possible_secret_pattern` | Human/Doctor | `security_review` | yes |
| `lost_task_candidate` | Dispatcher | `task_import_or_triage` | no |
| `lost_documentation` | ProjectMapPlanner | `documentation_map_backfill` | no |
| `policy_drift` | Doctor | `policy_drift_review` | warning/current yes |

## Mutability

Scanner is read-only.

Router defaults to report-only. With `--apply`, router may create Dispatcher-owned task candidates or task rows when the project allows Task_manager writes.

## No Silent Drop Rule

A finding must not disappear without disposition. If a finding is intentionally ignored, record owner, reason and evidence.
