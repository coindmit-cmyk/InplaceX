# Project Design Reality Intake

## Purpose

Project Design must discuss and design from current project state, not from an empty slate.

## Rule

Before clarification questions, product design or ADC review, Project Design produces Current Project Reality Summary.

## Required Inputs

- existing docs;
- existing architecture;
- existing implementation paths;
- existing roles, workflows, modes, prompts, skills and lenses;
- current backlog/task queue;
- known gaps;
- recent commits/PRs when useful;
- current integration state;
- Project Reality Map / PROJECT_MAP coverage when available.

## Pipeline Shape

```text
1. Department Rules Bootstrap
2A. Owner Raw Input and Discussion
2B. Project Reality Review
3. Context Merge Snapshot
4. Proposed Change Draft
5. Delta Draft
6. ADC Pass 1
7. ADC-Derived Questions and Owner Gate
8. ADC Pass 2
9. Final design package stages
```

## Delta Map

```yaml
delta:
  new_entities:
  changed_entities:
  removed_entities:
  affected_surfaces:
  required_integration:
  legacy_backfill_tasks:
  blocking_gaps:
  non_blocking_gaps:
```

## Discussion Rule

Clarification questions must be based on current reality and ADC findings, not only on owner intent.

## Map Rule

New outputs created by Project Design must be mapped immediately. Existing unmapped legacy components create backfill tasks unless they block current safety.
