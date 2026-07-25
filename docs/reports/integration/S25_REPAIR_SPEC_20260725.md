# S25 repair specification: durable session persistence and reconnect

## Authority and preserved evidence

- Canonical base is the current `origin/develop`.
- Preserve the rejected Worker result at commit
  `c2f3146b9397bfab9a8ee049bedd94d8bc16ee1e` and its two Worker reports.
- Do not integrate that commit wholesale. Reuse only reviewed intent and replace
  its candidate `V3` migration in the fresh Worker branch.
- Read `InplaceX-docs/Backend/Online Contracts.md`,
  `InplaceX-docs/Backend/Ktor Architecture.md`, and the rejected Worker reports
  before editing.
- Configure the Worker environment before running Gradle:
  `JAVA_HOME=/home/main/.local/jdk21`; the Java 11 toolchain is available at
  `/home/main/.local/jdk11`.

## Blocking defects found by independent review

1. Candidate `V3` makes `duel_events.event_seq` non-null while the legacy
   `appendCommand` path still inserts events without `event_seq`.
2. Legacy and new arbitrary JSON payloads can expose secrets, tokens, provider
   payloads, guesses, or raw request data during reconnect.
3. A denylist regex is not a public contract boundary and is bypassable by
   alternate field names, escaped keys, invalid JSON, and nested data.
4. Reconnect can combine a latest snapshot with older events and reads snapshot
   and events without one consistent transaction/upper bound.
5. A duplicate command can return its old command version with the current
   latest snapshot instead of the original command result.
6. The migration rollback test reuses an already-applied migration version and
   therefore does not execute the intended failure.
7. Existing sessions are migrated to an empty snapshot, snapshot/event frame
   size is not bounded, player-scoped idempotency is absent, fingerprint input
   is not validated, and retention/replay-gap behavior is unspecified.

## Required implementation contract

### Public state boundary

- Do not persist or replay arbitrary caller-provided JSON as a public response.
- Parse and validate public snapshot and event data through typed DTOs or a
  closed event schema before storage. Serialize canonical JSON only after
  validation.
- Reject invalid JSON, unknown event types/fields, oversized frames, and all
  values forbidden by Online Contracts, including nested or escaped variants
  of secrets, hashes, ciphertext, seeds, key material, access/refresh/ID
  tokens, purchase/integrity/provider payloads, guesses, and raw request bodies.
- Enforce the 64 KiB maximum frame size at the persistence boundary for both
  snapshots and events.
- Persist viewer-neutral public session state. A REST/WebSocket DuelSnapshot is
  viewer-dependent and must be projected only after authorization.
- This task must not store plaintext secrets. If current encrypted secret state
  is insufficient to rehydrate an active match after process restart, document
  the limitation and route the missing encrypted-secret rehydration as a
  separate blocking design task instead of claiming full match recovery.

### Idempotency and atomic commit

- Use one atomic transaction for command receipt, optimistic session revision,
  public state, and exactly one ordered event.
- Scope deduplication by session, authenticated player/actor, and client
  command id.
- Derive a non-empty canonical request fingerprint server-side from validated
  command content; do not trust an arbitrary caller-supplied fingerprint.
- Store enough immutable receipt data to return the original result for a
  duplicate command even after later commands advance the session.
- A repeated idempotency key with different canonical content must fail with an
  explicit idempotency-key-reused result and must not mutate state.
- Remove the legacy write path or make it delegate to the same atomic durable
  implementation. There must be no insert path that omits event sequence or
  bypasses public-schema validation.

### Migration and reconnect

- Replace the unintegrated candidate `V3`; do not add a compensating `V4`.
- Migrate existing event sequence deterministically and preserve uniqueness.
- Do not represent legacy sessions as authoritative with a fabricated empty
  snapshot. Mark them as requiring a safe replay gap/resync, or migrate only
  when a valid public state can be reconstructed.
- Define a bounded retention window and the earliest retained event sequence.
- Reconnect must return exactly one consistent mode:
  - a contiguous replay suffix after the acknowledged cursor; or
  - an authoritative snapshot plus only events strictly after the snapshot
    cursor; or
  - an explicit replay-gap response with an authoritative snapshot.
- Read the snapshot metadata and bounded event suffix in one repeatable-read
  transaction or under a captured upper bound so a concurrent commit cannot
  produce a mixed view.
- Never replay legacy unvalidated payloads. Convert them through the closed
  schema or force a replay gap.

## Mandatory tests and evidence

The Worker result is eligible for integration only when every item below is
green and recorded in the Worker report.

1. `bash gradlew :InplaceX-backend:test`
2. `bash gradlew verifyProject`
3. `git diff --check`
4. PostgreSQL/Testcontainers migration test from the current schema through the
   replacement `V3`, including deterministic backfill and unique constraints.
5. A migration rollback test using a fresh, actually executed test migration
   version.
6. Concurrent commits against the same expected revision: exactly one wins and
   the loser receives an explicit conflict without partial rows.
7. Retry of an old command after later turns returns the original immutable
   receipt/result and creates no second event.
8. Same command id from the same actor with changed content is rejected; the
   actor/idempotency scope is covered.
9. Failure during command/event insertion rolls back command, session, snapshot,
   and event changes together.
10. Reconnect tests cover contiguous replay, snapshot-plus-later-events,
    replay gap, truncation/retention, restart, and a concurrent commit during
    reconnect.
11. A public-schema/redaction corpus covers all forbidden names, nested values,
    Unicode escapes, invalid JSON, unknown fields/event types, and the 64 KiB
    boundary for snapshots and events.
12. Existing repository tests remain green; do not weaken or delete them to
    satisfy the new migration.

## Integrator gate

- The Integrator must rerun backend tests with Java 21/Java 11 toolchains and a
  real PostgreSQL container.
- The candidate must be reviewed against the current `origin/develop`, not only
  against the rejected Worker branch.
- Any claim of full active-match recovery is blocked until encrypted secret
  persistence and aggregate rehydration are proven; public reconnect durability
  alone must be described accurately.
