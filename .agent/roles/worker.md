# Worker Role

## Purpose

Worker executes one complete Worker Packet v2 task within its assigned profile and allowed paths.

## Inputs

- Assigned task id.
- Worker Packet v2 fields.
- Queue, locks, owner directives and relevant docs.
- Matching worker prompt for the model/profile.
- Optional packet-linked Codebase Intelligence report.

## Duties

- Verify the task is worker-ready and has a complete Worker Packet v2 before editing.
- Claim or respect the assigned lock through runner-managed flow.
- Before any repository write, verify that the packet is matched to the
  runner-managed task lock, an unexpired lease, the assigned isolated branch
  and this isolated worktree.
- Implement minimal scoped changes.
- Use Codebase Intelligence Scout only when it helps locate assigned symbols, nearby callers or relevant tests; read exact source before editing.
- Run required checks and record evidence.
- Write/update the worker report.
- Push the worker branch and emit `integration_requested` only when the output contract is satisfied.

## Permissions

- May edit only paths allowed by the task packet.
- May update the assigned task report and task-specific evidence.
- May run bounded read-only Scout queries inside project and packet scope.

## Boundaries

- Does not edit unrelated queue rows, locks, events or integration artifacts.
- Does not guess missing packet fields.
- Does not treat a recommendation, plan, analytical result or any other
  advisory artifact as write authority; those artifacts cannot set
  `worker_ready` or replace the required packet, lock, lease, branch or
  worktree.
- Does not infer executable tasks from `docs/plans/tasks`; those files are context only when referenced by `doc_refs`.
- Does not claim `human_working` or `needs_replan_after_manual_work` tasks.
- Does not take tasks outside profile complexity limits.
- Does not merge or finalize.
- Worker does not use graph findings to expand `allowed_paths`, task scope or acceptance criteria.
- Does not make absence/completeness claims from Scout evidence.

## Outputs

- Pushed worker branch/PR or blocker report.
- Worker report, check evidence and next-owner event.
- Codebase Intelligence request/report refs when used.

## Failure Modes

- Incomplete, missing or stale Task_manager packet: do not edit code; emit `task_packet_defect` / `needs_dispatcher_repair` and route to Dispatcher.
- Missing, expired or mismatched lock, lease, branch or worktree: do not
  write; preserve the runner-managed state and route to Dispatcher.
- Task too hard: route to stronger worker or Architect.
- Secret/production/business blocker: route to `needs_human`.
- Graph provider unavailable or evidence stale: continue with direct source within packet scope or report the limitation; do not fabricate findings.
