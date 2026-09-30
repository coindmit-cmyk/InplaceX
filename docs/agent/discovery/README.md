# Artifact Discovery Layer

Artifact Discovery Layer is the project-wide scanner, classifier and router for significant repository artifacts.

It is not a Project Map-only tool. Project Map Enforcement is the first consumer, but discovery findings can also route to Dispatcher, Integrator, Doctor, UX Design, Finalizer, Security/Human review and cleanup workflows.

## Core Formula

```text
Scanner detects.
Classifier explains.
Router recommends or writes controlled task routes.
Normalizer scopes noisy routes into safe Task Manager rows.
Consumers act.
```

## Primary Rule

Every significant discovered artifact must have one of these dispositions:

- mapped in `PROJECT_MAP.json`;
- covered by an integration surface;
- linked from the correct index/catalog/router;
- assigned to a backfill/triage/cleanup/security/UX task;
- explicitly ignored with reason and evidence.

Scanner inventory rows must include lightweight classification fields:

```yaml
artifact_type:
semantic_kind:
implementation_status:
implementation_evidence:
integration_status:
integration_gaps:
integration_evidence:
flags:
  - significant
  - agent_surface
  - automation_surface
  - contract_surface
  - evidence_surface
  - implementation_surface
  - task_manager_state
  - cleanup_candidate
disposition:
  inventory_only | needs_coverage_check | needs_project_map_backfill | needs_index_or_exception | needs_script_catalog_or_exception | needs_schema_template_or_exception | needs_cleanup_review | needs_human_security_review
```

`semantic_kind` separates what the artifact is from which gap was found:

```text
code | documentation | policy | agent_contract | schema | template | task_state | report | project_map | config | other
```

`implementation_status` records the scanner's current realization signal:

```text
implemented | documented | documented_only | contract_exists | state_exists | evidence_exists | map_exists | configured | needs_review | unknown
```

The default classifier is deterministic and path/type based. Optional
`--semantic-mode local-llm` may enrich those fields through the local LLM lane,
but scanner remains read-only and falls back to deterministic output if the
local endpoint is unavailable or returns invalid JSON.

`integration_status` records whether the artifact is actually wired into the
project surfaces:

```text
integrated | partially_integrated | not_integrated | needs_human_review | inventory_only
```

Examples:

- `implementation_status=implemented` and `integration_status=not_integrated`:
  code exists, but Project Map coverage is missing.
- `implementation_status=implemented` and
  `integration_status=partially_integrated`: code is mapped, but a required
  index/catalog/schema-template surface is still missing.
- `integration_status=integrated`: scanner found no current Artifact Discovery
  integration gaps for that artifact.

Generated Artifact Discovery reports under `docs/reports/discovery/`,
`AiStudio/Task_manager/reports/discovery/` and Task Manager backup files under
`AiStudio/Task_manager/backups/` are excluded from scanner inventory so
recurring runs do not amplify their own findings.

## First Consumers

- Project Map Consumer: `unmapped_artifact`, `missing_project_map_coverage`.
- Dispatcher Task Consumer: create `reality_map_backfill`, triage or cleanup task rows.
- Integration Consumer: route missing surface/integration gaps to Integrator.
- UX Consumer: route missing UX contract or waiver to UX Design.
- Doctor Policy Drift Consumer: route stale rules, legacy state references and migration drift.
- Cleanup Consumer: route cleanup candidates without automatic deletion.
- Security Risk Consumer: route possible secret patterns to Human/Doctor/security review.

## Runtime Boundary

Scanner is read-only. Router defaults to dry-run. For automation, queue-visible
work should go through `artifact_discovery_normalizer.py` or
`artifact_discovery_cycle.py`, which group raw findings into Dispatcher-owned
follow-up rows and allow at most one explicitly scoped Worker Packet v2 per run.

Router `--apply` remains a reviewed Dispatcher gate for exceptional cases; it is
not the default path for importing large raw candidate sets.

No automatic deletion, secret handling, production mutation, runner behavior change or release promotion is introduced by this layer.
