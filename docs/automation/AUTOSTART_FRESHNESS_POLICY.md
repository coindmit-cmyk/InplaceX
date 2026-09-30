# Autostart Freshness Policy

Status: reusable Phase 2.1 contract

## Purpose

Scheduled agent intervals are fallback check windows, not automatic permission to run work.
Primary automation triggers come from `AiStudio/Task_manager/agent_events.jsonl`.

Before starting any scheduled role, the runner must read `AiStudio/Task_manager/agent_activity_state.json`, GitHub state, queue, locks and owner directives. If the upstream input for that role did not change since the role last processed it, the runner records a skip reason and does not start the agent.

`agent_activity_state.json` is not a task queue. It records role freshness, manual inputs, pending signals and skip reasons.
`agent_events.jsonl` is the event stream used by scheduler/runner decisions.
`agent_process_state.json` records lane state (`idle`, `pending`, `running`, `waiting_budget`, `blocked`, `needs_human`, `completed`, `cooldown`).

## Role Freshness Gates

```text
Auto Architect
  runs only when Director/manual input changed since the last architect result

Auto Dispatcher
  runs when Architect output, Dispatcher return, manual task input, `task_packet_defect` or `human_answered` input changed

Auto Workers
  first run the worker-ready promotion preflight, then run only when eligible worker-ready shared-queue work exists

Auto Integrator
  runs when `integration_requested`, worker output, local/manual PRs, changed PR stack or conflict signals changed

Auto Finalizer
  runs when `integration_handoff_ready`, finalization request, owner approval, merge evidence or acceptance evidence changed
```

Manual additions always count as fresh input until the appropriate role records that it has seen them.

## Skip Evidence

Every skipped scheduled run should record:

```json
{
  "role": "auto_dispatcher",
  "checked_at": "2026-06-10T00:00:00Z",
  "last_input_seen_at": "2026-06-10T00:00:00Z",
  "last_skip_reason": "no_new_architect_or_manual_input"
}
```

## Final Rule

The scheduler may wake up often. Agents should only start when new upstream state gives them real work.
Timers are recovery/fallback. Events are the normal fast path.
Autostart wrappers should call `status_orchestrator.py`; they should not start
role prompts directly unless the orchestrator selected that lane.
