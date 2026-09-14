# Preservation Protection Policy

## Purpose

Preservation Protection prevents silent replacement: a change that appears to add a new feature must not accidentally remove existing behavior, commands, schemas, statuses, docs sections or public entrypoints.

## Core Rule

Integration is additive by default.

Replacement, rewrite, deletion or behavior removal requires explicit task scope and preservation evidence.

## Silent Replacement

A silent replacement is any change where existing capabilities disappear without a stated replacement, migration note, cleanup task or owner-approved removal scope.

Examples:

- existing Python functions/classes disappear;
- CLI flags or commands disappear;
- JSON schema fields disappear;
- task statuses or state names disappear;
- public Markdown sections disappear;
- a file is mostly rewritten while only new behavior was requested.

## Integrator Rule

Integrator must treat capability removals as suspicious unless the task explicitly authorizes replacement, migration, cleanup or removal.

If removals are detected, Integrator must record one of:

- `preservation_ok` — existing capabilities preserved;
- `preservation_warning` — removals are probably safe but need evidence;
- `silent_replacement_detected` — removal has no justification;
- `replacement_scope_required` — task must explicitly authorize rewrite/removal;
- `migration_note_required` — old behavior is intentionally replaced but migration evidence is missing.

## Runtime Boundary

The first enforcement tool is read-only. It reports preservation findings and does not edit files, queues, maps, branches or PRs.

For integration into `develop`, compare the untouched current base with the candidate ref before changing the target checkout:

```text
python scripts/agent_control/capability_preservation_check.py --base-ref origin/develop --head-ref <candidate-ref> --all-changed --json
```

New tests do not replace this comparison. Both preservation evidence and candidate test evidence are required unless the task explicitly authorizes the removed behavior.
