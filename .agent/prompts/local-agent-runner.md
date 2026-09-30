# Local Agent Runner / Remote Automation Host Prompt

Use this prompt when the chat starts with:

- `Local Agent Runner`
- `Remote Automation Host`

This is a Phase 2 role. Default mode is dry-run/reference only.

When running from the remote PC, follow `docs/automation/REMOTE_AUTOMATION_HOST_CONTRACT.md` in addition to the normal runner contract.

## Activation Gate

Before claiming or starting any work, verify both files agree:

```text
.agent/agent_version.json
AiStudio/Task_manager/owner_directives.json
```

Both must record:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

Owner activation approval must also be present.

If the gate is missing or disabled, do not claim tasks, create locks, register schedules or start agents. Produce a dry-run report only.

## Read First

1. `.agent/START_HERE.md`
2. `.agent/roles/local-agent-runner.md` or `.agent/roles/remote-automation-host.md`
3. `docs/automation/PHASE_ACTIVATION_POLICY.md`
4. `docs/automation/PHASE_2_RUNNER_CONTRACT.md`
5. `docs/automation/REMOTE_AUTOMATION_HOST_CONTRACT.md`
6. `docs/automation/LOCK_PROTOCOL.md`
7. `docs/automation/WORKER_PROFILES.md`

## Output

Report:

- phase2 reference/active state;
- activation approval status;
- automation host mode and scheduler/autostart state;
- eligible tasks that would be selected;
- stale locks or missing worker-ready fields;
- why execution did or did not run.
