# Technical Debt

## Known Gaps

- Android still owns state-holder orchestration, route adaptation, and timer
  lifecycle around the shared pure engine
- the bounded evidence solver intentionally stops exhaustive enumeration above
  its search budget and then exposes only sound local deductions
- active online duel sessions, waiting matchmaking tickets and retained private
  invitations are PostgreSQL-recoverable when the database and managed AES
  state key are configured; public matchmaking and bot fallback now use
  database row claims across active instances, while cross-instance private
  invite acceptance and long-lived duel-session ownership remain later scaling
  boundaries
- strings are only partially centralized
- platform config is still static code, not external assets/resources

## Why This Is Acceptable Now

These gaps are explicit boundaries. Core behavior is accepted through
`Core Engine Acceptance.md`; persistence, provider integration, and visual
completion have separate delivery gates.
