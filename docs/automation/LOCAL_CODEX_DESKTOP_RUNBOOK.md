# Codex Runner Runbook

## Active Runner Workflow

```text
remote PC -> Codex Desktop Auto Worker -> role branch -> checks -> commit -> push -> draft PR
```

Owner laptop Codex Desktop is a control and manual-work surface only. Routine Phase 2 automation, worker launches, local LLM comparison runs, Auto Integrator and Auto Finalizer execution must run from the remote automation host once the project host policy is active.

Laptop-side work is allowed for giving tasks, reading GitHub state, reviewing reports, making explicit manual edits and running lightweight checks. It must not be recorded as an autonomous worker or local LLM success.

Branch, commit and final assembly rules live in `BRANCH_COMMIT_INTEGRATION_PROTOCOL.md`.
Integration and finalization rules live in `INTEGRATION_FINALIZATION_PROTOCOL.md`.
Phase 2 activation rules live in `PHASE_ACTIVATION_POLICY.md`.
Remote host rules live in `REMOTE_AUTOMATION_HOST_CONTRACT.md`.

## Startup

1. Read `.agent/START_HERE.md`.
2. Identify worker profile: `Auto Worker 5.3 mini`, `Auto Worker 5.3`, `Auto Worker 5.5` or `Auto Worker 5.5max`.
3. Run the GitHub freshness guard:

```text
python scripts/agent_control/github_freshness_guard.py --project-root <project> --base-ref origin/develop --fetch --json
```

4. If the guard reports `local_checkout_behind_remote`, stop as `sync_blocked` and update the checkout or create a fresh worktree before reading local files as source of truth.
5. Read fresh agent rules.
6. Refresh GitHub state.
7. Read queue and locks.
8. Confirm task packet, linked architecture, context docs and dependent files are explicit enough.
9. Confirm status is `planned` or `needs_stronger_agent` and lock is `free`.
10. Set lock before editing.

## Default Selection Order

```text
Auto Worker 5.3 mini -> S only
Auto Worker 5.3      -> M, then S
Auto Worker 5.5      -> L only by default
Auto Worker 5.5max   -> worker-ready XL, then critically important L
```

Use 5.3-family as the high-capacity lane. Routine docs, tests, contract,
verification and focused implementation work should be S/M unless it truly
needs stronger-model handling.

Default branch lanes:

```text
remote/<machine-id>/auto-worker-5-3-mini/<TASK-ID>-short-name
remote/<machine-id>/auto-worker-5-3/<TASK-ID>-short-name
remote/<machine-id>/auto-worker-5-5/<TASK-ID>-short-name
remote/<machine-id>/auto-worker-5-5max/<TASK-ID>-short-name
integrator/<BATCH-ID>-short-name
finalizer/<BATCH-ID>-short-name
```

5.5-family workers must not claim a new task when less than 15% model limit remains. 5.3-family workers may continue until exhausted.

## Governance Startup

Use `Auto Integrator` after worker/director/architect PRs exist and need assembly into a safe integration order.

Use `Auto Finalizer` after Auto Integrator provides a validated safe package, merge evidence exists, or owner approval is required for blocked/risky/ambiguous items. For safe packages, Finalizer may return the package to `develop` when the merge gate passes, then synchronize task status, locks, docs, changelog and final reports.

## Phase 2 Runner Gate

Phase 2 active files may be present while runner autostart remains disabled.

Do not register schedules, claim tasks or start Local Agent Runner / Remote Automation Host execution unless these files agree:

```text
.agent/agent_version.json
AiStudio/Task_manager/owner_directives.json
AiStudio/Task_manager/agent_runner_state.json
AiStudio/Task_manager/agent_activity_state.json
```

Required active state:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

Owner activation approval and dry-run evidence must also be recorded.

If `AiStudio/Task_manager/agent_runner_state.json` records `automation_host.kind = remote_pc`, follow `REMOTE_AUTOMATION_HOST_CONTRACT.md`. If the current machine is the owner laptop, stop before worker/LLM automation and hand the run to the remote PC.

## Stop And Report

Stop when owner decision, protected path, manual conflict, failing checks, missing tool, disabled Phase 2 gate, missing remote host policy, laptop-side automation attempt, server-runner requirement, or model limit threshold appears.
