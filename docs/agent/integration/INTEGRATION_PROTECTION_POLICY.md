# Integration Protection Policy

## Purpose

Integration Protection makes integration a first-class lifecycle. It ensures accepted changes are discoverable, routed, documented, mapped, evidenced and finalized before they can be called complete.

## Policy

Any accepted change that introduces, modifies or removes agent rules, roles, workflows, modes, prompts, skills, lenses, policies, scripts, schemas, templates, project modules or runtime behavior is not complete until the required integration surfaces are updated or a blocking status is recorded.

## Integrator Modes

Integrator owns two execution modes:

- `AutoIntegrationMode` — automation-driven integration from queues, branches, pull requests, worker evidence, reports and events.
- `ManualIntegrationMode` — current-chat integration where the operator expects the current manual session to move the change to a concrete state.

Code, rules, docs, workflows and routing are not separate modes. They are integration subjects handled through shared skills.

## Required Lifecycle

```text
classify change
  -> select mode
  -> select skills
  -> select lenses
  -> discover surfaces
  -> inspect current project reality
  -> build delta
  -> update or plan required surfaces
  -> update Project Reality Map for new entities
  -> create backfill tasks for non-blocking legacy gaps
  -> validate evidence
  -> write manifest
  -> assign final status and next owner
```

## New Work Rule

New entities must be mapped and integrated immediately.

```text
new entity + missing required surface = integration_incomplete
new entity + missing map entry = integration_incomplete
new entity + no manifest/evidence = integration_incomplete
```

## Legacy Backfill Rule

Legacy missing map coverage is non-blocking by default. It creates or proposes a `reality_map_backfill` task unless the gap affects current safety, routing, automation, release/adoption or source-of-truth resolution.

## Manual Integration Rule

ManualIntegrationMode may perform safe inline role reasoning for Architect, Dispatcher, Doctor, Project Design and Finalizer-readiness checks. It must record the inline invocation and result. It must not bypass owner, release, secrets, destructive-cleanup or force-push gates.

ManualIntegrationMode may open a draft pull request when checks/evidence are present and no protected gate is bypassed. Draft PRs are considered dirty evidence and must not be consumed directly by automation as final integration.

## Auto Integration Rule

AutoIntegrationMode remains task/event/handoff based. It may route missing work to another role/task and continue other integration candidates. This policy does not change worker pickup, queue semantics, locks, Finalizer merge behavior or runner scheduling unless a separate explicit implementation task changes them.

## Completion Rule

Files added or edited is not `done`. Completion requires updated surfaces, map decision, evidence, version/changelog review, rollback note, next owner and resolved gates.

## Orphan Rule

If a role, workflow, mode, prompt, skill, lens, script, schema, template or policy exists without the required discovery/routing/index/map/evidence links, it is an orphan until fixed.

## ADC and Project Design Rule

Project Design and ADC review proposed changes against current project reality and delta. ADC Pass 1 generates owner questions. ADC Pass 2 happens after owner answers or explicit safe-default waiver.

## Evidence Rule

Every non-trivial integration writes an Integration Manifest under `docs/reports/integration/` and links or copies it to the design/session context when applicable.
