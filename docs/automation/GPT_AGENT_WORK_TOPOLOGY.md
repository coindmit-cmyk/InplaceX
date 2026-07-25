# GPT Agent Work Topology

## Current Goal

Reusable coordination with Phase 2 active distribution and remote host execution:

```text
Owner -> GPT Director -> GPT Architect -> GPT/Codex Dispatcher -> Auto Make Tasks -> Remote Automation Host -> Auto Workers -> Auto Integrator -> Auto Finalizer -> needs_human only for blocked/risky/ambiguous work
```

Phase 2 active metadata is distributed through `Agent Update Manager`; the default execution host is the remote PC, while runner autostart remains disabled until a project explicitly starts or schedules execution.

## Seven-layer Model

1. Director Chat: product direction, base priorities, acceptance framing and durable owner decisions.
2. Architect Chat: architecture, scope, task proposals and durable decisions.
3. GPT Dispatcher / Codex Dispatcher / Auto Make Tasks: structured task packets and queue/lock updates.
4. Auto Workers: execute task packets through branch, checks, commit and draft PR.
5. Auto Integrator: assembles ready PRs/branches into integration order and reports conflicts.
6. Auto Finalizer: returns verified safe Integrator packages to `develop` when gates pass, then closes accepted results.
7. Human-needed Chat: owner decisions, manual QA, risky acceptance, failed gates and production/release merge authority.
8. Agent Update Manager: prepares controlled upstream update branches, reports and PR bodies.
9. Phase Activation Manager: records owner-approved activation and selected worker profiles.
10. Remote Automation Host / Local Agent Runner: Phase 2 execution role; dry-run only unless activation gates pass.

## Default Worker Routing

```text
S  -> Auto Worker 5.3 mini; Auto Worker 5.3 may take after M pool is empty
M  -> Auto Worker 5.3
L  -> Auto Worker 5.5; Auto Worker 5.5max may take only critically important L
XL -> Auto Worker 5.5max only after Architect/Dispatcher marks the packet worker-ready
```

Non-ready `XL` work remains an architecture container until it has explicit scope, dependencies, allowed paths, forbidden paths, acceptance criteria and checks.
Dispatcher should split routine L-looking work into S/M packets when safe so 5.3 capacity is used first.

## Integration And Finalization

Auto Integrator and Auto Finalizer are governance layers, not worker complexity levels.

```text
worker PRs -> Auto Integrator -> Auto Finalizer gate -> develop/stable task state; failed gates -> needs_human
```

Auto Integrator may report `integration_ready`, `integration_blocked`, merge order, stale branches and missing checks.

Auto Finalizer may merge safe Integrator-approved packages into `develop` when the finalizer merge gate passes. It may record `owner_approved` or `done` only when approval, merge evidence or accepted-state evidence supports that exact status.

## Required Contracts

```text
DISPATCHER_TASK_PACKET.md
BRANCH_COMMIT_INTEGRATION_PROTOCOL.md
AGENT_UPDATE_PROTOCOL.md
AGENT_UPDATE_FLOW.md
AGENT_PHASE2_FULL_ARCHITECTURE.md
PHASE_ACTIVATION_POLICY.md
PHASE2_ACTIVATION_FLOW.md
PHASE_2_RUNNER_CONTRACT.md
REMOTE_AUTOMATION_HOST_CONTRACT.md
INTEGRATION_FINALIZATION_PROTOCOL.md
INTEGRATOR_FINALIZER_PACKAGE_FLOW_CLARIFICATION.md
LOCK_PROTOCOL.md
TASK_TRACEABILITY_CONTRACT.md
LOCAL_CODEX_DESKTOP_RUNBOOK.md
WORKER_PROFILES.md
MANUAL_CHANGES_PROTECTION.md
HUMAN_NEEDED_QUEUE.md
PROJECT_TEMPLATE_ADOPTION.md
AI_PROJECT_AGENT_REPOSITORY.md
```

## Phase 2 Gate

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

When `phase2_active` is true, runner/scheduler execution is allowed by policy. The default host is the remote PC, but execution is still not started automatically.

## Duplicate Prevention

Check Issues, PRs, recent commits, changed paths, manual PRs, task queue, locks and task pages before creating or assigning work.
