# Auto / Manual Integration Compatibility

## Purpose

Manual Integration improves current-chat completion without breaking existing automation.

## Principle

```text
Auto and Manual share skills, lenses, surfaces, manifests and completion policy.
They differ in execution mode.
```

## AutoIntegrationMode

Auto mode works through queues, branches, pull requests, worker evidence, integration events, reports, handoffs and finalizer gates.

When Auto mode needs another role, it may create a route, task or handoff and continue other candidates.

## ManualIntegrationMode

Manual mode works in the current chat/Codex session.

When Manual mode needs another role's reasoning, it should perform it inline when safe and record it in the manifest.

Manual mode may open a draft PR when evidence is ready and no protected gate is bypassed. Draft PRs remain dirty until explicitly promoted.

## Non-breaking Rules

Manual mode must not change these behaviors unless explicitly tasked:

- worker pickup rules;
- queue status semantics;
- lock protocol;
- auto integrator candidate selection;
- finalizer merge gate;
- release/main promotion;
- recurring runner scheduling;
- task manager source-of-truth rules.

## Shared Components Allowed

Manual and Auto modes may share Integration skills, Integration lenses, Integration surface model, Integration manifest format, completion policy, orphan detection policy and Project Reality Map policy.

## Runtime Integration Strategy

Safe runtime integration starts with read-only validation and explicit gates:

1. docs/rules/catalogs;
2. schemas/templates;
3. read-only validator scripts;
4. CI/worker checks by explicit task;
5. runner behavior changes only after separate owner-approved implementation.

## Compatibility Checklist

```yaml
auto_mode_affected:
runner_behavior_changed:
queue_semantics_changed:
lock_behavior_changed:
finalizer_behavior_changed:
release_flow_changed:
adoption_flow_changed:
notes:
```
