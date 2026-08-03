# Core Engine Acceptance

## Scope

The core-engine stage covers deterministic game behavior shared by local
Android matches, campaign, bots, and server-authoritative duels. It does not
include visual polish, ad-network SDKs, provider authentication, store
publishing, or production infrastructure activation.

## Physical Ownership

- `InplaceX-bot-core` owns pure match lifecycle, configuration, validation,
  scoring, secret generation, deduction, campaign rules, bot behavior, and
  provider-neutral match contracts.
- `InplaceX-backend` owns server-authoritative duel/session state, membership,
  matchmaking, invite rooms, server clocks, idempotency, and bot participation.
- `InplaceX-android:app` owns Android state restoration, timers, route
  adaptation, and presentation. It must map every rule into the shared core and
  must not reimplement scoring or secret validation.

## Required Invariants

### Configuration and rules

- Code length is `4..20`.
- A no-duplicates game cannot exceed the ten-symbol decimal alphabet.
- Attempt limits and configured turn time limits are positive.
- Secret generation, fixed test secrets, restored secrets, and submitted
  guesses use the same `GameConfig` validation.
- Friend games allow repeated digits but at most three equal digits in a row.
- Rule flags survive mode, route, Android state, backend snapshot, and process
  recreation boundaries.

### Lifecycle

- The only phases are not-started/setup, active, won/finished, and lost.
- A winning attempt is terminal and must be the last accepted attempt.
- Attempt exhaustion and total/turn timeout produce an explicit terminal loss.
- Finished matches reject further mutation and never restart implicitly.
- Checkpoint restoration is atomic and fails closed on malformed or
  rule-incompatible state.

### Evidence and campaign

- Manual table marks remain hypotheses and never become proven facts.
- Confirmed exact positions prefill every following attempt until changed or
  removed.
- Authoritative hints and accepted attempts drive deterministic deduction;
  contradictions are explicit.
- Campaign attempt budgets are derived from a versioned deterministic solver
  benchmark, not an opponent animation profile.
- A loss earns zero stars; a win on the final allowed attempt earns one star.

### Duel and online authority

- Turn-based duels enforce current turn; races accept concurrent stale
  revisions while retaining idempotent command IDs.
- A finite turn-based duel gives both participants the same number of attempts:
  the second participant may solve on the matching final turn, otherwise the
  exhausted match ends in an explicit draw. A race still ends as soon as one
  participant exhausts their own attempt budget.
- Secrets and an opponent's raw guesses never appear in a viewer snapshot.
  An authenticated viewer receives `ownGuess` only for attempts they submitted,
  so reconnect can rebuild their deduction history without trusting transient UI state.
- Human matchmaking prefers a waiting player and falls back to a server bot
  only after the configured delay.
- Private rooms bind exactly two server-owned memberships, creator-selected
  length, race/turn-based style, and a server-owned deadline.
- Terminal outcome, winner/draw, finish reason, revision, and server time are
  authoritative.

## Closure Gates

A core change is eligible for integration only when all of these pass on the
same commit:

1. `:InplaceX-bot-core:test`
2. `:InplaceX-backend:test`
3. `:app:testDebugUnitTest`
4. `verifyProject`
5. Android lint and debug/release assembly
6. emulator smoke for start, valid/invalid submit, terminal result, and
   recreation
7. physical-device acceptance for campaign, bot duel, race terminal state, and
   two-client online flow when two devices are available

Benchmark output is balance evidence, not a replacement for functional tests.
