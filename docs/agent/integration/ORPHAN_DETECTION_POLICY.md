# Orphan Entity Detection Policy

## Purpose

Detect files or entities that exist but are not connected to the required route, index, map, task, evidence or finalization surfaces.

## Orphan Types

```text
orphan_role
orphan_workflow
orphan_mode
orphan_prompt
orphan_skill
orphan_lens
orphan_policy
orphan_script
orphan_schema
orphan_template
orphan_doc
orphan_task
orphan_implementation
orphan_integration_surface
```

## Detection Rules

- Artifact Discovery Layer under `docs/agent/discovery/` is the preferred
  scanner/router evidence source for repository-wide orphan discovery.
- Role: role file exists but role router or role index does not reference it.
- Workflow: workflow docs exist but no owning role, prompt, launch path, boundary or index link exists.
- Mode: mode exists but no owning role or invocation/completion rule exists.
- Prompt: prompt exists but no role/mode launch path references it.
- Skill: skill exists but catalog or selection policy does not reference it.
- Lens: lens exists but catalog or selection policy does not reference it.
- Policy: policy exists but no enforcement owner or index route references it.
- Script: user/agent-facing script exists but catalog/docs/check references are missing.
- Schema/template: examples, validators, adoption docs or version notes are missing.
- Documentation: doc exists but no index, package docs, workflow, map or active task references it.
- Implementation: code exists without task, map, docs, tests/evidence or integration status.

## Outcomes

```text
no_orphans
orphans_found_non_blocking
orphans_found_blocking
integration_incomplete
reality_map_backfill_required
```

## Blocking Orphans

A blocking orphan is any new entity missing required discovery or map coverage, or any entity required for current safety/routing/release/source-of-truth resolution.

## Non-blocking Orphans

A non-blocking orphan is legacy context not touched by current work and not safety-critical. It creates a backfill or cleanup task.

## Artifact Discovery Consumer

Artifact Discovery findings with `category` values such as
`unmapped_artifact`, `missing_index_link`, `missing_integration_surface`,
`cleanup_candidate` or `lost_documentation` should be treated as orphan
evidence.

Current-scope orphan findings block integration until resolved, routed or
explicitly waived. Legacy unrelated orphan findings create Dispatcher,
Integrator, Doctor or ProjectMapPlanner routes and do not block by default.

## Report Format

```yaml
orphan_detection:
  checked: true
  orphans:
    - type:
      entity:
      path:
      blocking:
      reason:
      required_fix:
      backfill_task:
  final_status:
```
