# v0.4 Clean Project Launch

`v0.4.0.0` is the first cleanup launch for the AiStudio Agent control layer.
It assumes GitHub is the source of truth, `develop` is the project work base,
and `release/main` is the stable agent source.

## Goals

- migrate projects to `AiStudio/Task_manager` as canonical state;
- keep `docs/plans` only as legacy/archive material;
- archive transient integration, runtime and migration artifacts after each
  finalization or migration;
- keep dirty historical worker branches out of direct integration;
- use local LLM only through explicit policy, queue state and evidence.

## Required Gates

Before applying cleanup or automation to a project:

```text
python scripts/agent_control/github_freshness_guard.py --project-root <project> --base-ref origin/develop --fetch --json
python scripts/agent_control/validate_task_queue_readiness.py --queue <project>/AiStudio/Task_manager/task_queue.json --json
python scripts/agent_control/dispatcher_decision_guard.py --queue <project>/AiStudio/Task_manager/task_queue.json --json
python scripts/agent_control/llm_dispatch_tagger.py --project-root <project> --json
python scripts/agent_control/post_migration_cleanup.py --project-root <project> --json
```

Apply cleanup only when the plan touches transient run artifacts or legacy
machine-state, not product code, durable docs or canonical queue files.

## Local LLM Mode

Local LLM starts in `parallel_debug` only. `llm_only` requires project policy
evidence that meets the promotion threshold, for example `100/100` comparable
successes with zero failures for the same task kind, prompt and model.

The local LLM runner may only take tasks where:

```text
llm_candidate = true
llm_queue_state = ready
```

Default queue concurrency is one active task per `task_kind`.

If the local LLM endpoint is down, keep the task queued and report backend
readiness failure. Do not convert a backend outage into a worker task failure.
