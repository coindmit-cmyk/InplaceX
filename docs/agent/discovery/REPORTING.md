# Artifact Discovery Reporting

## Purpose

Reports make findings visible to humans, automation, Dispatcher and Integrator without mutating repository state.

## Report Locations

Default runtime reports should be written outside repositories. Durable discovery
or integration reports may be committed under:

```text
docs/reports/discovery/
docs/reports/integration/
```

## Report Sections

```yaml
summary:
  by_category:
  by_severity:
  by_owner:
  by_disposition:
  by_semantic_kind:
  by_implementation_status:
  by_integration_status:
  resolution_counts:
inventory:
findings:
routes:
blocking_findings:
non_blocking_findings:
task_candidates:
ignored_with_reason:
checks:
```

Inventory rows should expose `artifact_type`, `semantic_kind`,
`implementation_status`, `implementation_evidence`, `integration_status`,
`integration_gaps`, `integration_evidence`, `flags` and `disposition`.
Findings should echo that context as `artifact_flags`,
`artifact_disposition`, `semantic_kind`, `implementation_status` and
`implementation_evidence`, `integration_status`, `integration_gaps` and
`integration_evidence`, then add the specific gap flag such as `map_gap`,
`index_gap`, `catalog_gap`, `template_pair_gap`, `cleanup_candidate` or
`sensitive_risk`.

## Semantic Classification

Scanner output distinguishes artifact meaning from the gap category. For
example, a single finding may be `category=missing_project_map_coverage`,
`semantic_kind=code` and `implementation_status=implemented`; that means the
code exists, but map/index coverage is still missing.

Common semantic kinds:

```text
code
documentation
policy
agent_contract
schema
template
task_state
report
project_map
config
other
```

Common implementation statuses:

```text
implemented
documented
documented_only
contract_exists
state_exists
evidence_exists
map_exists
configured
needs_review
unknown
```

The deterministic scanner is the default source of truth for automation.
Optional local LLM enrichment is explicit via `--semantic-mode local-llm`; it
must return strict JSON and cannot mutate Task Manager state.

## Integration Status

`integration_status` answers a different question from `implementation_status`.
It describes whether the artifact is wired into project surfaces.

```text
integrated
partially_integrated
not_integrated
needs_human_review
inventory_only
```

- `not_integrated`: Project Map coverage is missing.
- `partially_integrated`: Project Map coverage exists or no map gap was found,
  but another integration surface is missing, such as AISTUDIO index, Scripts
  Catalog or schema/template pair.
- `integrated`: no Artifact Discovery integration gaps were found.
- `needs_human_review`: sensitive-risk finding blocks automatic disposition.
- `inventory_only`: non-significant artifact outside the integration scope.

## Task Candidate Format

Task candidates created by router/report builder must be explicit and Dispatcher-owned.

```yaml
task_candidate:
  id:
  type:
  title:
  reason:
  source_finding_id:
  entity_path:
  suggested_owner:
  blocking_current_work:
  acceptance_criteria:
```

## Normalized Queue Rows

Automation must not import every raw router candidate into the active queue.
`artifact_discovery_normalizer.py` groups routed findings by category and writes
planned Dispatcher-owned rows. Only the first reviewed safe scope may become
`worker_ready=true`, and it must include Worker Packet v2 fields.

Normalized rows must preserve scanner metadata. Worker-ready rows carry
`artifact_flags`, `artifact_disposition`, `semantic_kind`,
`implementation_status`, `implementation_evidence`, `integration_status`,
`integration_gaps` and `integration_evidence` from their source
finding. Grouped Dispatcher/Human rows carry aggregate `artifact_flags`,
`artifact_dispositions`, `semantic_kinds`, `implementation_statuses`,
`integration_statuses` and `sample_artifacts` so Dispatcher can split work
without reopening raw routed JSON first.

Normalizer output must include a release gate:

```yaml
normalizer_version: 1.0-release
source_report_hash: sha256
summary:
  release_ready: true|false
  release_gate_errors: 0
  release_gate_warnings: 0
release_gate:
  ok: true|false
  errors: []
  warnings: []
```

`artifact_discovery_normalizer.py --apply` must refuse to write queue rows when
`release_gate.ok=false`. This blocks raw router candidates, duplicate IDs,
malformed Worker Packet v2 rows, multiple ADL `worker_ready` packets in one
batch, and rows not owned by the normalizer.

## Resolution Status

Router and cycle output should compare findings against Task Manager state and
report whether work is already done or still pending.

Common values:

```text
done
task_exists
active_group_pending
done_group
needs_human_review
uncovered
```

`active_group_pending` means the finding is not done yet, but there is an
active normalized group row for that category. `done` means an exact
`source_finding_id` match exists in task history or a terminal queue row.
