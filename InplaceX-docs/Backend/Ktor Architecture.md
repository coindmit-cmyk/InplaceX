# Ktor Backend Architecture

## Goal

Build the server as an authoritative backend for:

- account sync
- cloud save
- PvP matchmaking and duel sessions
- server-side bot fallback
- rankings and seasons
- entitlement validation for ads, `Remove Ads`, `Pro`, and `Pro+`

The current game docs already define the core product behavior. Backend should preserve those contracts and move authority for online state, purchases, and cross-device sync to the server.

The normative v1 boundary for guest auth, cloud save, matchmaking, duel
commands, snapshots, WebSocket events, concurrency, redaction, and security is
[`Online Contracts v1`](Online%20Contracts.md). This architecture document
defines module ownership and rollout; it must not introduce a second wire
contract.

## Recommended Stack

- `Kotlin`
- `Ktor`
- `PostgreSQL`
- `Exposed` for the first stage
- `kotlinx.serialization`
- `JWT` access tokens
- `Flyway` for migrations
- `HikariCP`
- `Testcontainers`
- `Docker`

## Why Exposed First

For this project, `Exposed` is the pragmatic default:

- faster to start than `jOOQ`
- natural fit with Kotlin data mapping
- enough for profile, session, cloud-save, ranking, and entitlement tables
- easier to keep the first server iteration compact

If later we get very query-heavy analytics, leaderboard aggregation, or deep reporting SQL, we can introduce `jOOQ` selectively for read-heavy modules without rewriting the whole backend.

## Service Boundaries

Start as one modular monolith, not microservices.

Suggested bounded modules inside one Ktor app:

1. `app`
   boot, config, routing, DI, security, observability
2. `auth`
   Google sign-in verification, device identity bootstrap, JWT issuing
3. `profile`
   player profile, settings, currencies, progression snapshot
4. `cloudsave`
   user save upload, fetch, version conflict handling
5. `matchmaking`
   queueing, ticket lifecycle, bot fallback
6. `session`
   authoritative duel room state, reconnect, turn ordering, setup phase
7. `bot`
   adapters around `ServerBotPlayer`
8. `ranking`
   rating, seasons, leaderboard reads
9. `billing`
   entitlement state, purchase token validation, ad-removal flags
10. `ads`
   rewarded grants and ad eligibility policy
11. `shared`
   result types, errors, ids, clock, transaction helpers

## High-Level Runtime Flow

### 1. Anonymous bootstrap

Client starts offline-first, but may request backend bootstrap:

- create or attach a `device_installation`
- create a lightweight player identity if none exists
- receive JWT pair for backend calls

### 2. Optional Google Play sign-in

This follows the current product rule from docs:

- local save remains primary for offline play
- sign-in is optional
- sign-in enables cloud sync and multi-device restore

Backend responsibility:

- verify Google token
- link provider identity to player
- merge or preserve cloud-save ownership rules
- reissue JWT with stable player id

### 3. Cloud save sync

Cloud save should be versioned and conflict-aware:

- client uploads save snapshot plus local revision
- server stores immutable save version and updates latest pointer
- conflicts return both server and client metadata for resolution

### 4. Online duel setup

Based on `Duel Setup Flow`, online duel must preserve the same meaning as local duel:

- match enters `setup` phase
- each participant submits their secret separately
- one participant must never see the other participant secret
- duel must not start before both secrets are set
- if opponent is a bot, the bot secret is generated server-side

### 5. Active session

The server is authoritative for:

- turn order
- accepted guesses
- scoring
- win detection
- reconnect state
- retry-safe bot turns through pending turn state

## Authoritative Duel Session Model

Recommended session phases:

- `SETUP_WAITING_FOR_PLAYERS`
- `SETUP_SECRET_A`
- `SETUP_SECRET_B`
- `ACTIVE_TURN_A`
- `ACTIVE_TURN_B`
- `FINISHED`
- `ABANDONED`

Recommended session rules:

- every command has `sessionId`, `playerId`, `clientCommandId`
- commands are idempotent
- session stores `version`
- each successful mutation increments version
- reconnect API returns the full authoritative snapshot

## Suggested Database Model

### Core identity

- `players`
  - `id`
  - `created_at`
  - `display_name`
  - `country_code`
  - `last_seen_at`
  - `status`
- `player_identities`
  - `id`
  - `player_id`
  - `provider`
  - `provider_user_id`
  - `created_at`
  - unique on `provider + provider_user_id`
- `device_installations`
  - `id`
  - `player_id`
  - `platform`
  - `app_version`
  - `device_label`
  - `last_seen_at`

### Profile and progression

- `player_profiles`
  - `player_id`
  - `avatar_url`
  - `locale`
  - `coins`
  - `rating`
  - `current_season_id`
- `player_season_stats`
  - `id`
  - `player_id`
  - `season_id`
  - `wins`
  - `losses`
  - `draws`
  - `rating`

### Cloud save

- `save_heads`
  - `player_id`
  - `latest_revision`
  - `updated_at`
- `save_revisions`
  - `id`
  - `player_id`
  - `revision`
  - `payload_json`
  - `schema_version`
  - `created_at`
  - unique on `player_id + revision`

### Matchmaking and sessions

- `matchmaking_tickets`
  - `id`
  - `player_id`
  - `mode`
  - `status`
  - `created_at`
  - `expires_at`
- `duel_sessions`
  - `id`
  - `mode`
  - `status`
  - `config_json`
  - `created_at`
  - `started_at`
  - `finished_at`
  - `winner_player_id`
  - `version`
- `duel_participants`
  - `id`
  - `session_id`
  - `player_id`
  - `slot`
  - `participant_type`
  - `bot_profile_json`
  - `connected`
- `duel_secrets`
  - `id`
  - `session_id`
  - `participant_id`
  - `secret_hash`
  - `secret_ciphertext`
  - `submitted_at`
- `duel_turns`
  - `id`
  - `session_id`
  - `turn_number`
  - `actor_participant_id`
  - `guess`
  - `exact_matches`
  - `solved`
  - `created_at`
  - unique on `session_id + turn_number`
- `duel_events`
  - `id`
  - `session_id`
  - `event_type`
  - `payload_json`
  - `created_at`

### Billing and ads

- `entitlements`
  - `id`
  - `player_id`
  - `product_code`
  - `status`
  - `starts_at`
  - `expires_at`
  - `source`
- `purchase_receipts`
  - `id`
  - `player_id`
  - `provider`
  - `product_code`
  - `purchase_token`
  - `validation_state`
  - `raw_payload_json`
  - `created_at`
  - unique on `provider + purchase_token`
- `reward_grants`
  - `id`
  - `player_id`
  - `grant_type`
  - `amount`
  - `reason`
  - `created_at`

## API Shape

Prefer `/api/v1`. Route payloads and error semantics are defined by
[`Online Contracts v1`](Online%20Contracts.md); the list below is only the
module-level route inventory.

### Auth

- `POST /api/v1/auth/bootstrap`
  - create or resume anonymous backend identity for the installation
- `POST /api/v1/auth/google/link`
  - verify Google Play identity and link account
- `POST /api/v1/auth/refresh`

### Profile

- `GET /api/v1/me`
- `PATCH /api/v1/me`
- `GET /api/v1/me/progression`

### Cloud save

- `GET /api/v1/me/save`
- `PUT /api/v1/me/save`
- `GET /api/v1/me/save/revisions`

### Matchmaking

- `POST /api/v1/matchmaking/tickets`
- `GET /api/v1/matchmaking/tickets/{ticketId}`
- `DELETE /api/v1/matchmaking/tickets/{ticketId}`

### Duel sessions

- `GET /api/v1/sessions/{sessionId}`
- `POST /api/v1/sessions/{sessionId}/setup/secret`
- `POST /api/v1/sessions/{sessionId}/turns`
- `POST /api/v1/sessions/{sessionId}/reconnect`

### Ranking

- `GET /api/v1/seasons/current`
- `GET /api/v1/leaderboard`
- `GET /api/v1/me/rank`

### Billing and ads

- `GET /api/v1/me/entitlements`
- `POST /api/v1/billing/google/validate`
- `POST /api/v1/ads/rewarded/complete`

## DTO and Domain Split

Keep three layers separate:

- transport DTOs
- domain models
- persistence records

Do not leak Exposed table rows directly into routes.

## Match Session Transport

For the first stage:

- use REST for bootstrap, profile, save, billing, matchmaking, and the reliable
  duel command/recovery path;
- use the authenticated WebSocket for live duel session updates and the
  versioned subscribe/resync protocol;
- if duel commands are accepted over WebSocket, they must use the same
  transport-neutral command schemas and idempotency/revision rules as REST;

This keeps the server simpler:

- REST remains easy to debug
- live match updates are still low-latency
- reconnect can always fall back to `GET /sessions/{id}`

## Bot Integration

`ServerBotPlayer` already fits the future server shape well.

Recommended next wrapper:

- `ServerBotSessionAdapter`

Responsibilities:

- attach `ServerBotPlayer` to a duel participant slot
- translate session commands into bot actions
- preserve `pendingTurn` retry behavior
- emit session events compatible with human participants

## Security Rules

- JWT for API auth
- refresh token rotation
- never trust client-side scoring
- never trust client-side entitlement flags
- never expose opponent secret in setup or active snapshots
- hash and encrypt stored secrets
- validate every turn against active session version and active actor
- never put access or refresh tokens in URLs, WebSocket query parameters, logs,
  snapshots, or error details
- redact secrets, tokens, provider payloads, guesses, and raw request bodies at
  the logging boundary
- use bounded WebSocket queues and close slow consumers so a socket cannot
  block the authoritative session

See [`Online Contracts v1`](Online%20Contracts.md) for the required error
codes, reconnect cursor, event ordering, backpressure, and contract-test
invariants.

## Suggested Ktor Package Layout

```text
com.mirkori.inplacex.backend
  app/
  auth/
  profile/
  cloudsave/
  matchmaking/
  session/
  ranking/
  billing/
  ads/
  bot/
  db/
  shared/
```

## Delivery Order

### Phase 1

- Ktor app skeleton
- config + health endpoints
- PostgreSQL + Flyway + Exposed
- auth bootstrap
- profile read/write
- cloud save basic sync

### Phase 2

- entitlement validation
- ad reward grants
- season stats + leaderboard

### Phase 3

- matchmaking queue
- duel session aggregate
- reconnect snapshots
- bot fallback through `ServerBotPlayer`

### Phase 4

- WebSocket live session transport
- observability
- anti-abuse limits
- admin tooling

## Recommended First Implementation Slice

If we want the highest value with the least risk, start with:

1. `auth/bootstrap`
2. `GET/PUT me/save`
3. `GET me/entitlements`
4. session aggregate contracts without full live transport

That gives us a real backend foundation without blocking on the hardest online-room logic immediately.
