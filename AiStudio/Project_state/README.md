# Project State Hub

`AiStudio/Project_state/` is the project-local state hub for reality, intake,
memory, links, decisions, indexes, reports and snapshots.

It does not replace:

- `AiStudio/Task_manager/` as the executable work queue.
- `PROJECT_MAP.md` as the current human-readable module/task binding view.
- Artifact Discovery as the scanner/inventory/finding provider.
- Change Intake as the event intake layer.
- Integrator, Dispatcher or Finalizer as decision and execution departments.

Project State Hub collects, links, summarizes, archives, prepares and routes.
It must not run workers, mark tasks `worker_ready`, merge PRs, finalize releases
or treat Local LLM output as fact without script/report evidence.

## Blocks

- `registry/` - stable object IDs and metadata.
- `inventory/` - detected project contents and scanner outputs.
- `input/` - durable PR-backed packages from GPT, Codex and external sources.
- `intake/` - raw incoming signals and their routing lifecycle.
- `links/` - structural and semantic graph edges.
- `decisions/` - accepted/rejected decisions and rationale.
- `memory/` - project-local memory only.
- `reports/` - evidence and audit trail.
- `indexes/` - fast summaries for chats and agents.
- `snapshots/` - historical state checkpoints.

See `docs/agent/project-state/README.md` for the full architecture.

`input/` and `intake/` are intentionally different. Input packages remain in
`develop` as durable provenance; `intake/inbox/` remains the transient event
routing area. Neither surface creates worker-ready tasks.
