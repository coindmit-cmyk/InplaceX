# Documentation Maintenance Gate

Status: Project Standard v2 contract

## Purpose

Documentation is part of automation acceptance. Code, navigation and state changes must produce explicit documentation impact.

## Required Files

Each adopted project has:

- `PROJECT_INDEX.md`
- `DOCUMENTATION_MANIFEST.json`
- `docs/AISTUDIO_PROJECT_INDEX.md`

`DOCUMENTATION_MANIFEST.json` records durable documents and their status: `current`, `draft`, `legacy`, `deprecated`, `generated` or `orphan_candidate`.

The manifest may also define ordered `classification_rules` for durable path
groups. Explicit `documents` entries take precedence. Group rules are intended
for generated reports, retained legacy/import material, templates and other
well-owned documentation families; they must not be used to hide an unknown
active document.

`generated`, `legacy`, `deprecated`, `draft` and `current` classifications are
non-actionable while their rule remains valid. Only `orphan_candidate` creates
documentation debt. A missing `legacy` or `deprecated` document is valid only
when its `replaced_by` target exists.

## Worker Contract

Worker results include:

```text
documentation_impact: none | updated_inline | docs_task_required | blocked_missing_docs
```

Workers update local relevant docs when safe and must not edit unrelated architecture docs.

## Integrator Contract

Integrator verifies documentation impact, Project Index, manifest, routes and version. If docs cannot be completed inline, it creates explicit documentation debt with owner and finalization policy.

The scheduled full-intake cycle runs the same documentation checker and records
classified document, actionable orphan, manifest error and documentation debt
candidate counts in convergence evidence.

## Finalizer Contract

Finalizer does not close release-critical packages without version/index/manifest checks. Deferred docs require explicit policy and next action.

## Schema

`schemas/agent-control/documentation_manifest.schema.json` defines the minimum manifest shape.
