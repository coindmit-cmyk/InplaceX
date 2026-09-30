# AiStudio Project Standard

Status: v1 active, v2 contracts staged

## Purpose

The standard defines the expected shape of an AiStudio-managed project. It is a navigation and validation layer, not a duplicate of every detailed policy.

Project Standard v2 extends this document through focused contracts instead of replacing existing controller, Command Bus, worker, integrator or dashboard mechanisms:

- `PHYSICAL_WORKSPACE_LAYOUT.md`
- `BRANCH_MODEL_V2.md`
- `VERSION_NAVIGATION_GATE.md`
- `PROJECT_REBUILD_CONTRACT.md`
- `PROJECT_CLEANUP_CONTRACT.md`
- `DOCUMENTATION_MAINTENANCE_GATE.md`
- `ACTION_REPORT_CONTRACT.md`

The Director handoff source is preserved under `docs/plans/aistudio-project-standard-v2/`.

## Branch Model

- `develop` is the active development base.
- `release/main` is the stable release branch when the project has a release line.
- `main` is not a working branch unless the repository explicitly documents an exception.
- Temporary automation branches use the `AiStudio/Agent/<role>/...` namespace.
- Rescue/archive branches must include `rescue`, `rebuild`, or `archive` and must leave a Task Manager event or report.

## Required Project Structure

```text
.agent/                         Project-local agent entrypoints and cached version metadata
AiStudio/Task_manager/          Canonical live task, lock, event and directive state
docs/AISTUDIO_PROJECT_INDEX.md  Project-local navigation, preserved after creation
docs/automation/                Shared Agent Core automation docs copied from release/main
```

Project code, docs and dependencies remain in normal project folders. Release branches should contain product code and release docs, not live automation queues, except for the Agent Core repository itself.

## Task Manager Contract

`AiStudio/Task_manager` is the primary state path. `docs/plans` is legacy fallback only.

Required files:

- `task_queue.json`
- `agent_locks.json`
- `agent_events.jsonl`
- `owner_directives.json`
- `agent_activity_state.json`
- `process_locks.json`

## Agent Core Contract

Agents must use the current Agent Core from GitHub `release/main` or from a central runner that has just updated from it. Project-local `.agent/agent_version.json` is cached adoption metadata, not the authoritative release version.

## Work Execution

- Dispatcher creates or repairs Worker Packet v2 tasks.
- Workers claim eligible tasks through the claim layer.
- Integrator handles metadata, path and check repairs unless there is an explicit product defect.
- Finalizer only finalizes verified integration packages.
- Every failed or blocked route must produce a next owner, event, or repair task.

## Validation

A project is standard-conformant when the local project index exists or is planned for bootstrap, Task Manager files are valid, active Agent Core scripts are discoverable from the manifest/catalog, and live automation state is not promoted into product release branches.
