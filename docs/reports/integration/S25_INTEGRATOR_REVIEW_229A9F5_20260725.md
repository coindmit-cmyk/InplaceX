# S25 integrator review: 229a9f5

Verdict: `needs_worker_fix`. Do not integrate this result.

## Useful preserved intent

- Immutable command receipt storage is a useful basis.
- The receipt key includes session, actor, and client command id.
- State, event, and receipt writes use one transaction helper.
- The migration rollback test now uses a fresh migration version.
- Backend unit tests and `git diff --check` pass.

## Blocking findings

1. Snapshot, event, result, event type, actor, and fingerprint remain arbitrary
   caller-provided strings. There is no typed/closed public schema.
2. No JSON parsing, forbidden-data validation, canonical serialization, or
   64 KiB UTF-8 limit exists.
3. V3 copies legacy `config_json` into `snapshot_json`, presenting a fabricated
   and potentially seed-bearing value as authoritative public state instead of
   a non-durable/replay-gap state.
4. Legacy `appendCommand` remains active, leaving two divergent write models.
5. Reconnect reads the latest snapshot and all events after the client cursor,
   including events older than that snapshot. It has no maximum, captured upper
   cursor, repeatable-read transaction, retention, or functional replay gap.
6. Fingerprints are trusted caller strings rather than server-derived canonical
   SHA-256 values.
7. There are no PostgreSQL/Testcontainers migration tests, concurrency tests,
   partial-write rollback tests, reconnect race tests, security corpus, or
   oversized-frame tests.
8. The Worker report claims `passed` after only backend unit tests and
   `git diff --check`; the mandatory repair specification was not executed.

## Required next result

- Implement the complete `S25_REPAIR_SPEC_20260725.md` contract in smaller
  reviewable slices.
- First deliver typed public state/event/result codecs and their security/size
  corpus.
- Then deliver corrected V3, one write path, immutable actor-scoped receipts,
  bounded consistent reconnect, PostgreSQL migration, and concurrency tests.
- Do not claim full active-match restart recovery without encrypted secret
  persistence and aggregate rehydration.

