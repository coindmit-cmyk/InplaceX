# Server Bot Runtime

## Purpose

The server bot is not a different brain.

It is a backend-side player wrapper around the shared `bot-core` logic.

This lets us reuse the same solve behavior:

- on device for offline or local bot matches
- on server as a fallback PvP participant when online is low

## What The Server Bot Must Do

The server bot has to behave like a real player in a duel.

That means it owns both sides of play:

1. its own hidden secret
2. its own outgoing guesses against the opponent
3. scoring of incoming guesses made against its secret

## Current Backend Shape

The first backend runtime object is:

- `ServerBotPlayer`

It wraps:

- a generated or injected secret
- a shared `BotSolver` brain for offensive play
- defense history for incoming guesses
- offensive history for bot-made guesses
- pending turn state so the server can safely retry or resend the current bot move

## Match Responsibilities

At match start the backend bot should:

1. generate a valid secret from the active duel rules
2. expose only public bot profile data to clients
3. keep the secret private until the match is finished

During the match it should:

1. score incoming guesses from the opponent
2. produce its next guess when the room turn logic allows it
3. register feedback for its own last guess
4. stop producing new turns after either side has already solved

## Why Pending Turn State Matters

Backend transport can retry, reconnect, or re-send.

Because of that the bot runtime should not generate a new guess every time a caller asks.

If the previous bot guess has not received score feedback yet, the backend should return the same pending turn again.

That makes the bot safe to use inside room state machines and retrying message handlers.

## Непредсказуемость production-бота

Секрет и случайность решений серверного бота создаются через `SecureRandom`.
Открытые клиенту `sessionId`, `playerId` и правила комнаты не используются как seed,
поэтому клиент не может заранее воспроизвести секрет по данным matchmaking.
Детерминированные seed разрешены только тестовой фабрике backend.

## Future Layers

This is only the first server slice.

Future backend work will still need:

- room/session orchestration
- human player connection adapters
- matchmaking
- persistence and reconnect state
- authoritative turn ordering

But the bot can now already be treated as a server-side participant instead of a UI helper.
