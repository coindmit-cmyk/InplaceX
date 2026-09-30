# Artifact Discovery Classification Rules

## Purpose

Classification converts inventory into meaningful findings.

## Artifact Type Hints

| Path pattern | Artifact type |
| --- | --- |
| `.agent/**` | agent_project_rule |
| `agent-core/.agent/roles/*.md` | agent_role |
| `agent-core/.agent/routing.md` | routing_policy |
| `docs/agent/workflows/**` | agent_workflow |
| `docs/agent/prompts/**` | prompt |
| `docs/agent/skills/**` | skill |
| `docs/agent/lenses/**` | lens |
| `scripts/agent_control/*.py` | automation_script |
| `schemas/**/*.json` | schema |
| `templates/**/*.json` | template |
| `AiStudio/Task_manager/*.json` | task_state |
| `docs/reports/**` | report |
| `PROJECT_MAP.json` | project_map |

## Significance

An artifact is significant when it can affect project behavior, agent behavior, task routing, automation, UX, security, release, documentation truth, implementation truth or evidence.

## Semantic Classification

Scanner classification must include:

```yaml
artifact_type: narrow path/type category
semantic_kind: code | documentation | policy | agent_contract | schema | template | task_state | report | project_map | config | other
implementation_status: implemented | documented | documented_only | contract_exists | state_exists | evidence_exists | map_exists | configured | needs_review | unknown
implementation_evidence:
integration_status: integrated | partially_integrated | not_integrated | needs_human_review | inventory_only
integration_gaps:
integration_evidence:
```

This is separate from finding category. Example: an automation script can be
`semantic_kind=code` and `implementation_status=implemented` while still
emitting `category=missing_script_catalog_entry`.

Local LLM enrichment is allowed only as an explicit evidence lane. It may refine
`semantic_kind` and `implementation_status` when strict JSON is returned, but it
must not create tasks, apply patches or override Task Manager resolution.

`integration_status` is deterministic and gap-derived:

- `missing_project_map_coverage` means `not_integrated`;
- non-map integration gaps mean `partially_integrated`;
- no gaps on a significant artifact means `integrated`;
- sensitive-risk findings mean `needs_human_review`.

## Current Scope

A finding is current-scope when it belongs to changed paths, task affected paths, PR diff, active integration package, finalizer package or owner-selected area.

## Blocking Defaults

- new significant artifact without disposition: blocking;
- possible secret pattern: blocking;
- current-scope map/integration/UX gap: blocking for the relevant gate;
- legacy unrelated finding: non-blocking task/report route.
