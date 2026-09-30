# Control Commands

Dashboard, CLI and future Telegram use the same command model. They do not run arbitrary shell commands and they do not replace GitHub or project Task Manager as source of truth.

## Lifecycle

```text
queued -> approved -> leased -> running -> succeeded | failed | rejected | cancelled | expired
```

## V1 Actions

Read-only: `get_status`, `list_tasks`, `get_task`, `get_logs`, `explain_failure`.

Safe execution: `run_project`, `run_task`, `run_role`, `pause_temporary`, `resume`.

Not in first control release: arbitrary shell, production deploy, secret rotation, unscoped delete, unreviewed release merge.

Commands require actor, project, action, risk, timestamps, idempotency key and result reference. Secrets and raw credentials are forbidden in command payloads.

## Local Automation Controller

Use `scripts/agent_control/automation_controller.py` as the canonical local entry point. Default mode is dry-run planning; `--apply` is required to execute delegated project commands. Supported modes: `full`, `project`, `role`, `one-task`, `status`.

Example dry-run:

```bash
python scripts/agent_control/automation_controller.py --registry templates/agent-control/projects.example.json --mode full --json
```

## Command Bus v1

Dashboard, CLI and future Telegram create durable commands in `runtime/command-bus/commands.json` through `command_bus.py`. `command_consumer.py` leases queued commands and executes only approved high-level actions through `automation_controller.py`; it never accepts raw shell commands. Local Dashboard control enqueues a command and starts one consumer pass. Mirror mode stays read-only until a remote pull consumer is deployed.

Command lifecycle: `queued -> leased -> running -> succeeded|failed`; `cancelled` and `expired` are reserved lifecycle states.
