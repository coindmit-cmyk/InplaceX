# Technical Debt

## Known Gaps

- Android still owns state-holder orchestration, route adaptation, and timer
  lifecycle around the shared pure engine
- the bounded evidence solver intentionally stops exhaustive enumeration above
  its search budget and then exposes only sound local deductions
- online authoritative sessions are currently in-memory; durable session
  recovery is a later backend stage, not part of local engine correctness
- strings are only partially centralized
- platform config is still static code, not external assets/resources

## Why This Is Acceptable Now

These gaps are explicit boundaries. Core behavior is accepted through
`Core Engine Acceptance.md`; persistence, provider integration, and visual
completion have separate delivery gates.
