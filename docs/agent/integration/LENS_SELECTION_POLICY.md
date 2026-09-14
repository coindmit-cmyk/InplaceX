# Integration Lens Selection Policy

## Purpose

Integrator and other review roles select Integration lenses by integration risk and affected surfaces. There is no fixed lens count.

## Selection Inputs

```yaml
execution_mode:
changed_paths:
change_type:
affected_surfaces:
risk_level:
release_or_adoption_impact:
manual_operator_impact:
automation_impact:
legacy_map_gap:
```

## Required Base Lenses

Every non-trivial integration uses:

- `EvidenceGuard`
- `CompletionJudge`

Add `BreakageGuard` when runtime, automation, routing, schema, script or release behavior might be affected.

## Default Sets

### Manual integration

- `OperatorPathGuard`
- `OwnershipGuard`
- `TraceabilityGuard`
- `EvidenceGuard`
- `CompletionJudge`

### Rule / workflow / role / mode / prompt / skill / lens integration

- `DiscoveryGuard`
- `EntryPointGuard`
- `CompatibilityGuard`
- `OperatorPathGuard`
- `DocumentationGuard`
- `VersioningGuard`
- `OrphanEntityGuard`
- `CompletionJudge`

### Code / script / schema integration

- `BreakageGuard`
- `DependencyGuard`
- `TestCoverageGuard`
- `EvidenceGuard`
- `RollbackGuard`
- `AutomationGuard`
- `CompletionJudge`

### Routing / START_HERE / index integration

- `DiscoveryGuard`
- `EntryPointGuard`
- `CompatibilityGuard`
- `OperatorPathGuard`
- `AutomationGuard`
- `ReleaseGuard`
- `CompletionJudge`

### Release / adoption integration

- `ReleaseGuard`
- `AdoptionGuard`
- `MigrationGuard`
- `VersioningGuard`
- `PermissionGuard`
- `EvidenceGuard`
- `CompletionJudge`

### Reality map migration / backfill

- `RealityMapGuard`
- `TraceabilityGuard`
- `OrphanEntityGuard`
- `OwnershipGuard`
- `CompletionJudge`

## Escalation

Use the expanded set when:

- more than one role or workflow is affected;
- automation behavior might change;
- release/adoption impact exists;
- source-of-truth conflict exists;
- owner approval or protected operation is involved;
- legacy map gaps affect current safety.

## Authority Boundary

Lenses report findings. The current role/mode decides action and must respect owner, release and security gates.
