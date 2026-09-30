# Artifact Discovery Policy

## Purpose

Artifact Discovery finds repository artifacts that may be lost, unmapped, stale, duplicated, risky, unindexed or disconnected from the current project truth.

## Scope

Artifact Discovery covers all projects and all significant artifact types:

- documentation;
- product modules;
- automation scripts;
- agent rules;
- workflows, roles, modes, prompts, skills and lenses;
- schemas and templates;
- task state;
- reports;
- UX contracts and waivers;
- integration manifests;
- dashboards and command surfaces;
- legacy migration evidence;
- cleanup candidates;
- possible secret or security-risk files.

## Mutability

Scanner is read-only.

Router is dry-run by default. With `--apply`, it may write only Dispatcher-owned task/route records according to this policy.

## New Work Rule

New significant artifacts must not remain unmapped or unintegrated.

```text
new significant artifact + no map/index/surface/discovery disposition = blocking finding
```

## Legacy Rule

Legacy unrelated artifacts do not block by default. They create backfill, triage or cleanup candidate routes.

## Possible Secret Rule

Possible secret findings are always blocking and route to Human/Doctor/security review, with confidence recorded.

## Cleanup Rule

Cleanup findings never auto-delete. They create review tasks or reports with evidence and safe deletion conditions.

## Project Map Rule

Project Map Enforcement consumes discovery findings for missing map coverage. Dispatcher creates `reality_map_backfill` tasks; ProjectMapPlanner updates `PROJECT_MAP.json` and `PROJECT_MAP.md`.
