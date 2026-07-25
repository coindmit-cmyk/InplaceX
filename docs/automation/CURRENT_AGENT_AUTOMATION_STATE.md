# Current Agent Automation State

`ai-project-agent` is the AiStudio Agent Control Repository. It is the control
layer that projects use to pull current agent rules, scripts and automation
policy.

## Connected Project Contract

Projects keep their product code in their own repositories. This repository
ships only agent rules, deterministic scripts, schemas and runbooks. Product
projects should update their local `.agent`, `scripts/agent_control`, schemas
and docs from the release branch/tag before running automation.

## Implemented Layers

Current stable automation is script-first:

```text
queue/source docs
-> Dispatcher task packets
-> worker pool / isolated workers
-> sync_worker_results
-> pre_integrator_repair
-> pr_readiness_classifier
-> task_identity_audit / integration_candidate_filter
-> integration_batch_builder
-> build_integration_package
-> Integrator handoff
-> auto_finalizer_merge
```

The rebuild loop adds the missing routing layer:

```text
classification
-> rebuild_decision_classifier
-> route_rebuild_and_integration_results
-> dispatcher_rebuild_planner / provisional_crb_task_builder
-> loop_agent_orchestrator
```

## Required Agent Rules

- Always read fresh agent rules before starting.
- Always fetch/sync project state before integration/finalization decisions.
- No task_id, no worker claim; integration may use recovered/provisional source
  identity when safe evidence exists.
- No worker report/evidence/source artifact, no integration candidate.
- Missing task identity is recoverable metadata when branch/PR/path evidence is
  safe; Integrator performs recovery before batching/finalization.
- Package size alone is not a blocker; Integrator may split or coalesce safe
  module-sized batches.
- Low-risk untraced docs/coordination work may become provisional CRB or cleanup.
- Every routed item must include `next_owner`, `reason` and `next_event`.
- Tests must pass before release.
- All durable control-layer changes must be committed and pushed to GitHub.
- Agents must clean or explicitly report temporary artifacts after work.

## Status Ownership

| Status / decision | Owner |
| --- | --- |
| `worker_ready` | Worker pool |
| `ready_candidate` | Integrator |
| `integrator_identity_recovery` | Integrator |
| `integrator_module_batch` | Integrator |
| `ready_to_finalize` | Finalizer |
| `crb_auto_task` | Dispatcher / clean rebuild bridge |
| `provisional_crb` | Dispatcher |
| `dispatcher_rebuild` | Dispatcher |
| `llm_advisory_classify` | Dispatcher with advisory LLM |
| `needs_worker_fix` | Worker lane |
| `needs_rework` | Integrator |
| `needs_human` | Human owner |
| `cleanup_candidate` | Cleanup / Finalizer policy |

## Main Entry Points

```powershell
python scripts\agent_control\rebuild_decision_classifier.py --project-root D:\Work\Project --json
python scripts\agent_control\route_rebuild_and_integration_results.py --project-root D:\Work\Project --apply --json
python scripts\agent_control\compact_status_builder.py --project-root D:\Work\Project --json
python scripts\agent_control\loop_agent_orchestrator.py --project-root D:\Work\Project --base-ref origin\develop --apply --once --json
python scripts\agent_control\build_integration_package.py --project-root D:\Work\Project --base-ref origin\develop --apply --push --json
python scripts\agent_control\auto_finalizer_merge.py --project-root D:\Work\Project --base-branch develop --apply --json
```

`status_orchestrator.py` now treats rebuild events as `rebuild_route` and
delegates that lane to `loop_agent_orchestrator.py`.
