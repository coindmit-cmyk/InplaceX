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

## Implemented production composition

The release application currently composes Platform game-token verification,
PostgreSQL player projection, matchmaking, private invites, authoritative duel
sessions, encrypted restart recovery, the v1 session WebSocket, ad-market
routing and health/release endpoints. It uses direct JDBC migrations and
repositories with HikariCP.

Profile APIs, cloud-save HTTP APIs, ticket cancellation, rankings/seasons and
entitlement/billing/reward mutation are roadmap modules and are not routed.
Some migrations, repositories, schemas and retired identity compatibility code
exist for them, but those artifacts must not be described as shipped endpoints.
The one-time legacy membership bridge is routed only to transfer an active
legacy session to the stable Platform `pid`; it does not restore the retired
standalone identity runtime.

The normative v1 boundary for Platform-token authentication, legacy membership
migration, cloud save, matchmaking, duel
commands, snapshots, WebSocket events, concurrency, redaction, and security is
[`Online Contracts v1`](Online%20Contracts.md). This architecture document
defines module ownership and rollout; it must not introduce a second wire
contract.

## Original stack direction

- `Kotlin`
- `Ktor`
- `PostgreSQL`
- `kotlinx.serialization`
- Mirkori game-scoped `RS256` access-token verification
- project JDBC migration runner
- `HikariCP`
- `Testcontainers`
- `Docker`

The implemented persistence slice uses explicit JDBC and the project migration
runner rather than Exposed/Flyway. Future modules should extend the existing
transaction boundary unless an approved migration changes that decision.

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
   Mirkori game-token verification, exact claim validation, and idempotent
   projection of the Platform `pid` into the game-local player row
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

### 1. Platform guest identity

The client remains offline-first. When online identity is needed, Android uses
the Mirkori Platform Game SDK to restore or bootstrap its installation and
InplaceX game profile. The Platform owns the installation secret, account,
refresh family, and game-scoped credential pair.

The InplaceX backend does not bootstrap a second device identity and does not
issue another JWT. It verifies the Platform `RS256` token, requires the
configured issuer/audience and exact `gid=inplacex`, then idempotently projects
the stable `pid` into the game-local `players` row.

### 2. Optional Platform account linking

Offline play remains available without linking an account. Profile linking is
performed through the Mirkori browser/PKCE flow; Google, Telegram, local/email,
and future provider credentials remain inside the Platform. Linking preserves
the same InplaceX `gamePlayerId` and does not replace local campaign progress.

The InplaceX backend never verifies a Google or Telegram provider token, never
links provider subjects, and never reissues a backend JWT after linking. The
former direct Google challenge/bootstrap flow is retained only as historical
debug/test compatibility and is not part of the release composition.

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
  - `id` (the verified Platform `pid` / `game_player_id`)
  - `created_at`
  - `display_name`
  - `country_code`
  - `last_seen_at`
  - `status`

Production provider identities, device installations, refresh families, and
provider subjects belong to Mirkori Games Platform and are not copied into the
InplaceX database. Any older `player_identities` or `device_installations`
schema is migration/historical evidence for the retired debug/test identity
adapter, not the current release model.

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
  - runtime startup restores non-expired rows and reconstructs the create
    command replay from `player_id + command_id`
  - `player_id + command_id` is unique; active instances claim the oldest
    compatible waiting row with `FOR UPDATE SKIP LOCKED`
  - session creation and both matched ticket updates share one transaction;
    bot fallback locks the same waiting row
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
  - `state_iv`
  - `state_ciphertext`
  - `expires_at`
  - runtime writes `state_iv` + `state_ciphertext` atomically with the optimistic
    `version`; the ciphertext is the recoverable aggregate memento and uses the
    session id as AES-GCM authenticated data
  - active reads, commands and timeout transitions take command-scoped row
    ownership with `FOR UPDATE`, restore the latest memento, and commit any next
    version before releasing the transaction; process memory is not authority
- `private_duel_invites`
  - owner, optional direct target, guest, create/accept command ids, immutable
    rules and session link are restored through the invite retention window
  - `owner_player_id + create_command_id` and non-null
    `guest_player_id + accept_command_id` are unique
  - acceptance locks the invite row and writes the session plus matched invite
    in one transaction across active instances
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
- `private_duel_invites`
  - owner, optional target, and guest player IDs
  - create/accept command IDs
  - immutable rules JSON
  - status, session ID, and expiry
- `online_command_results`
  - operation, actor, command ID, and request fingerprint
  - exact response JSON and expiry

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

- production identity and credential refresh are owned by Mirkori Games
  Platform and consumed through the Platform Game SDK
- the InplaceX online backend verifies the Platform public key plus exact
  issuer, audience and `gid=inplacex`, and maps `pid` to the online player
- legacy `/api/v1/auth/*` sources are debug/test compatibility only and are not
  part of the production application composition

### Profile

Future; not routed in the production composition.

- `GET /api/v1/me`
- `PATCH /api/v1/me`
- `GET /api/v1/me/progression`

### Cloud save

Future; repositories/schema do not currently imply public routes.

- `GET /api/v1/me/save`
- `PUT /api/v1/me/save`
- `GET /api/v1/me/save/revisions`

### Matchmaking

- `POST /api/v1/matchmaking/tickets`
- `GET /api/v1/matchmaking/tickets/{ticketId}`
- `DELETE /api/v1/matchmaking/tickets/{ticketId}` — future, not routed

### Duel sessions

- `GET /api/v1/sessions/{sessionId}`
- `POST /api/v1/sessions/{sessionId}/setup/secret`
- `POST /api/v1/sessions/{sessionId}/turns`
- `POST /api/v1/sessions/{sessionId}/reconnect`

### Ranking

Future; not routed in the production composition.

- `GET /api/v1/seasons/current`
- `GET /api/v1/leaderboard`
- `GET /api/v1/me/rank`

### Billing and ads

Entitlement, purchase-validation and reward-mutation routes are future. The
read-only ad-market resolver is implemented separately at
`GET /api/v1/runtime/ad-market`.

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

- use the external Platform API for identity bootstrap/refresh, and InplaceX
  REST for profile, save, billing, matchmaking, and the reliable
  duel command/recovery path;
- use the authenticated WebSocket for live duel session updates and the
  versioned subscribe/resync protocol;
- if duel commands are accepted over WebSocket, they must use the same
  transport-neutral command schemas and idempotency/revision rules as REST;

This keeps the server simpler:

- REST remains easy to debug
- live match updates are still low-latency
- reconnect can always fall back to `GET /sessions/{id}`
- PostgreSQL-backed startup restores every non-expired encrypted duel before
  online routes begin serving requests; decryption or replay inconsistency is a
  startup error rather than a silent downgrade to empty in-memory state

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

- Mirkori game-scoped JWT for API auth
- `RS256` access tokens with the private key isolated in Mirkori Games Platform;
  the game API verifies with the public key only, requires the configured
  issuer/audience and exact `gid=inplacex`, and authorizes by `pid` rather than
  the account `sub`. See `Auth Process Boundary.md`.
- Mirkori Games Platform owns refresh-token rotation and persistent refresh
  idempotency; the InplaceX backend never accepts or stores refresh tokens
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

## Remaining delivery order

1. Operate and observe the implemented online slice in a bounded rollout.
2. Define and route cloud-save plus profile APIs against Mirkori Platform
   identity; add conflict/restart E2E before claiming them shipped.
3. Implement matchmaking cancellation with the same PostgreSQL coordination
   and idempotency guarantees as create/read.
4. Add server-authoritative entitlements and the Mirkori payment adapter for
   the selected markets; client flags remain non-authoritative.
5. Add ranking/seasons only after match-result authority and abuse monitoring
   are stable.

Each new route requires production composition, authorization, principal plus
operation rate limits, migration/restart proof, nginx routing and canonical
documentation in the same package.
