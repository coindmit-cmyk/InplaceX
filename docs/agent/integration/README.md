# Integration Protection Layer

Integration Protection is the Agent Core safety layer that prevents accepted changes from becoming file-only, orphaned, partially wired or capability-losing updates.

## Core Formula

```text
Mode = how integration runs
Skill = what subject is integrated
Lens = how integration quality is checked
Surface = where the change must be connected
Manifest = proof of integration state
Reality Map = project truth from intent to implementation and evidence
Preservation = proof that existing capabilities were not silently removed
```

## Primary Documents

- `INTEGRATION_PROTECTION_POLICY.md`
- `PRESERVATION_PROTECTION_POLICY.md`
- `SURFACE_MODEL.md`
- `MANIFEST_TEMPLATE.md`
- `LENS_SELECTION_POLICY.md`
- `SKILL_CATALOG.md`
- `COMPLETION_POLICY.md`
- `ORPHAN_DETECTION_POLICY.md`
- `REALITY_MAP_POLICY.md`
- `AUTO_MANUAL_COMPATIBILITY.md`
- `ADC_INTEGRATION_REVIEW.md`
- `PROJECT_DESIGN_REALITY_INTAKE.md`
- `RESULT_INTEGRATION_ENGINE.md`

## Related Catalogs

- Integration lenses: `docs/agent/lenses/Integration/README.md`
- Integration skills: `docs/agent/skills/Integration/README.md`

## Runtime Boundary

The first protection layer is safe and explicit. It adds rules, catalogs, validator support and evidence requirements. It does not start recurring automation, change worker pickup rules, alter locks, bypass Finalizer gates or promote `release/main` by itself.

The deterministic execution-result synthesis path is
`scripts/agent_control/result_integration_engine.py`. It consumes a closed
Parallel Work result set, reuses exact lane accounting, and emits the existing
`result_integration.schema.json` contract without granting Router, launch,
Finalizer, merge or release authority.
