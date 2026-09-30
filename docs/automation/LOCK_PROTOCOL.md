# Lock Protocol

## Core Rules

- One active task lock per worker.
- One task per active lock.
- A worker session may process multiple tasks sequentially.
- Worker refreshes locks before every new task.
- Stale locks are cleared only by owner or a dedicated lock-maintenance task.
- Open PR moves task to `review` and prevents retaking it.

## Lock Object

```json
{
  "task_id": "TASK-001",
  "state": "locked",
  "by": "auto-worker-5.5",
  "machine_id": "ubuntu-agent-server",
  "branch": "remote/ubuntu-agent-server/auto-worker-5-5/TASK-001-example",
  "at": "2026-06-07T00:00:00+00:00",
  "expires_at": "2026-06-07T08:00:00+00:00"
}
```
