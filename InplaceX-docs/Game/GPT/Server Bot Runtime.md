# Server Bot Runtime

## Canonical Intent

The backend bot must be modeled as a match participant, not as a standalone helper script.

It should reuse `InplaceX-bot-core` for offensive solving and add backend-facing state around it.

## Current Entry Point

Current backend runtime object:

- `ServerBotPlayer`

Location:

- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/bot`

## Owned State

`ServerBotPlayer` currently owns:

- `ServerBotProfile`
- active `GameConfig`
- hidden secret
- shared `BotSolver` instance
- defensive history of incoming guesses
- pending offensive turn
- solved-state flags for both duel sides

## Required Behaviors

The backend bot must support:

1. generating or accepting a secret
2. validating and scoring incoming guesses against that secret
3. producing the next offensive bot turn
4. registering score feedback for the current pending bot guess
5. exposing a backend snapshot without leaking the hidden secret by default

## Online Match Rules

Matchmaking, private friend rooms, and server-bot fallback use the same
`OnlineMatchRules` selected before search starts. The bot must inherit the
selected play style and code length; it must not replace them with a
difficulty-specific game preset.

Current online rules allow repeated digits, reject runs longer than three
identical digits, and expose no move limit. `GameConfig.attemptLimit` remains an
internal positive capacity required by the core model and is not a player loss
condition for an online duel.

## Pending Turn Rule

`nextTurnOrNull()` is intentionally idempotent while feedback is still missing.

If a room handler asks for the bot move twice before registering score feedback, the same pending turn should be returned.

This avoids accidental double-advance of the bot brain on retries or reconnect flows.

## Production Entropy

Production bot creation does not accept deterministic seeds or a caller-provided secret.
The backend generates both the hidden secret and the bot-brain seed from `SecureRandom`.
Public match identifiers such as `sessionId` and `playerId` must never participate in
either value. Deterministic seeds belong only to the backend test factory.

## Dependency Direction

Allowed:

- `InplaceX-backend` -> `InplaceX-bot-core`

Not intended:

- `InplaceX-bot-core` -> backend module

The shared bot brain stays transport-agnostic.
The backend module owns only server/runtime concerns.
