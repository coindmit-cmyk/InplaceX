# AI Task Routing

## Base Branch

Default base branch is project-specific. Use `.agent/project.md` or task packet.

Detailed branch, commit and assembly rules live in `docs/automation/BRANCH_COMMIT_INTEGRATION_PROTOCOL.md`.

For the AiStudio Agent Core repository itself, `develop` is the only normal
working integration branch. `release/main` is a stable distribution branch:
release-bound changes must be accepted on `develop` first, then promoted to
`release/main` by a release gate. Old feature, worker, integrator and Codex
branches should be archived or deleted after their accepted changes are present
on `develop`.

## Source Hierarchy

Canonical machine state lives under `AiStudio/Task_manager/`. Use it as the
primary queue, lock, directive, runner and event path. `docs/plans/` machine
state is a legacy fallback for older projects only, not an active source for new
state when `AiStudio/Task_manager/` is present.

Execution and diagnostics must read current state in this order:

1. GitHub freshness for the target base branch.
2. Stable Agent Core rules from `ai-project-agent:release/main`.
3. `AiStudio/Task_manager/task_queue.json`.
4. `AiStudio/Task_manager/agent_locks.json`.
5. `AiStudio/Task_manager/agent_events.jsonl` and process/runner state.
6. `AiStudio/Task_manager/owner_directives.json`, when present.
7. Pull Requests, Issues and recent commits.
8. Repository architecture/product docs referenced by the task packet.

Legacy `docs/plans` machine-state references and legacy task-document folders
are migration evidence only. Do not use them as active task inventory, lock state
or Doctor diagnosis state when `AiStudio/Task_manager/` exists. If a role
instruction or project file points there as primary state, report
`legacy_state_reference` and route to Agent Update Manager or Dispatcher repair.

`task_queue.json` is the current execution queue. Architecture docs can explain
intent, but they are not runnable tasks unless Dispatcher imported or referenced
them in a Worker Packet v2 row.

## Project Design Routing

Use Project Design when an owner asks to turn a raw idea, discussion or early
product direction into a complete pre-implementation design package.

Triggers include:

- `Project Design`;
- `Project Design Department`;
- `GPT Project Design`;
- `project_design`;
- `$aistudio-project-design`;
- requests to prepare product, requirements, documentation seed, architecture,
  project map and backlog-readiness before implementation.

Project Design uses:

```text
role: agent-core/.agent/roles/project-design.md
prompt: docs/agent/prompts/ProjectDesign.md
skill: .agent/skills/aistudio-project-design/SKILL.md
workflow: docs/agent/workflows/ProjectDesign/README.md
```

Project Design is a workflow role for GPT chats and planning sessions. It may
invoke DecisionCouncil, SystemArchitect, ProjectMapPlanner and BacklogPlanner
modes as workflow functions, but it does not replace the existing process flow:

```text
Director -> Architect -> Dispatcher -> Worker -> Integrator -> Finalizer
```

Project Design must not create final Worker Packet v2 claims or dispatch
Worker agents. Backlog-readiness output becomes Dispatcher input only after the
documented Project Design stage gates and snapshots exist.

Project Design gate behavior is strict:

- build owner discussion and project reality review in parallel;
- create Context Merge Snapshot, Proposed Change Draft and Delta Draft;
- run Decision Council Pass 1 before clarification questions;
- stop at `owner_questions_required` after ADC Pass 1;
- continue only after owner answers or explicitly waives questions with safe defaults;
- run Decision Council Pass 2 before final product, requirements, documentation, architecture, project map, backlog or finalization artifacts.

Generic approval such as "do it", "делай" or "у тебя pro режим, делай" does
not waive the owner-question gate.

## UX Design Routing

Use UX Design when a change affects a human-facing surface or when a task needs a UX Contract or UX Waiver.

Triggers include:

- `UX Design`;
- `UX Design Department`;
- `GPT UX Design`;
- `ux_design`;
- `$aistudio-ux-design`;
- requests for user journey, admin/dashboard UX, CLI UX, report UX, onboarding, error message design, sketch intake, reference research, visual direction or test visuals.

UX Design uses:

```text
role: agent-core/.agent/roles/ux-design.md
prompt: docs/agent/prompts/UXDesign.md
workflow: docs/agent/workflows/UXDesign/README.md
lenses: docs/agent/lenses/UX/README.md
skill: .agent/skills/aistudio-ux-design/SKILL.md
skill_catalog: docs/agent/skills/UX/README.md
schema: schemas/agent-control/ux_contract.schema.json
validator: scripts/agent_control/ux_design_validator.py
```

UX Design is standalone and project-agnostic. It can be invoked by Project Design, Architect, Dispatcher, Integrator, Finalizer or Reviewer.

Human-facing work requires a `UX Contract` or `UX Waiver` before it becomes worker-ready or finalization-ready. Backend-only/internal work can receive a waiver when no human-facing behavior changes.

Default UX level is `light`. Dispatcher, UX Design, Integrator or Finalizer may raise UX level. Lowering UX level requires waiver evidence.

Test visuals and reference research are level-based:

```yaml
test_visuals:
  light: optional
  standard: recommended
  strict: required
reference_research:
  light: optional
  standard: recommended
  strict: required
```

## Integration Protection Routing

Use Integration Protection when an accepted manual or automatic change must be
connected to required discovery, routing, documentation, map, evidence,
validation, version or finalization surfaces before it can be called complete.

Triggers include:

- `Manual Integration`;
- `Manual Integrator`;
- `Integration Protection`;
- `manual_integration`;
- requests to integrate a new role, workflow, mode, prompt, skill, lens, policy,
  script, schema, template, docs package or runtime behavior;
- cases where files exist but routing, indexes, maps or evidence are missing.

Integration Protection uses:

```text
role: agent-core/.agent/roles/integrator.md
policy: docs/agent/integration/INTEGRATION_PROTECTION_POLICY.md
surfaces: docs/agent/integration/SURFACE_MODEL.md
manifest: docs/agent/integration/MANIFEST_TEMPLATE.md
lenses: docs/agent/lenses/Integration/README.md
skills: docs/agent/skills/Integration/README.md
```

Execution modes:

- `AutoIntegrationMode` for queues, branches, PRs, worker evidence, events and handoffs.
- `ManualIntegrationMode` for current-chat/manual Codex integration.

ManualIntegrationMode may open a draft PR when checks/evidence are present and
protected gates are not bypassed. Draft PRs are dirty evidence and must not be
consumed directly by automation as final integration.

New entities without required surfaces or map coverage are `integration_incomplete`.
Legacy missing map coverage creates `reality_map_backfill` tasks unless it affects
current safety, routing, automation, release/adoption or source-of-truth resolution.

## Swarm Coordination Routing

Use Swarm Coordination only after Router has authorized a Parallel Work plan
and Architect or Dispatcher has identified a concrete coordination need.

Triggers include:

- `Swarm Coordination`;
- `swarm_coordination`;
- `agent topology`;
- `named agents`;
- `$aistudio-swarm-coordination`.

Swarm Coordination uses:

```text
role: .agent/roles/swarm-coordination.md
contract: docs/agent/orchestration/README.md
skills: docs/agent/skills/SwarmCoordination/README.md
validator: scripts/agent_control/swarm_coordination_validator.py
compiler: scripts/agent_control/swarm_coordination_compiler.py
```

The role is an authority-free supporting layer. It may validate topology,
bounded named messages, shared-context views, advisory consensus and stop
limits for existing work units. It cannot create tasks, expand scope, select
concrete models, increase Router-authorized capacity, launch agents, mutate
project state, integrate results or grant merge/release authority.

## Shared Queue Contract

Projects use one shared execution queue:

```text
AiStudio/Task_manager/task_queue.json
```

Do not create separate task queues per agent, per model or per worker slot.

## Model And Effort Declaration

Every interactive chat declares its execution recommendation before
substantive task work:

```text
Execution recommendation: <model>, reasoning <effort>, complexity <S|M|L|XL> - <short reason>.
```

The recommendation is selected from `.agent/model_routing_policy.json`; it is
not permission to bypass Router limits, authorization, leases or role scope.
Chats do not claim that they changed their own model. They report a material
mismatch when the active model is below the recommended capability for
high-risk work.

New executable tasks use exactly one complexity value:

| Complexity | Typical scope | Default model candidates | Default reasoning |
| --- | --- | --- | --- |
| `S` | narrow mechanical change or bounded check | `gpt-5.3-codex-spark`, `gpt-5.6-luna` | `medium` |
| `M` | ordinary implementation in one coherent area | `gpt-5.3-codex-spark`, `gpt-5.6-luna`, `gpt-5.6-terra` | `high` |
| `L` | cross-file or integration-sensitive implementation | `gpt-5.6-terra`, `gpt-5.6-sol` | `high` |
| `XL` | architecture-wide or high-risk coherent work; split when possible | `gpt-5.6-sol`, `gpt-5.6-terra` | `extra_high` |

Risk, retries, fresh limits and an explicit Next Run recommendation may raise
the final effort. `max` and `ultra` remain governed capability routes, not
synonyms for `XL`. Missing or unknown complexity on an identified executable
task routes to Dispatcher repair and must never fall back to a role-only model.

Worker profiles are filters over the shared queue. They may limit:

- complexity;
- task type;
- allowed or forbidden paths;
- live/secret access requirements;
- maximum active locks.

A task can optionally name `eligible_worker_profiles`, but this is routing metadata, not a separate backlog.

## Duplicate Prevention

Before creating or assigning a task, check:

- open or recent GitHub Issues;
- open or recent PRs;
- recent commits;
- changed paths;
- manual PRs and live-work notes;
- `AiStudio/Task_manager/task_queue.json`;
- relevant task pages.

If related work exists, update or reference it instead of creating a parallel task.

## Worker Profiles

Dispatcher assigns work by worker profile.

Task complexity is independent from worker identity:

```text
Task complexity = S / M / L / XL
Worker eligibility = Auto Worker profile + explicit task packet
```

See `docs/automation/WORKER_PROFILES.md`.

Default complexity routing:

| Complexity | Primary lane | Fallback lane |
| --- | --- | --- |
| `S` | `auto-worker-5.3-mini` | `auto-worker-5.3` after the `M` pool is empty |
| `M` | `auto-worker-5.3` | `auto-worker-5.5` after the `L` pool is empty |
| `L` | `auto-worker-5.5` | `auto-worker-5.5max` only for critically important `L` |
| `XL` | `auto-worker-5.5max` only when worker-ready | Architect/Dispatcher keeps non-ready `XL` as a container and splits it |

## Local LLM Parallel Debug Routing

When `AiStudio/Task_manager/local_llm_dispatch_policy.json` enables a task kind
for local LLM, Dispatcher must maximize small comparable packets instead of
leaving broad work as one row.

Default LLM-friendly packet shape:

- `packet_schema_version = 2`;
- `complexity = S` or `M`;
- narrow `docs` or `tests` task kind;
- one module/path family;
- no more than four `allowed_paths`;
- no more than six checks;
- no secrets, payment, billing, production deploy, credentials or broad migrations.

If work is useful for LLM comparison but too broad, Dispatcher should split it
into child Worker Packet v2 tasks and mark the parent
`dispatcher_decision = split_into_children`. Local LLM may only receive tasks
tagged by `llm_dispatch_tagger.py` with `llm_candidate = true` and
`llm_queue_state = ready`.

## Role Branches

Use role-aware branch names:

```text
docs/director/<YYYYMMDD>-short-name
docs/architect/<TASK-ID>-short-name
docs/dispatcher/<TASK-ID>-short-name
codex/auto-make/<TASK-ID>-short-name
codex/auto-worker-5-3-mini/<TASK-ID>-short-name
codex/auto-worker-5-3/<TASK-ID>-short-name
codex/auto-worker-5-5/<TASK-ID>-short-name
codex/auto-worker-5-5max/<TASK-ID>-short-name
codex/integrator/<BATCH-ID>-short-name
codex/finalizer/<BATCH-ID>-short-name
codex/make-human/<TASK-ID>-short-name
codex/module-<MODULE-ID>/<TASK-ID>-short-name
docs/idea/<TASK-ID>-short-name
ai/review/<PR-ID>-short-name
```

GPT Director and GPT Architect are docs/architecture lanes by default. GPT/Codex Dispatcher and Auto Make Tasks update task packets and queues. Module Companion records owner-led module work and task evidence. Auto Workers implement task packets inside allowed paths. Auto Integrator assembles ready branches/PRs. Auto Finalizer closes accepted results after approval and/or merge evidence. Make human runs a full human-mode task lifecycle and targets `develop` through branch/PR/merge evidence.

## Make Human Routing

Use `Make human` when the owner explicitly wants one chat to behave like a human engineer and close a task all the way to `develop`.

Make human may take `planned`, `needs_human`, `review`, `agent_done` or integration-ready tasks when the owner directs it and the required access/decision is available in the chat or repository. It must not take tasks that require undisclosed secrets, payments, production credentials or unresolved business decisions; those remain `needs_human` or `blocked`.

Make human uses:

```text
base branch: develop
branch: codex/make-human/<TASK-ID>-short-name
PR target: develop
final state: done only after merge evidence
```

## Status Routing

Workers may take only:

```text
status = planned
lock = free
```

Workers must skip:

```text
blocked
human_working
needs_replan_after_manual_work
needs_human
needs_stronger_agent
review
owner_approved
done
postponed
failed
```

## Manual Module Work Routing

`human_working` means owner-led manual work is actively in progress. It blocks
worker pickup until Module Companion records completion, routing or release.

`needs_replan_after_manual_work` means manual work partially implemented a task
or changed its scope. The original packet is stale and must be routed to
Dispatcher/Architect before any worker receives the remaining work.

## Phase 2 Active Routing

Phase 2 active files are the default adopted state.

Required active state:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

When `phase2_active` is true:

- Agent Update Manager may prepare active update branches, reports and PR bodies.
- Remote Automation Host / Local Agent Runner may claim tasks only when explicitly started or scheduled and allowed by `AiStudio/Task_manager/agent_runner_state.json`.
- Auto Workers still operate through explicit task packets and locks.
- No scheduler or runner starts automatically from adoption alone.

When `phase2_active` is false, Remote Automation Host / Local Agent Runner may produce dry-run reports only.
