# Auto Worker Profiles

Task complexity is independent from worker identity, but this reusable core ships with the current default Auto Worker matrix.

Phase 2 templates in `.agent/worker_profiles.json` ship with `enabled = true`, `runner = remote-automation-host` and `autostart_enabled = false`. Projects must still explicitly start runners or register schedules.

```text
Task complexity = S / M / L / XL
Worker eligibility = Auto Worker profile + explicit task packet + free lock
```

`Codex 5.3 Worker` and `Codex 5.5 Worker` are not standalone roles. The active worker identities are the four profiles below.

## Worker Matrix

| Worker | Model family | Reasoning effort | Primary order | Fallback order | Branch lane |
| --- | --- | --- | --- | --- | --- |
| `Auto Worker 5.3 mini` | `5.3 mini` (`gpt-5.3-codex-spark`) | medium | `S` only | none | `remote/<machine-id>/auto-worker-5-3-mini/` |
| `Auto Worker 5.3` | `5.3` (`gpt-5.3-codex-spark`) | very high | `M` | `S` after `M` pool is empty | `remote/<machine-id>/auto-worker-5-3/` |
| `Auto Worker 5.5` | `5.5` | medium | `L` | none by default; Dispatcher should split routine work to S/M for 5.3 | `remote/<machine-id>/auto-worker-5-5/` |
| `Auto Worker 5.5max` | `5.5` | very high | worker-ready `XL` | critically important `L` | `remote/<machine-id>/auto-worker-5-5max/` |

## Shared Worker Rules

Every Auto Worker:

- reads fresh agent rules first, then GitHub state, queue, locks and relevant task docs before selecting work;
- in scheduled automation, receives one pre-claimed `task_id` from the central runner and must not select a different task;
- runs in its own isolated git worktree when launched by scheduler/runner; parallel workers must not share the same checkout;
- reads the task packet, linked architecture, context docs and dependent files before taking a lock;
- takes only `planned` or `needs_stronger_agent` tasks with free lock and a complete worker-ready packet;
- skips `human_working` and `needs_replan_after_manual_work`;
- skips visible planning rows that are missing `allowed_paths`, `forbidden_paths`, `checks`, `complexity`, acceptance criteria or context documentation;
- marks the task `in_progress` before edits with start time, worker id, branch and expiry;
- evaluates its own capability and escalates too-hard tasks to the next level with `needs_stronger_agent`;
- if an `XL` task cannot be completed, returns it once to Dispatcher as `needs_dispatcher_split`;
- routes retry-after-split tasks that still cannot be completed to `needs_human`;
- marks owner-decision tasks as `needs_human`;
- writes a per-task report and updates docs/changelog/task status when required;
- commits on a role branch, pushes and opens a draft PR when possible;
- keeps product/runtime edits inside the task `allowed_paths`;
- does not edit unrelated queue rows, locks, events, process logs or integration artifacts from a worker branch;
- may update only its assigned task status/evidence in `AiStudio/Task_manager/task_queue.json` when worker-result sync needs outcome evidence;
- emits `integration_requested` after successful worker evidence is pushed;
- emits `task_packet_defect` and returns to Dispatcher when a packet is incomplete or contradictory;
- finishes the assigned task and stops; the central runner decides whether to claim and launch the next task in the cycle;
- scans stale locks and unresolved `in_progress`/`review` rows after the run;
- may run from a remote PC scheduler after the project explicitly enables autostart/scheduler policy.

If a worker detects that it is running in a checkout already used by another
active worker, or sees branch/queue state changed by another worker before it
claims a task, it must stop without taking new work and report
`shared_worktree_conflict`.

In runner-managed mode, "continue until the pool is empty" is implemented by
`run_worker_cycle.py`, not by a single LLM worker session. This preserves one
central lock commit per task and avoids duplicate claims from identical queue
snapshots.

## Limit Rules

Current profiles are capability envelopes, not fixed model assignments. `codex_model` is a compatibility fallback; `model_candidates` is the ordered set available to the resource router. Before each lane starts, `model_resource_router.py` selects the exact model and reasoning effort from task complexity/risk, current global and Spark-specific limits, reset time and retry history. The launcher must pass both values explicitly to `codex exec`.

Pool capacity is also limit-aware. Profiles may declare `max_parallel_lanes`; the host policy and current limit tier remain hard upper bounds. Additional lanes are selected only for distinct task ids without proven concrete path overlap. Shared reconciliation files such as `CHANGELOG.md`, `README.md`, and clean-rebuild plan artifacts do not by themselves make otherwise independent implementation packets overlap; exact source and test paths still serialize the affected lanes, and Integrator remains responsible for reconciling shared documentation edits.

The limit check runs before claiming the next task, not in the middle of a task already in progress.

```text
5.3-family workers: may claim tasks until exhausted, but each runner batch still
stops at the profile `max_tasks_per_session` limit so Integrator can collect the
results.
5.5-family workers: do not claim a new task when remaining model limit is below
15%, and use smaller batches by default.

5.3-family workers are the default high-capacity execution lane. Dispatcher
should prefer S/M task packets for routine docs, tests, contract, verification
and focused implementation work so 5.3 capacity is used before 5.5-family
limits. Only keep a task as L/XL when it cannot be split safely or needs
stronger-model judgment.

Default batch limits:

```text
auto-worker-5.3-mini -> 10 tasks
auto-worker-5.3      -> 8 tasks
auto-worker-5.5      -> 3 tasks
auto-worker-5.5max   -> 1 task
```

The launcher must pass the profile model to Codex explicitly with
`codex exec --model <codex_model>`. The worker name in the prompt is not enough
to select the model.
```

For the 5.5-family, 10% is reserved for manual development and 5% is execution buffer for finishing or reporting already-started work.

## Escalation Rules

Complexity may be raised by one level at a time:

```text
S -> M -> L -> XL
```

When escalating, the worker must:

- set `status = needs_stronger_agent`;
- set `complexity` to the next level;
- record `previous_complexity`, `failed_worker`, `escalation_reason` and `handoff_note`;
- release its lock after state is committed and pushed.

If `Auto Worker 5.5max` cannot complete a worker-ready `XL` task:

```text
first ceiling -> status = needs_dispatcher_split, return_to_dispatcher_count = 1
retry-after-split ceiling -> status = needs_human
```

Dispatcher may split the returned task once. Split retry tasks must record `derived_from` and `retry_after_split = true`.

## Default Profile Objects

```json
[
  {
    "worker_id": "auto-worker-5.3-mini",
    "runner": "remote-automation-host",
    "phase": "phase_2",
    "runner_mode": "phase_2_runner",
    "enabled": true,
    "autostart_enabled": false,
    "model_family": "5.3 mini",
    "reasoning_effort": "medium",
    "allowed_complexity": ["S"],
    "selection_order": ["S"],
    "allowed_types": ["docs", "tests", "automation", "small-fix", "backlog"],
    "forbidden_types": ["production-secrets", "release-merge"],
    "max_active_locks": 1,
    "max_tasks_per_session": null,
    "model_limit_policy": {
      "new_task_min_remaining_percent": 0,
      "manual_reserve_percent": 0
    },
    "autostart_interval_minutes": 360,
    "queue_policy": "shared_queue_filter"
  },
  {
    "worker_id": "auto-worker-5.3",
    "runner": "remote-automation-host",
    "phase": "phase_2",
    "runner_mode": "phase_2_runner",
    "enabled": true,
    "autostart_enabled": false,
    "model_family": "5.3",
    "reasoning_effort": "very_high",
    "allowed_complexity": ["M", "S"],
    "selection_order": ["M", "S"],
    "allowed_types": ["docs", "tests", "automation", "focused-implementation", "implementation", "backlog", "contract"],
    "forbidden_types": ["production-secrets", "release-merge"],
    "max_active_locks": 1,
    "max_tasks_per_session": null,
    "model_limit_policy": {
      "new_task_min_remaining_percent": 0,
      "manual_reserve_percent": 0
    },
    "autostart_interval_minutes": 360,
    "queue_policy": "shared_queue_filter"
  },
  {
    "worker_id": "auto-worker-5.5",
    "runner": "remote-automation-host",
    "phase": "phase_2",
    "runner_mode": "phase_2_runner",
    "enabled": true,
    "autostart_enabled": false,
    "model_family": "5.5",
    "reasoning_effort": "medium",
    "allowed_complexity": ["L"],
    "selection_order": ["L"],
    "allowed_types": ["implementation", "integration", "tests", "migration", "docs"],
    "forbidden_types": ["production-secrets", "release-merge"],
    "max_active_locks": 1,
    "max_tasks_per_session": null,
    "model_limit_policy": {
      "new_task_min_remaining_percent": 15,
      "manual_reserve_percent": 10
    },
    "autostart_interval_minutes": 360,
    "queue_policy": "shared_queue_filter"
  },
  {
    "worker_id": "auto-worker-5.5max",
    "runner": "remote-automation-host",
    "phase": "phase_2",
    "runner_mode": "phase_2_runner",
    "enabled": true,
    "autostart_enabled": false,
    "model_family": "5.5",
    "reasoning_effort": "very_high",
    "allowed_complexity": ["XL", "L"],
    "selection_order": ["XL", "L"],
    "allowed_types": ["worker-ready-xl", "critical-implementation", "release-critical", "integration"],
    "forbidden_types": ["production-secrets", "release-merge-without-owner"],
    "max_active_locks": 1,
    "max_tasks_per_session": null,
    "model_limit_policy": {
      "new_task_min_remaining_percent": 15,
      "manual_reserve_percent": 10
    },
    "autostart_interval_minutes": 360,
    "queue_policy": "shared_queue_filter"
  }
]
```

Dispatcher must verify:

```text
task.status in [planned, needs_stronger_agent]
task.lock == free
task.worker_ready == true
task.dispatcher_decision == worker_ready
task.complexity matches worker.selection_order
task.allowed_paths are explicit
task.forbidden_paths are explicit
task.checks are explicit
task.acceptance_criteria are explicit
task.context_docs or task documentation are present
```

Non-ready `XL` tasks remain architecture containers until Architect/Dispatcher splits or marks them worker-ready.

Visible but incomplete task rows remain Dispatcher/Architect work, not Worker work.

Tasks with `needs_dispatcher_split` are routed to Dispatcher, not directly to workers.

Tasks with `human_working` are owned by active manual module work and must not
be claimed by workers. Tasks with `needs_replan_after_manual_work` must be
rebuilt by Dispatcher/Architect before worker pickup.

## Governance Roles Are Not Worker Profiles

`Auto Integrator` and `Auto Finalizer` are separate governance layers:

- `Auto Integrator` handles branch/PR assembly, merge order, stale branches, path overlap and missing checks.
- `Auto Finalizer` handles accepted-result closure, status synchronization, lock cleanup, changelog/release notes and final reports.

They do not take implementation tasks by `S/M/L/XL` complexity and should not be listed as `worker` values in dispatcher task packets.
