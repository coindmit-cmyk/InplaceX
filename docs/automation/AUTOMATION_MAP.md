# Automation Map

## Primary Flow

```text
project AiStudio/Task_manager
  <- change_intake_router.py / change_intake_cycle.py for manual/chat git refs
  -> event_driven_scheduler.py
  -> status_orchestrator.py
  -> dispatcher / worker_pool / integrator / finalizer lanes
  -> reports, events, commits and GitHub refs
  -> dashboard snapshot and timer status
```

Timers are recovery triggers. Events and queue state decide whether work actually runs.

`orchestrator_recovery_supervisor.py` is intentionally independent from the
Status Orchestrator timer. The remote AiStudio PC runs it in `writer` mode so an
unleased stop, disable or failed unit is recovered after immutable-release,
checkout-freshness and pause-lease gates pass. Control/VPS hosts use `observer`
mode and never start a second canonical writer.

## Change Intake

`change_intake_router.py` and `change_intake_cycle.py` are the approved
manual/chat commit intake path. They compare a base/head ref, classify the
change, and optionally append one idempotent existing scheduler event:
`queue_changed`, `integration_requested`, `integration_routed` or
`task_packet_defect`.

They do not create worker-ready rows, launch workers, merge PRs, fake Finalizer
state or bypass release gates. Artifact Discovery evidence from the cycle is
read-only evidence for Dispatcher/Integrator.

## Controller Entry Point

`automation_controller.py` is the canonical dry-run-first entry point for multi-project automation. It reads the project registry and delegates to existing per-project scripts for `full`, `project`, `role` and `one-task` runs. The controller does not accept arbitrary shell commands.

## Current Unified Remote Runtime

The remote host uses project-specific status orchestrator systemd timers:

- `aistudio-status-orchestrator.timer`
- `eshop-status-orchestrator.timer`
- `myvpn-status-orchestrator.timer`

Legacy role timers are compatibility only and must not be the normal automation path.

Every intentional Status Orchestrator pause must have a local TTL lease. Raw
`systemctl stop` without that lease is treated as an outage and is reversed by
the recovery supervisor.

## V1 Packages

1. Documentation and contract freeze.
2. Dashboard manual-run repair.
3. Exact one-task mode.
4. Multi-project automation controller.
5. Host-level execution leases.
6. VPS Command Bus and remote pull consumer.
7. Dashboard v1 reliability and control UI.
8. Project Standard adoption.
9. Telegram operator after Command Bus acceptance.

## Host Execution Leases

`execution_lease_manager.py` owns host-level runtime leases under `runtime/execution-leases/`. It enforces global, per-project and per-model worker caps with TTL cleanup. Worker orchestration should acquire leases before starting write-capable worker lanes and release or heartbeat them during long runs.
