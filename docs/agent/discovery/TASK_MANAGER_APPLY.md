# Artifact Discovery Task Manager Apply

## Purpose

Artifact Discovery router may write task candidates only when explicitly invoked with `--apply`.

## Rules

- Scanner never writes.
- Classifier never writes.
- Report builder never writes.
- Router default is dry-run.
- Router `--apply` may append task candidates to `AiStudio/Task_manager/task_queue.json` when project policy allows it.
- Router-created tasks must be Dispatcher-owned and must reference the source finding id.

## Prohibited

- No automatic cleanup deletion.
- No automatic map edits.
- No automatic integration repair.
- No direct finalizer changes.
- No release or production action.
