# Artifact Type Catalog

Artifact Discovery uses artifact types to map repository files to consumers.

## Core Types

```text
agent_project_rule
agent_role
routing_policy
agent_workflow
prompt
skill
lens
integration_policy
discovery_policy
automation_script
schema
template
task_state
report
document
project_map
python_module
frontend_or_node_module
artifact
```

## Consumer Hints

- Agent rules and workflows usually require route/index/map coverage.
- Automation scripts usually require catalog/map/evidence coverage.
- Schemas usually require template/example/validator coverage.
- Task state files are canonical project state and should be handled carefully.
- Reports are evidence unless they are generated temporary artifacts.

## Semantic Kinds

Artifact type is the narrow source classification. `semantic_kind` is the
consumer-facing meaning used by Dispatcher/Integrator reports:

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

`implementation_status` is not the same as task completion. It records whether
the artifact itself looks implemented, documented-only, contract-only, state,
evidence or still needs review. Router resolution then compares the finding
against Task Manager rows to decide whether the required follow-up is done,
pending or uncovered.

`integration_status` is the scanner's current wiring signal. A code artifact can
be `implementation_status=implemented` while still being
`integration_status=not_integrated` when Project Map coverage is missing.
