# Integration Skill Catalog

## Purpose

Integration skills are reusable procedures for integrating a subject area. They are shared by `AutoIntegrationMode` and `ManualIntegrationMode`.

## Core Rule

```text
Mode = how we execute
Skill = what we integrate
Lens = how we check integration quality
```

## Skills

- `SurfaceDiscoverySkill` — find required integration surfaces.
- `RuleIntegrationSkill` — integrate agent rules and policies.
- `WorkflowIntegrationSkill` — integrate workflows/departments and launch paths.
- `RoleIntegrationSkill` — integrate roles into router/index/prompt surfaces.
- `ModeIntegrationSkill` — integrate modes under owning roles.
- `PromptIntegrationSkill` — connect prompts to role/mode launch paths.
- `SkillIntegrationSkill` — register skills in catalogs and selection policy.
- `LensIntegrationSkill` — register lenses in catalogs and selection policy.
- `CodeIntegrationSkill` — integrate code/runtime/script changes through checks, docs and rollback planning.
- `DocsIntegrationSkill` — connect docs into indexes/maps/navigation.
- `RoutingIntegrationSkill` — integrate triggers, START_HERE, role router and statuses.
- `SchemaIntegrationSkill` — integrate schemas, validators and examples.
- `TemplateIntegrationSkill` — integrate templates into adoption/update flows.
- `ScriptIntegrationSkill` — integrate scripts into catalogs, docs and safety boundaries.
- `VersionChangelogIntegrationSkill` — handle version/changelog/document revision impact.
- `EvidenceValidationSkill` — collect and validate evidence.
- `ReleaseReadinessSkill` — check release/main, stable tags and release gates.
- `RollbackPlanningSkill` — prepare rollback notes.
- `MigrationIntegrationSkill` — handle legacy paths/state and migration notes.
- `AdoptionPackageIntegrationSkill` — check downstream Agent Core adoption package impact.
- `RealityMapUpdateSkill` — update Project Reality Map or create backfill tasks.
- `OrphanDetectionSkill` — detect entities without required routes, indexes, maps or evidence.

## Skill Output Contract

Each skill should output:

```yaml
skill:
input_refs:
findings:
required_updates:
updates_done:
missing_items:
risk:
recommended_status:
next_owner:
```

## Shared Use

Auto mode may use a skill to produce route/handoff/task outputs.

Manual mode may use the same skill to perform safe inline work and continue toward completion.
