# Loop Agent And Status Orchestrator

`loop_agent_orchestrator.py` is the rebuild/integration loop entry point. It
does not replace the existing worker and integrator scripts. It chooses which
script should run next from compact status and pending events.

## Flow

```text
compact_status_builder
-> decide next run_class
-> dispatcher_rebuild_planner / provisional_crb_task_builder
-> clean_rebuild_queue_bridge
-> worker_pool_manager
-> pre_integrator_repair
-> build_integration_package
-> auto_finalizer_merge
-> cleanup_merged_branches
```

## Run Once

```powershell
python scripts\agent_control\loop_agent_orchestrator.py `
  --project-root D:\Work\Project `
  --base-ref origin\develop `
  --once `
  --json
```

Add `--apply` to execute the selected step. Without `--apply`, it writes the
plan and commands only.

## Watch Mode

```powershell
python scripts\agent_control\loop_agent_orchestrator.py `
  --project-root D:\Work\Project `
  --base-ref origin\develop `
  --watch `
  --interval 1800 `
  --max-cycles 4 `
  --apply `
  --json
```

The loop is intentionally conservative. It runs one selected lane per cycle and
records the decision in `docs/plans/loop_agent_orchestrator.json`.

`integration_run` is a two-step lane: first it prepares
`integration_batch.json`, then it creates an isolated Integrator package
worktree/branch and writes `integration_handoff.json`. Package branch push is
controlled by the `--push-package` flag.

`finalizer_gate` calls `auto_finalizer_merge.py`. In dry-run mode it evaluates
the finalizer merge gate. With `--apply`, it creates an isolated finalizer
worktree, merges the verified `integrator/*` package into `develop`, verifies
the target branch did not change during the run and pushes only the accepted
package result. If the gate, merge or push is unsafe, it records
`finalization_blocked` / `needs_human` instead of mutating `develop`.

## Events

The loop consumes status/events produced by:

```text
dispatcher_rebuild_requested
provisional_crb_requested
llm_advisory_requested
crb_task_created
worker_ready_available
integration_requested
finalization_requested
cleanup_requested
needs_human_created
```
