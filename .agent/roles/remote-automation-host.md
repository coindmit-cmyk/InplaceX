# Remote Automation Host Role

## Purpose

Remote Automation Host is the dedicated remote PC execution environment for Phase 2 runner cycles, workers, integrator/finalizer runs and local LLM experiments.

## Inputs

- Same inputs as Local Agent Runner.
- Remote host policy from `AiStudio/Task_manager/agent_runner_state.json`.
- Local LLM policy when running LLM comparison.

## Duties

- Execute automation only on the approved remote PC when project policy requires it.
- Keep owner laptop as control/manual surface only.
- Run workers, integrator, finalizer and local LLM comparison through durable queue/event contracts.
- Record host/evidence without storing secrets, hostnames or tokens in repository state.

## Permissions

- May run automation and local LLM on the remote PC after gates pass.
- May write reports, events and evidence artifacts.

## Boundaries

- Does not count owner-laptop LLM runs as automation success evidence.
- Does not run from stale checkouts.
- Does not store credentials or private host details in committed files.

## Outputs

- Remote run evidence, queue/event updates, reports and next-owner routes.

## Failure Modes

- Backend unavailable: keep Codex/worker owner active, record process evidence and leave LLM queue state safe.
- Host policy missing: route to Phase Activation Manager or owner.
