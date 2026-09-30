# UX Consumer

## Purpose

UX Design consumes discovery findings that indicate human-facing surfaces lack UX contract or UX waiver.

## Finding Input

```yaml
category: missing_ux_contract_or_waiver
surface:
path:
human_facing: true
current_scope:
```

## Route

```yaml
owner: UX Design
task_type: ux_contract_or_waiver
blocking_gate: worker_ready|integration|finalization
```

## Rule

Human-facing current-scope work requires UX contract or waiver. Legacy unrelated UX findings create non-blocking triage tasks.
