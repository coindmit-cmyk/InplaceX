# Minimal context per model call

Owner decision: 2026-09-30. Script + Luna is the default implementation route;
Sol handles bounded escalation. Every failure, including Sol failures, is diagnosed by Luna.

| Call | Supply | Do not supply |
|---|---|---|
| Luna context selection | One task, short project restrictions, allowed paths, candidate filenames | Repository dump, all source, AiStudio library, full skill/agent catalog |
| Luna implementation | Task and acceptance, selected original sources, editable files, relevant contracts/tests | Previous chats, task queue, unrelated project documentation |
| Luna diagnosis | Task, current failure once, changed/error-related files, short prior failure summaries | Successful logs, repeated full error history, all earlier prompts |
| Sol repair | Same task, current relevant sources, Luna diagnosis, short failure facts | Entire Luna conversation, raw historical logs, startup catalog |
| Host checks/Git/state | Exact scoped check and saved candidate | No model call for successful logs, status polling or ordinary Git operations |

The host may read registry/maps to identify the task, but must not forward them wholesale.
Read project-specific product/architecture constraints only for affected behavior;
extract necessary restrictions into the small `pipeline-context.json` rules or task.
Load a particular local skill/reference only when needed, never the catalog. Explicit
`reference_paths` can add a chosen project file to Luna's candidate list.

Keep the project's current task authority; SQL projects remain SQL and local-file
projects remain local-file projects. This document does not configure or activate a
queue adapter. Preserve paused work and pending owner-only product/hardware decisions.
New task branch: current local develop; resume unfinished work in its existing branch.
Preserve unrelated changes. Run only scoped checks; do not disguise a full suite in a wrapper.
Failed checks never become success. Stop at attempt/credit/context limits with a recoverable result.

The reusable module is `direct_pipeline.py`; deployment and task-source adapters are
host concerns, not input for each model. Reference installation on this PC:
`D:/Work/DevOps/AiStudio/Develop/scripts/agent_control/direct_pipeline.py`.
Run instructions: `D:/Work/DevOps/AiStudio/Develop/docs/automation/LUNA_PIPELINE.md`.
Reading or using that small module does not authorize loading the entire AiStudio context.

Record each call's role/model/effort, selected files, input characters, tokens/cache,
time, estimated credits, failures and reason for escalation. Record manager assistance
separately. Unknown usage is null; all failed paid attempts count toward task cost.
