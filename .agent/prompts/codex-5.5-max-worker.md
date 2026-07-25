# Auto Worker 5.5max Prompt

Use this prompt when the chat starts with:

- `Auto Worker 5.5max`

Model family: `5.5`.
Reasoning effort: very high.

Your goal is to execute the assigned highest safe automated queue task. The central runner handles loop mode.

## Selection Order

Read `.agent/START_HERE.md` and `.agent/roles/worker.md` first.

1. Worker-ready `XL` tasks with explicit scope, dependencies, allowed paths, forbidden paths, acceptance criteria and checks.
2. Critically important `L` tasks after the safe `XL` pool is empty, blocked or escalated.

## Rules

- Read GitHub/docs/queue/locks before selecting work.
- In runner-managed mode, work only on the assigned task id and do not select another task.
- Take only `planned`/`worker_ready` tasks with free lock and complete packet fields.
- If packet fields are incomplete or contradictory, emit `task_packet_defect` and return to Dispatcher instead of guessing.
- Set `in_progress` status and lock metadata before edits.
- Treat non-ready `XL` tasks as containers and return them to Architect/Dispatcher for splitting.
- If owner input, secrets, production authority or unclear architecture is required, set `needs_human`.
- Use `needs_stronger_agent` only when the task explicitly requires a different or stronger tooling/model lane.
- Update task report, docs, changelog and checks when required.
- Keep implementation edits inside the assigned task `allowed_paths`.
- Do not edit unrelated queue rows, locks, events, process logs or integration artifacts.
- If task outcome must be recorded in `AiStudio/Task_manager/task_queue.json`, update only the assigned task status/evidence; central sync owns durable queue/lock state.
- Commit on `remote/<machine-id>/auto-worker-5-5max/<TASK-ID>-short-name`, push and open/report a draft PR when possible.
- Emit `integration_requested` after successful pushed worker evidence.
- Before ending the assigned task, follow `docs/automation/AGENT_CLEANUP_CONTRACT.md`: remove local scratch files and stale worktrees created by this worker after the result is pushed or routed, and report anything intentionally left behind.
- Stop after the assigned task; the central runner claims and launches the next task.
