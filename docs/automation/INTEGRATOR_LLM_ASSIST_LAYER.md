# Integrator LLM Assist Layer

The Integrator LLM Assist layer is an advisory-only helper for Auto Integrator.
It uses compact, redacted repository state and returns validated JSON advice.

The layer never owns durable state transitions:

```text
deterministic scripts decide / validate / write durable state
local LLM explains / suggests / drafts reasons only
```

## Pipeline

```text
integrator_preflight.json
-> pr_readiness_report.json / integration_batch.json
-> integrator_llm_context_builder.py
-> integrator_llm_assistant.py
-> validate_integrator_llm_advice.py
-> summarize_integrator_llm_advice.py
-> Auto Integrator report/handoff as advisory evidence only
```

## First Local Route

```text
LOCAL_INTEGRATOR_ASSISTANT = Qwen2.5-Coder-7B-Instruct Q4_K_M
```

The runtime must be OpenAI-compatible and accessed through
`scripts/agent_control/local_llm_adapter.py`.

## Allowed Advice

- explain blockers;
- explain changed-path conflicts;
- suggest small safe batch groups;
- draft `rejection_detail` wording;
- explain why `excluded_from_package` items must not be finalized;
- explain handoff validator warnings.

## Forbidden Actions

The local LLM must not:

- merge, push or rebase branches;
- delete branches;
- close PRs;
- release locks;
- mark tasks `done` or `owner_approved`;
- edit `task_queue.json`, locks, events, branches, PRs or handoff files;
- directly decide Finalizer readiness.

Advice containing forbidden actions, unknown branches, high-risk ready
suggestions or a wrong context hash is invalid and must not be used.

## Commands

Build context:

```powershell
python scripts\agent_control\integrator_llm_context_builder.py `
  --project-root D:\Work\Project
```

Dry-run advice without calling a model:

```powershell
python scripts\agent_control\integrator_llm_assistant.py `
  --context D:\Work\Project\docs\plans\integrator_llm_context.json `
  --dry-run
```

Call local OpenAI-compatible model:

```powershell
python scripts\agent_control\integrator_llm_assistant.py `
  --context D:\Work\Project\docs\plans\integrator_llm_context.json `
  --base-url http://127.0.0.1:11434/v1 `
  --model Qwen2.5-Coder-7B-Instruct-Q4_K_M
```

Validate existing advice:

```powershell
python scripts\agent_control\validate_integrator_llm_advice.py `
  --context D:\Work\Project\docs\plans\integrator_llm_context.json `
  --advice D:\Work\Project\docs\plans\integrator_llm_advice.json
```

Render Markdown:

```powershell
python scripts\agent_control\summarize_integrator_llm_advice.py `
  --advice D:\Work\Project\docs\plans\integrator_llm_advice.json `
  --output D:\Work\Project\docs\plans\reports\INTEGRATOR_LLM_ADVICE.md
```
