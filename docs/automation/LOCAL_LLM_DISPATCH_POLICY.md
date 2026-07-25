# Local LLM Dispatch Policy

## Purpose

Dispatcher must keep two separate tags on worker-ready tasks:

- `llm_candidate`: whether a task kind may be evaluated or executed by a local LLM at all.
- `llm_execution_mode`: how the task is executed.

Allowed execution modes:

```text
codex_only      # local LLM must not work this task
parallel_debug  # Codex and local LLM both work/assess; compare outputs and collect evidence
llm_only        # local LLM may work solo after promotion threshold is met
```

`parallel_debug` is the default learning mode for candidate task kinds. It is
used to build the comparison/evidence base without trusting the LLM as the only
executor. `llm_only` is allowed only after the policy evidence reaches the
promotion threshold, for example `100/100` successful comparable tasks with zero
failures for the same task kind, packet contract, prompt and model.

## Policy File

The canonical project policy is:

```text
AiStudio/Task_manager/local_llm_dispatch_policy.json
```

Projects may start from:

```text
templates/agent-control/local_llm_dispatch_policy.example.json
```

The policy records per `task_kind`:

- whether it is an LLM candidate;
- execution mode;
- queue/concurrency limits;
- granularity limits;
- target model/prompt;
- evidence counts;
- promotion threshold.

## Dispatcher Tagging

Run:

```text
python scripts/agent_control/llm_dispatch_tagger.py --project-root <project> --apply --json
```

The tagger writes:

- `llm_candidate`;
- `llm_execution_mode`;
- `llm_task_kind`;
- `llm_granularity_status`;
- `llm_dispatch_reason`;
- `llm_dispatch_policy`;
- `llm_parallel_required`.
- `llm_queue_key`;
- `llm_queue_state`;
- `llm_queue_position`;
- `llm_queue_concurrency`;
- `llm_queue_reason`.
- `llm_triage_only`;
- `llm_triage_route_on_pass`.

The tagger only allows local LLM consideration for worker-ready
`packet_schema_version = 2` tasks with the full Worker Packet v2 fields. High
risk scope remains `codex_only`.

Default candidate kinds are `docs` and `tests` in `parallel_debug`. The local
LLM is not trusted as a solo executor during cleanup launch.

Default granularity:

```json
{
  "granularity": {
    "max_complexity": "M",
    "max_allowed_paths": 4,
    "max_checks": 6
  }
}
```

Tasks that exceed granularity are tagged `codex_only` with
`llm_granularity_status = needs_dispatcher_split`. Dispatcher should split or
repacketize them into smaller Worker Packet v2 children when safe.

## Local LLM Queue

Local LLM execution is serialized by task kind by default. A task can be an
`llm_candidate` and still wait until its queue slot is free.

Default policy:

```json
{
  "queue": {
    "enabled": true,
    "concurrency_per_kind": 1
  }
}
```

Queue states:

```text
not_applicable  # task is not eligible for local LLM execution
ready           # local LLM may take this task now
waiting         # another task of the same kind must finish first
in_progress     # local LLM already claimed/started this task
claimed/running # accepted active aliases
```

The dispatcher/local runner must only hand a task to the local LLM when
`llm_candidate = true` and `llm_queue_state = ready`. An existing
`llm_queue_state` of `claimed`, `running`, or `in_progress`, or an
`llm_claimed_by`/`llm_started_at` value, occupies the task-kind queue slot.

If the configured local LLM backend is unavailable, the runner must not mark
the task failed as worker output. It should leave the task queued, record the
backend readiness failure as process evidence, and keep Codex as the execution
owner for `parallel_debug` tasks.

## Planning Cycle

Use the planning cycle before the remote host calls the local LLM:

```text
python scripts/agent_control/local_llm_planning_cycle.py --project-root <project> --apply --write-prompts --write-report --json
```

The planning cycle is deterministic and does not call the model. It:

- refreshes `llm_candidate`, `llm_execution_mode` and queue state through the
  dispatch tagger;
- uses the packet planner to reject broad/noisy packets before prompt creation;
- writes prompts only for `llm_queue_state = ready` tasks;
- leaves same-kind `waiting` tasks in the report for later runs;
- writes `AiStudio/Task_manager/reports/local_llm_planning_report.json` when
  `--write-report` is used.

Without `--apply`, the queue is not changed. Without `--write-prompts`, prompt
files are not created. This lets a laptop-side control run inspect the plan
without acting as the local LLM execution host.

## Runtime Cycle

After planning writes prompt files, the approved remote host may run:

```text
python scripts/agent_control/local_llm_runtime_cycle.py --project-root <project> --apply --execute-model --write-report --json
```

The runtime cycle is the only local LLM lane that calls the model for ordinary
task prompts. It:

- reads `AiStudio/Task_manager/llm_parallel_debug/prompts/*.prompt.json`;
- skips prompts that already have matching response files;
- calls `local_llm_adapter.py` for at most one ready task prompt by default;
- writes responses to `AiStudio/Task_manager/llm_parallel_debug/responses/`;
- marks the task `llm_queue_state = response_ready` so the next evidence cycle
  can validate it;
- leaves the task queued when the backend is unavailable instead of marking a
  worker failure.

The runtime cycle does not apply patches, commit code, mark tasks done, or count
promotion evidence. Those remain the job of Codex/Integrator/Finalizer and the
evidence cycle.

## Idle Learning

If no task prompt is ready, the runtime cycle may use the local LLM in
`advisory_only` idle-learning mode:

```json
{
  "idle_learning": {
    "enabled": true,
    "mode": "advisory_only",
    "max_prompts_per_cycle": 1,
    "roles": ["dispatcher_research", "architect_research", "reviewer"]
  }
}
```

Idle learning lets the remote host spend free time looking for safer task
splits, prompt-contract improvements, or review findings. Idle output is stored
under `AiStudio/Task_manager/llm_idle_learning/`. It is never worker evidence,
never eligible for `llm_only` promotion counters, and must be consumed as an
advisory input by Dispatcher, Architect, Reviewer or Codex.

## Evidence Cycle

After the approved remote host writes local LLM response files, run:

```text
python scripts/agent_control/local_llm_evidence_cycle.py --project-root <project> --apply --write-report --json
```

The evidence cycle is also deterministic. It:

- reads `AiStudio/Task_manager/llm_parallel_debug/responses/*.json` by default;
- runs the local LLM quality gate for each response;
- records `llm_last_quality_gate`, `llm_last_response_path`,
  `llm_finished_at` and `llm_evidence_history` on the task;
- changes the task `llm_queue_state` to `completed` or
  `failed_quality_gate`;
- increments `attempts` plus `successes` or `failures` in
  `local_llm_dispatch_policy.json`.

Each response path is recorded only once. If the cycle is run again against the
same response file, it reports `already_recorded` and does not increment
promotion evidence again.

### Remediation triage

An exact remediation kind may set `triage_only = true`. This means the local
model diagnoses packet/source consistency; it does not edit project files,
complete the task or act as Integrator.

`triage_route_on_pass` supports two values:

```text
evidence_only       # safe default; record comparison evidence only
dispatcher_repair   # route a validated blocker back to Dispatcher
```

The Dispatcher route is valid only when the quality gate passes and the model
verdict is `blocked` or `needs_worker_fix`. The evidence cycle then sets
`status`, `dispatcher_decision`, `packet_status` and `normalization_status` to
`needs_dispatcher_repair`, clears worker readiness and records status history.
Any gate failure leaves the original worker route intact for Codex fallback.

Current policy keeps Project Map remediation `codex_only`. Task Pipeline
remediation is a `parallel_debug` candidate with `evidence_only` in distributed
templates. A host-local canary policy may opt into `dispatcher_repair`; this is
not `llm_only` promotion and must not mutate the canonical queue until the
adopted project has the tested Agent Core release.

Task Pipeline promotion evidence must come from a read-only benchmark with 100
unique task ids and unique contract fingerprints, a source queue hash, fresh
responses and zero quality failures. Use minimal task context so terminal
history cannot override the triage contract. The 2026-07-12 independent MyVPN
holdout met this evidence threshold at 100/100, but production routing still
requires release adoption and a bounded live canary with Codex fallback.

### Pre-worker activation

`pre_worker.enabled` does not activate laptop execution. It allows the tested
policy to be adopted, while the approved remote scheduler must also pass
`--auto-local-llm-pre-worker`. Default MVP limits are one task per cycle, one
attempt per task and `fallback=codex`.

Existing project policies must be updated through
`local_llm_policy_adopter.py`. The adopter refuses evidence below 100/100,
requires unique task ids and contract fingerprints, checks queue immutability
and preserves project-specific evidence counters and unrelated task kinds.

Dashboard evidence is reported under `local_llm_pre_worker`: pending, attempted,
Dispatcher-routed, quality/backend/no-blocker fallback and avoided external
worker launches.

## Orchestrator Entry Point

On the approved remote host, the local LLM maintenance lane can be run through
the status orchestrator:

```text
python scripts/agent_control/status_orchestrator.py --project-root <project> --local-llm-cycle --apply --json
```

This explicit lane runs evidence postflight first, prepares the next prompt
batch, then runs the local LLM runtime cycle. If no task prompt is ready, the
runtime may create one advisory idle-learning prompt. It is intentionally not
selected automatically from generic worker signals; the remote host or owner
automation must request `--local-llm-cycle` so laptop-side control runs remain
inspection-only by default.

## Promotion

To move a task kind from `parallel_debug` to `llm_only`, update the policy only
after comparable evidence meets the threshold. Example:

```json
{
  "task_kinds": {
    "log_summary": {
      "llm_candidate": true,
      "execution_mode": "llm_only",
      "evidence": {
        "attempts": 100,
        "successes": 100,
        "failures": 0
      }
    }
  }
}
```

Until then, local LLM output is advisory/comparison evidence and Codex remains
the authoritative executor.

## Prompt And Quality Gate

Local LLM runs should use Worker Packet v2 as the prompt source:

```text
python scripts/agent_control/local_llm_prompt_builder.py --project-root <project> --task-id <task> --output <prompt.md>
```

The prompt builder emits a strict JSON response contract. It includes the task
packet, selected `doc_refs`, allowed/forbidden paths and negative examples for
known bad output such as placeholder task ids, generic evidence or broad paths.

After the model responds, run the quality gate:

```text
python scripts/agent_control/local_llm_quality_gate.py --project-root <project> --task-id <task> --response <response.json> --json
```

The gate must pass before an LLM response can count as successful comparison
evidence. It rejects:

- non-JSON or Markdown-only responses;
- missing, placeholder or mismatched `task_id`;
- invalid verdicts;
- `changed_paths` outside the packet's allowed paths;
- forbidden paths;
- missing or generic evidence;
- invalid confidence values.

Failed gate results are still useful learning evidence for `parallel_debug`,
but they must not advance a task kind toward `llm_only` promotion.
