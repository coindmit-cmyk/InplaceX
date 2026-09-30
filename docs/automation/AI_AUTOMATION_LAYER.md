# AI Automation Layer

Reusable local coordination layer for AI-assisted projects.

Current active workflow:

```text
GPT Director -> GPT Architect -> GPT/Codex Dispatcher -> Auto Make Tasks -> Remote Automation Host -> Auto Workers -> Auto Integrator -> Auto Finalizer -> needs_human only for blocked/risky/ambiguous work
Owner module chat -> Module Companion -> human_working / needs_replan_after_manual_work / normal final flow
```

Phase 2 active package:

```text
Agent Update Manager -> controlled project update branch/PR -> Phase 2 active docs/templates -> runner autostart disabled
```

Default execution host:

```text
remote PC -> Phase 2 runner cycle -> shared queue/locks -> worker launch -> draft PR/evidence
```

Required reference gate:

```json
{
  "phase2_reference": true,
  "phase2_active": true
}
```

Core rule:

```text
role -> fresh GitHub/docs context -> task packet -> lock -> branch -> commit -> PR -> integration -> finalization
```

Branch, commit and final assembly rules live in `BRANCH_COMMIT_INTEGRATION_PROTOCOL.md`.
Integration and finalization governance lives in `INTEGRATION_FINALIZATION_PROTOCOL.md`.
Integrator/finalizer package handoff rules live in `INTEGRATOR_FINALIZER_PACKAGE_FLOW_CLARIFICATION.md`.
Agent repository updates live in `AGENT_UPDATE_PROTOCOL.md`.
Controlled update PR flow lives in `AGENT_UPDATE_FLOW.md`.
Phase 2 activation rules live in `PHASE_ACTIVATION_POLICY.md`.
Remote automation host rules live in `REMOTE_AUTOMATION_HOST_CONTRACT.md`.

Auto Finalizer target policy:

```json
{
  "finalizer_mode": "auto_merge_to_develop",
  "finalizer_target_branch": "develop",
  "auto_merge_allowed_sources": ["auto-integrator"],
  "needs_human_on_gate_failure": true,
  "require_expected_head_sha": true,
  "allow_documented_check_blockers": false
}
```

## Default Auto Worker Matrix

| Worker | Executes |
| --- | --- |
| `Auto Worker 5.3 mini` | `S` only, model `5.3`, medium reasoning effort |
| `Auto Worker 5.3` | `M` then `S`, model `5.3`, very high reasoning effort |
| `Auto Worker 5.5` | `L` only by default, model `5.5`, medium reasoning effort |
| `Auto Worker 5.5max` | worker-ready `XL` then critically important `L`, model `5.5`, very high reasoning effort |

All workers use the same shared queue and lock protocol. They do not own separate task lists.
5.3-family workers are the default capacity lane for routine S/M work; 5.5-family limits are reserved for L/XL or work that cannot be split safely.
Workers must not claim `human_working` or `needs_replan_after_manual_work` tasks.

Phase 2 worker profile templates ship enabled for the remote automation host, with `autostart_enabled = false`. Projects must still explicitly start runners or register schedules.

## Governance Layers

| Layer | Main job |
| --- | --- |
| `Auto Integrator` | Assemble ready branches/PRs, detect merge order, conflicts, stale branches and missing checks. |
| `Auto Finalizer` | Auto-merge verified safe Integrator packages into `develop` when the merge gate passes; otherwise route blocked, risky or ambiguous work to `needs_human`; then synchronize statuses, locks, docs, changelog and final reports. |
| `Module Companion` | Record owner-led module work, protect active manual tasks from worker pickup and route stale partial work to Dispatcher/Architect. |
