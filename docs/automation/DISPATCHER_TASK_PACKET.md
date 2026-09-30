# Dispatcher Task Packet Contract

## Purpose

Defines the exact packet GPT Dispatcher / Codex Dispatcher / Auto Make Tasks gives to an Auto Worker.

Integrator and Finalizer governance runs use `INTEGRATION_FINALIZATION_PROTOCOL.md` instead of implementation task packets.

## Packet

```json
{
  "task_id": "TASK-001",
  "title": "Example task",
  "worker": "auto-worker-5.5",
  "recommended_model": "5.5",
  "reasoning_effort": "medium",
  "machine_id": "ubuntu-agent-server",
  "source_lane": "auto-make-tasks",
  "base_branch": "develop",
  "branch": "remote/ubuntu-agent-server/auto-worker-5-5/TASK-001-example",
  "status_required": "planned",
  "lock_required": "free",
  "context_docs": [],
  "packet_schema_version": 2,
  "worker_instructions": [],
  "traceability": {},
  "doc_refs": [],
  "input_refs": {},
  "allowed_paths": [],
  "forbidden_paths": [],
  "protected_paths": [],
  "conflict_paths": [],
  "manual_change_notes": null,
  "acceptance_criteria": [],
  "checks": [],
  "script_actions": [],
  "output_contract": {},
  "stop_conditions": [],
  "previous_complexity": null,
  "return_to_dispatcher_count": 0,
  "derived_from": null,
  "retry_after_split": false,
  "handoff_note": null,
  "report_required": true
}
```

Dispatcher must refresh GitHub state and worker profiles before creating a packet.

## Worker-Ready Gate

A visible task in `AiStudio/Task_manager/task_queue.json` is not automatically worker-ready.

Dispatcher / Auto Make Tasks must classify queue items before workers can claim them:

```text
visible planning item -> needs_task_packet
complete implementation packet -> planned + worker_ready = true
unclear architecture item -> needs_architect
owner decision required -> needs_human
```

Tasks like `NW-*`, `FAM-*`, `FRI-*`, `R4S-*` or other imported rows may stay visible in the queue as planning inventory, but workers must not claim them until Dispatcher or Architect adds a complete worker packet.

A worker-ready implementation task must include:

- `packet_schema_version = 2`;
- `worker_instructions`;
- `traceability`;
- `doc_refs`;
- `input_refs`;
- `output_contract`;
- `script_actions`;
- `complexity`;
- `priority`;
- `type`;
- `recommended_agent` or `eligible_worker_profiles`;
- `allowed_paths`;
- `forbidden_paths`;
- `acceptance_criteria`;
- `checks`;
- `context_docs` or equivalent task documentation;
- source provenance for architecture/manual/legacy origin;
- clear dependency/blocker state.

The v2 fields make normal worker tasks complete execution packets instead of
titles plus path hints:

- `worker_instructions`: explicit ordered instructions for the Worker run.
- `traceability`: task id, canonical id/target, source lane, source file,
  provenance, derived-from links and repair history.
- `doc_refs`: required project docs and architecture/context references the
  Worker must read before editing.
- `input_refs`: base branch/ref, allowed/forbidden paths, source branches,
  prior artifacts or other files that form the task input.
- `output_contract`: required changed-path discipline, worker report,
  task-state transition, event emission and success/blocker result shape.
- `script_actions`: concrete checks/scripts the Worker must run, with
  required/optional state and failure routing.

If any required packet field is missing or vague, Dispatcher must set or keep the task as `needs_task_packet`, `needs_architect` or `needs_human` rather than leaving it as claimable worker work.

For packet-specific gaps that Dispatcher can repair without architecture or
owner decisions, use the repair loop instead of silently skipping the row:

```text
incomplete worker packet
-> status = needs_dispatcher_repair
-> dispatcher_decision = needs_dispatcher_repair
-> missing_packet_fields + repair_request + repair_owner + next_action
-> repaired packet_schema_version = 2
-> planned + worker_ready = true
```

Run the deterministic repair helper before worker selection:

```text
python scripts/agent_control/dispatcher_packet_repair.py --queue AiStudio/Task_manager/task_queue.json --apply
```

If the helper cannot infer the v2 packet from existing task fields, it leaves a
visible `needs_dispatcher_repair` row with exact missing fields. Dispatcher must
complete the missing data or route to `needs_architect` / `needs_human` with a
concrete question.

Worker `allowed_paths` are always repository-relative write scope. Absolute
runtime paths, parent traversal and read-only external context references are
not valid scope, even when they appear in `context_docs`, `input_refs` or
historical repair evidence. A verified exact scope repair clears only its stale
Dispatcher integration route; immutable failed-attempt evidence remains
available for audit and retry comparison.

If all worker packet fields are already present, Dispatcher must not leave the
row as `needs_task_packet`. Before committing Dispatcher output, run the packet
normalizer/promoter and convert complete packets to
`status = planned`, `worker_ready = true` and
`dispatcher_decision = worker_ready`.

`needs_architect` is not a bulk fallback. Use it only when a task requires a
new architecture/product decision, cross-module design, or an owner/architect
split that Dispatcher cannot safely perform. If the item is concrete but still
has generic packet text, vague paths, missing checks or missing acceptance
criteria, keep it as `needs_task_packet` and complete or split the packet in a
Dispatcher pass.

Every `needs_architect` row must include at least one task-specific field:

```text
architect_request
architecture_question
split_reason
```

A generic reason such as "too broad or container-like" is not sufficient by
itself. The row must say what Architect needs to decide or how the work should
be split before Dispatcher can create worker-ready packets.

## Legacy Intake And Inventory

Dispatcher and Auto Make Tasks must treat `AiStudio/Task_manager/task_queue.json` as the single execution queue, but not as the only place where unresolved work may exist.

Before creating new worker packets, Dispatcher must scan known legacy and planning sources, including:

- `mvp_distribution` documents or folders;
- old MVP/backlog/task distribution docs;
- imported backlog files under `docs/plans/`;
- architecture docs that still contain unchecked task lists;
- GitHub Issues marked as planned or accepted but not present in the shared queue.

Dispatcher must collect every relevant legacy/backlog item into `AiStudio/Task_manager/task_queue.json` as inventory, even when it is not ready for workers.

Inventory rows must include:

- `status`;
- `worker_ready = false` unless the full implementation packet is complete;
- `packet_status`;
- `normalization_status`;
- `source_file`;
- `source_lane`;
- `provenance`;
- original legacy ID when available;
- source title/summary;
- reason it is not worker-ready, when incomplete.

Recommended inventory states:

```text
normalization_status = inventory_only       # found and recorded, packet not built yet
normalization_status = needs_task_packet    # needs Dispatcher packet completion
normalization_status = needs_architect      # needs architecture split/decision first
normalization_status = duplicate_linked     # represented by another queue item
normalization_status = stale_or_superseded  # kept for traceability, not implementation
normalization_status = worker_ready         # full packet is complete
```

If a legacy item is already represented in `task_queue.json`, Dispatcher must keep or create a lightweight inventory record that links to the canonical task instead of duplicating implementation work.

If a legacy item is stale, superseded or unclear, Dispatcher must still record it as inventory with `normalization_status = stale_or_superseded` or `needs_architect` / `needs_human`. It must not silently drop the item.

Dispatcher does not finish intake until the legacy/backlog sources it inspected are represented in the queue as inventory or linked duplicates.

## Dispatcher Decision Pass

Inventory intake is only the first half of Dispatcher work. Dispatcher is not done until every visible queue item has one explicit decision.

Allowed Dispatcher decisions:

```text
dispatcher_decision = worker_ready          # complete packet, workers may claim
dispatcher_decision = needs_task_packet     # Dispatcher must add missing packet fields
dispatcher_decision = needs_dispatcher_repair # Dispatcher must repair packet metadata before claim
dispatcher_decision = needs_architect       # Architect must split/decide architecture first
dispatcher_decision = needs_human           # owner/access/secret/product decision required
dispatcher_decision = split_into_children   # parent split into child tasks
dispatcher_decision = duplicate_linked      # duplicate linked to canonical task
dispatcher_decision = stale_or_superseded   # kept only for traceability
```

`status = planned` is valid for workers only when `worker_ready = true` and `dispatcher_decision = worker_ready`. A queue row with `status = planned` but missing complexity, paths, checks or acceptance criteria is a Dispatcher error, not worker work.

For large tasks, Dispatcher must either split the task into child tasks and record `split_into`, or route it to `needs_architect` with a reason. It must not leave a broad parent as plain `planned`.

## Local LLM Granularity

When local LLM parallel debugging is enabled, Dispatcher should maximize safe
LLM-eligible work by splitting broad items into small Worker Packet v2 children.
The goal is not to send risky work to LLM, but to create more precise packets
that can be compared against Codex output.

Default local LLM packet shape:

```text
complexity <= M
packet_schema_version = 2
one task kind, preferably docs or tests
one module or narrow path family
allowed_paths <= 4
checks <= 6
no secrets, production deploy, payment, billing, credentials or broad migrations
```

If an item is otherwise suitable but exceeds these limits, Dispatcher should
set or preserve a parent row with `dispatcher_decision = split_into_children`
and create child packets that satisfy local LLM granularity. If Dispatcher
cannot split safely because a design decision is missing, route to
`needs_architect` with a concrete `split_reason`.

After rebuilding or repairing packets, run:

```text
python scripts/agent_control/llm_dispatch_tagger.py --project-root <project> --apply --json
```

Only tasks with `llm_candidate = true` and `llm_queue_state = ready` may be
handed to the local LLM. `llm_granularity_status = needs_dispatcher_split` is a
Dispatcher instruction to split or repacketize before LLM comparison.

For duplicate tasks, Dispatcher must set `dispatcher_decision = duplicate_linked`, `normalization_status = duplicate_linked` and `canonical_task_id`.

For unresolved inventory, Dispatcher must set `dispatcher_next_review_at` or route to Architect/Human. Open-ended `inventory_only` without a next action is `dispatcher_incomplete`.

Every Dispatcher run should report:

- inventory items found;
- items made worker-ready;
- items still needing task packets;
- items routed to Architect;
- items routed to Human;
- duplicate-linked items;
- stale/superseded items;
- split parents and created child task IDs.

After every Dispatcher decision pass, run:

```text
python scripts/agent_control/dispatcher_decision_guard.py --queue AiStudio/Task_manager/task_queue.json
```

If the guard reports `complete_packet_left_needs_task_packet`,
`needs_architect_spike`, `no_worker_ready_after_dispatcher`,
`needs_architect_without_request`, `generic_needs_architect_reason`,
`worker_packet_v2_incomplete` or `dispatcher_repair_without_contract`, the
Dispatcher pass is not complete. The Dispatcher must either normalize complete
packets to worker-ready, create missing packet fields, split concrete work into
child packets, or keep genuinely incomplete concrete rows as `needs_task_packet`
or `needs_dispatcher_repair` for the next packet pass. It must not commit a
queue where complete packets are left unclaimable, v2 worker packets are
incomplete, repair rows lack explicit missing fields/next action, most concrete
work was sent to `needs_architect`, `needs_architect` lacks a concrete
Architect request, or no useful `planned + worker_ready` work remains.

Dry-run or test Dispatcher runs are read-only evidence only. They may write
temporary reports under ignored runtime paths, but they must not create commits,
push branches, open PRs, or update durable queue/activity files unless the owner
explicitly requested an apply run.

Allowed default `worker` values:

```text
auto-worker-5.3-mini
auto-worker-5.3
auto-worker-5.5
auto-worker-5.5max
```

If scope is ambiguous, mark the task `needs_human` or return it to Architect.

If a worker escalates a task, Dispatcher should preserve the handoff note and route by the new complexity. If an `XL` task returns as `needs_dispatcher_split`, Dispatcher may split it only once. Split retry tasks must carry:

```json
{
  "derived_from": "TASK-001",
  "retry_after_split": true
}
```

If a retry-after-split task still cannot be completed, it must go to `needs_human` instead of returning to Dispatcher again.

Do not assign `auto-integrator` or `auto-finalizer` as implementation workers. They are governance lanes after worker execution.
