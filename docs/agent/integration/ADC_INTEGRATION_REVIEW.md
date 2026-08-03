# ADC Integration Review

## Purpose

ADC reviews proposed changes against current project reality and delta, not against an empty project.

## Input Contract

```yaml
current_reality:
  docs:
  architecture:
  implementation:
  backlog:
  task_queue:
  known_gaps:
  recent_commits:
  integration_state:
proposed_change:
  summary:
  owner_intent:
  affected_entities:
  expected_outputs:
delta:
  new_entities:
  changed_entities:
  removed_entities:
  affected_surfaces:
  risk_level:
```

## Required Passes

- Product value and owner-intent fit.
- Requirements consistency.
- Architecture fit.
- Integration fit.
- Automation safety.
- Reality map impact.

## Integration Fit Questions

- What already exists that this change touches?
- Which surfaces are affected?
- Does it create orphans?
- Does it require Project Reality Map updates?
- Does it require legacy backfill tasks?
- Does it affect automation behavior?
- Does it require release/adoption review?
- Should execution use AutoIntegrationMode or ManualIntegrationMode?

## Verdicts

```text
approved
needs_clarification
needs_architecture
approved_with_integration_requirements
needs_integration_plan
blocked_by_reality_gap
```

## Output Contract

```yaml
verdict:
confidence:
reality_findings:
integration_findings:
automation_safety_findings:
required_updates:
blocking_gaps:
non_blocking_gaps:
recommended_next_mode:
recommended_skills:
recommended_lenses:
```

## Boundary

ADC may identify integration requirements. It does not perform implementation and does not bypass Project Design owner-question gates.
