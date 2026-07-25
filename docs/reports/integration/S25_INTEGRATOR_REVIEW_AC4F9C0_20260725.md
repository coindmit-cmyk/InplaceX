# S25 integrator review — ac4f9c0

## Verdict

`REJECT` for integration. Preserve
`ac4f9c0e16f68a774ac32a0ab48ed97fe1c80078` as salvage evidence, then split
the remaining work into smaller independently gated packets.

## Blocking findings

### P1 — actor scope is not authenticated

`DurableDuelSessionCommand.actorId` is a caller-provided `String`. Persistence
validates its format and uses it as the receipt namespace, but it does not bind
the value to an authenticated principal or session participant. A test
explicitly demonstrates that the same `clientCommandId` under another arbitrary
actor can mutate the session.

The persistence boundary must receive a server-established actor identity and
verify ownership/participation before accepting a command.

### P1 — transition is caller-authored, not server-authoritative

The command accepts prebuilt `resultingSnapshot`, `event`, and `result`.
`validateTransition` checks revision/sequence coherence and selected fields, but
does not derive the result from current authoritative state. It does not fully
enforce active phase/actor, score, winner, history, participant, and immutable
config transitions.

The application layer must compute a validated transition from the current
snapshot and typed intent. Persistence may atomically store that validated
transition; it must not trust client-authored outcomes.

### P1 — redaction and secret fingerprint boundary is incomplete

Forbidden names are checked more strongly than values. Known-field values such
as `seedValue`, `hashValue`, `cipherValue`, `cookieValue`, and
`integrityValue` can pass through opaque ID fields. Raw `actorId` and
`clientCommandId` are also placed in command log attributes.

`SubmitSecret` uses a deterministic unkeyed fingerprint. A four-digit secret is
brute-forceable from a database fingerprint. Online use requires a server-keyed
HMAC/pepper supplied by managed secret configuration.

### P2 — architecture remains monolithic

- `PublicSessionSchema.kt`: approximately 740 lines mixing DTOs, events,
  command content, fingerprinting, legacy parsing, codec, canonicalization, and
  redaction.
- `JdbcDurableDuelSessionRepository.kt`: approximately 856 lines mixing public
  contracts, SQL, transaction control, reconnect, retention, transition
  validation, and logging.

This conflicts with the project goal of keeping each responsibility in its own
component and makes the security boundary difficult to review.

## Confirmed useful salvage

- Replacement V3 does not convert legacy `config_json` into a public snapshot.
- Legacy sessions expose a replay gap until a real public snapshot exists.
- Receipt, revision, snapshot, and event are written atomically.
- Same-actor retries return immutable stored receipts.
- Reconnect captures an upper cursor and returns a bounded coherent page.
- PostgreSQL 16/Testcontainers concurrency coverage exists.

## Independent checks

- Forced backend suite: 37 tests passed.
- PostgreSQL 16/Testcontainers: 4 tests passed.
- `verifyProject`: passed with explicit Android SDK/JDK environment.
- `git diff --check`: passed.
- Branch and published commit match; worktree is clean.

Green persistence tests do not waive the actor, authority, value-redaction, or
architecture blockers.
