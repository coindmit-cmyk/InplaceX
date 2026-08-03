# InplaceX Online Contracts v1

Status: normative design contract for the first online implementation.

This document defines the transport-neutral boundary shared by REST commands,
WebSocket commands/events, and future client adapters. The JSON Schema catalog
is under [`schemas/online/v1`](../../schemas/online/v1/README.md). Ktor route
composition and persistence remain implementation details; they must preserve
the invariants here.

## Version and representation rules

- REST base path is `/api/v1`.
- WebSocket connections negotiate the `inplacex.online.v1` subprotocol and use
  `schemaVersion: "1.0"` in every JSON envelope.
- JSON field names are camelCase. IDs are opaque UUID strings. Timestamps are
  RFC 3339 UTC strings.
- The server may add optional fields within v1. A required-field, enum,
  meaning, security, or ordering change requires a new contract version.
- Clients ignore unknown optional fields and never infer server state from
  local transient state after reconnect.
- REST responses include `X-Request-Id` and `X-Api-Version: 1`; mutable
  resources expose their revision through the response body and an `ETag`.
- The schemas use JSON Schema Draft 2020-12 and deliberately reject unknown
  fields in security-sensitive request and snapshot objects.

The API and WebSocket layers are bindings of the same command and event
concepts. A command must have the same authorization, validation, idempotency,
revision, and result semantics regardless of its binding.

## Authentication and guest identity

### Bootstrap

`POST /api/v1/auth/bootstrap` creates or resumes a guest identity for an
installation. The request contains an opaque client-generated
`installationId`, platform, and optional app metadata. `installationId` is a
lookup hint, not a credential; possession of it alone never authorizes a
player.

The response is an `AuthTokenResponse` containing:

- stable `playerId` and `accountKind: "guest"`;
- a short-lived JWT access token;
- a rotating opaque refresh token;
- explicit access and refresh expiry timestamps.

Retrying bootstrap for the same authenticated installation is safe and must not
create duplicate player identities. The server may require an
`Idempotency-Key` for an initial request, but the installation ownership and
rate-limit policy remain server-side.

### Refresh

`POST /api/v1/auth/refresh` accepts the current refresh token and returns a new
access/refresh pair. Refresh tokens are one-time-use within a token family;
reuse revokes the family and returns `unauthorized`. Access tokens should be
short-lived (15 minutes is the default target) and refresh tokens must have a
bounded lifetime. The exact lifetime may be configured per environment without
changing the contract.

The access token carries an issuer, audience, subject (`playerId`), expiry and
unique token id. The client sends it as `Authorization: Bearer <token>` for
REST and during the WebSocket handshake. Tokens are never accepted in a query
parameter, WebSocket payload, path, or application log.

Google linking uses the authenticated guest player context:

1. `POST /api/v1/auth/google/challenge` creates a short-lived, single-use
   server challenge for the authenticated player.
2. Android passes that nonce to Credential Manager and receives a Google ID
   token for the configured web client ID.
3. `POST /api/v1/auth/google` submits the ID token and nonce over HTTPS.
4. The identity process verifies the Google signature, issuer, audience,
   expiry, subject, and nonce, consumes the challenge, and links the opaque
   Google subject to the player.

The database stores the provider name and opaque subject, never the raw Google
ID token or email address. A provider subject already linked to a player
restores that player after explicit Google sign-in; a new subject promotes the
current guest player without replacing its progress. A player cannot silently
link a second Google subject. Logout clears the local online session but does
not delete cloud data or the server-side identity link.

## Common command rules

### Idempotency

Every state-changing REST request requires an `Idempotency-Key` header. The
value is 1–128 characters from `[A-Za-z0-9._~-]`. The key is scoped to the
authenticated player, route, and resource. The server stores a request
fingerprint and final response for at least 24 hours.

- Same key and same request fingerprint returns the original result, including
  after a timeout or reconnect.
- Same key with a different fingerprint returns
  `idempotency_key_reused` and does not execute either request again.
- A transport retry must not mint a second ticket, save revision, secret, or
  turn.

Transport-neutral commands also carry a UUID `commandId`. WebSocket retries use
the same `commandId`; `requestId` is only a frame correlation identifier and
may change when the client reconnects. The server deduplicates by
`playerId + commandId` for the command's retention window and returns the same
command result.

### Optimistic concurrency

Mutable resources carry a monotonically increasing `revision`. Commands that
mutate cloud save or a duel require `expectedRevision` (called `baseRevision`
in older save terminology). The server validates it atomically with the
mutation:

- matching revision applies the mutation and increments the revision exactly
  once;
- stale revision returns `409` / `revision_conflict` with the current redacted
  snapshot;
- a duplicate idempotent command returns its original revision and does not
  increment it again.

REST clients send `If-Match` using the last ETag when available. If both
`If-Match` and a body revision are supplied, they must identify the same
revision; otherwise the server returns `invalid_request`.

### Error envelope

Non-2xx REST responses and `session.error` WebSocket events use:

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "The resource changed; fetch the current snapshot.",
    "requestId": "00000000-0000-4000-8000-000000000001",
    "retryable": false,
    "details": {}
  }
}
```

`message` is safe for display but is not a stable programmatic key. Clients use
`code`. `details` may contain safe revision, retry-after, or cursor metadata;
it must never contain tokens, secrets, provider payloads, raw request bodies,
or internal stack traces.

## REST resources and operations

The following routes are the v1 binding of the transport-neutral operations.
Every authenticated route checks that the player owns the referenced resource.

| Operation | Route | Contract |
| --- | --- | --- |
| Guest bootstrap | `POST /api/v1/auth/bootstrap` | `AuthBootstrapRequest` → `AuthTokenResponse` |
| Refresh | `POST /api/v1/auth/refresh` | `RefreshRequest` → `RefreshResponse` |
| Create Google challenge | `POST /api/v1/auth/google/challenge` | authenticated empty request → nonce and expiry |
| Authenticate with Google | `POST /api/v1/auth/google` | Google ID token + nonce → `AuthTokenResponse` |
| Read cloud save | `GET /api/v1/me/save` | `CloudSaveSnapshot` |
| Write cloud save | `PUT /api/v1/me/save` | `CloudSavePutCommand` → `CloudSaveSnapshot` |
| Create matchmaking ticket | `POST /api/v1/matchmaking/tickets` | `MatchmakingCreateCommand` → `MatchmakingTicket` |
| Read ticket | `GET /api/v1/matchmaking/tickets/{ticketId}` | `MatchmakingTicket` |
| Cancel ticket | `DELETE /api/v1/matchmaking/tickets/{ticketId}` | idempotent empty response or `MatchmakingTicket` |
| Create friend invite | `POST /api/v1/friends/invites` | `FriendInviteCreateCommand` → `FriendInvite` |
| Read owned friend invite | `GET /api/v1/friends/invites/{inviteCode}` | `FriendInvite` |
| Accept friend invite | `POST /api/v1/friends/invites/{inviteCode}/accept` | `FriendInviteAcceptCommand` → `FriendInvite` |
| Read duel snapshot | `GET /api/v1/sessions/{sessionId}` | `SessionSnapshotResponse` |
| Reconnect snapshot | `POST /api/v1/sessions/{sessionId}/reconnect` | `ReconnectRequest` → `ReconnectResponse` |
| Submit secret | `POST /api/v1/sessions/{sessionId}/setup/secret` | `DuelSubmitSecretCommand` → `DuelSecretReceipt` |
| Submit guess | `POST /api/v1/sessions/{sessionId}/turns` | `DuelSubmitGuessCommand` → `DuelTurnResult` |

Path identifiers and body identifiers must match. A route must not use a body
`sessionId` to bypass authorization for a different path resource.

`GET /api/v1/sessions/{sessionId}` is the always-available recovery path. The
reconnect operation may return a replay cursor, but a client can safely discard
local transient state and use the returned snapshot as the authority.

The Android client persists only the canonical UUID of an unfinished active
session. On process restart it opens the social match route and fetches this
recovery snapshot before rendering game state. The pointer is cleared on an
authoritative terminal snapshot, explicit match exit, invalid membership, or
sign-out; guesses, secrets, scores, and revisions are not duplicated in this
route marker.

### Cloud save

The save body is an opaque, versioned game state owned by the player. The
server stores immutable revisions and a latest pointer. A stale write returns
both `expectedRevision` and the current `CloudSaveSnapshot`; it does not merge
or silently overwrite client data. Merge policy belongs to a later, explicit
domain contract.

Balances, entitlement flags, ad grants, and server match results are not
client-authoritative cloud-save fields. If they are represented in a client
save payload for compatibility, the server ignores or recomputes them.

### Matchmaking

Ticket creation, cancellation, and status reads are idempotent. A ticket has a
bounded expiry and one terminal state. Matching creates a duel `sessionId`
server-side; clients do not choose an opponent or session authority. A
`matchmaking.matched` WebSocket event is an optimization, not the only way to
discover a match; polling the ticket remains valid.

The staging v1 policy first returns a `searching` ticket and pairs the oldest
compatible ticket from a different authenticated player. If no peer is found
within the server-configured bounded fallback interval (five seconds by
default), the next ticket read atomically creates a server-bot session and
returns `matched` with `matchedWithBot: true`. A searching ticket always has a
null `sessionId`; a matched ticket always has a server-generated non-null
`sessionId`. Replaying the create command returns the ticket's current state,
including a later human or bot match, rather than creating another ticket.

### Private friend invites

A private friend invite is an authenticated, human-only alternative to public
matchmaking:

1. the owner chooses the private play style (`race` or `turn_based`) and a
   secret length from 4 through 10 digits;
2. the server returns an eight-character code with at least 40 bits of
   entropy and a bounded expiry;
3. a different authenticated player accepts the code;
4. the server atomically creates one human session with the owner's immutable
   room configuration and marks the invite
   matched;
5. the owner polls the invite until the shared `sessionId` appears.

The owner cannot accept their own code. A matched or expired code cannot create
another session. Before matching, only the owner can read the invite; after
matching, only its two participants can read it. The code is a discovery
capability, not an authentication credential: every route still requires a
valid access token and all session routes enforce server-owned membership.

Private room rules are server-owned:

- digits may repeat, but four identical digits in one consecutive run are
  rejected; runs of one, two, or three are valid;
- private matches have no move limit (`attemptLimit` is `null`);
- a race accepts guesses from both participants while active and the first
  participant to solve the opponent's secret wins;
- a turn-based duel accepts exactly one participant at a time and alternates
  the current actor after each accepted miss;
- the active match has a ten-minute authoritative deadline. Snapshots expose
  `startedAtEpochMs`, `deadlineAtEpochMs`, and `serverTimeEpochMs`; after the
  deadline the server finishes the match with `finishReason=time_expired` and
  no winner.

The invite response repeats `playStyle`, `codeLength`, `allowDuplicates`,
`maxConsecutiveDuplicateDigits`, and `matchDurationSeconds`, so the guest can
show the exact owner-selected rules before entering setup.

The initial staging implementation keeps active invite/session state in the
game runtime process. A process restart invalidates unfinished invitations and
active staging matches; durable reconnect across server restarts requires the
separate persistence milestone.

### Duel commands

The server validates the active phase, participant ownership, expected revision,
turn actor, game configuration, and rate limit before applying a command.

- `DuelSubmitSecretCommand` accepts a digit string only during setup. It is
  stored as a server-owned hash plus encrypted ciphertext where adjudication
  requires recovery. The command result contains only `secretSubmitted: true`.
- `DuelSubmitGuessCommand` accepts a digit string only for the active actor.
  The server calculates exact-position score and phase transitions. Client
  supplied score, winner, timer, or phase fields are not accepted.
- A bot secret is generated and owned by the server. It follows the same
  redaction rules as a human secret.
- A duplicate command returns the prior receipt/result. A stale revision or
  wrong actor returns a typed error without changing the session.

## Authoritative snapshots and redaction

`DuelSnapshot` is the only source of truth after an initial load or reconnect.
It contains the session revision, event cursor, public game configuration,
participant views, submitted-secret booleans, public turn history, current
actor, winner, phase, and server time.

Turn history is viewer-specific. `ownGuess` is populated only for a turn whose
actor is the authenticated viewer, allowing deterministic reconstruction after
reconnect. It is `null` for every opponent turn; secrets remain absent for both
participants.

The following values are never present in a snapshot, event, REST response,
client error, analytics payload, or ordinary application log:

- either secret, secret hash, secret ciphertext, seed, or key material;
- access token, refresh token, JWT claims beyond the minimum public identity;
- provider ID tokens, purchase tokens, integrity tokens, cookies, or raw
  provider payloads;
- private device identifiers, email addresses, or unredacted request bodies.

Participant IDs are session-scoped opaque IDs. A participant view may expose a
bounded display name and connection status, but not a cross-session provider
identifier. A participant can see that their own secret was submitted and that
the opponent's secret was submitted; neither can read the other's value.

## WebSocket binding

### Handshake and envelope

The v1 endpoint is:

```text
wss://<host>/api/v1/ws/sessions/{sessionId}
```

The handshake must use TLS, `Authorization: Bearer <access-token>`, and the
`inplacex.online.v1` subprotocol. The server rejects query-string credentials.

Client frames have this shape:

```json
{
  "schemaVersion": "1.0",
  "messageId": "00000000-0000-4000-8000-000000000002",
  "requestId": "00000000-0000-4000-8000-000000000003",
  "sessionId": "00000000-0000-4000-8000-000000000004",
  "type": "session.subscribe",
  "payload": {"lastSeenEventSeq": 42}
}
```

The server uses the same envelope with `eventSeq`, `sentAt`, and a nullable
`requestId`. All server events for one session have a strictly increasing
`eventSeq`. `messageId` is unique per emitted frame.

Client control commands are `session.subscribe`, `session.resync`, and
`session.ping`. The same duel commands may be sent as `duel.submitSecret` and
`duel.submitGuess`; they use the REST schemas and command semantics unchanged.
REST remains the fallback for every command and for snapshot recovery.

### Events and reconnect

The v1 server events are:

- `session.snapshot` — authoritative snapshot after subscribe or resync;
- `session.replayGap` — requested cursor is no longer retained, with a fresh
  snapshot;
- `session.participantPresenceChanged`;
- `duel.secretStatusChanged`;
- `duel.turnResult`;
- `duel.phaseChanged`;
- `duel.finished`;
- `matchmaking.matched`;
- `session.error`;
- `connection.heartbeat`.

On reconnect the client refreshes the access token if necessary, opens a new
authenticated socket, and sends `session.subscribe` with its last received
`eventSeq`. The server either replays a contiguous suffix, sends a snapshot and
then a suffix, or emits `session.replayGap` with a snapshot. The client applies
the snapshot before later events and never treats an unacknowledged local
command as success.

### Heartbeat and backpressure

The server sends `connection.heartbeat`; the client answers with
`session.ping` within the advertised connection timeout. Heartbeat frames do
not advance the domain revision.

Each connection has a bounded outbound queue and a maximum JSON frame size of
64 KiB. The server may coalesce presence/heartbeat information, but must not
drop domain state events. A slow consumer is closed with WebSocket code `1013`
and a safe `slow_consumer` error; the client reconnects and resynchronizes from
the authoritative snapshot. The server never blocks the session aggregate on a
slow socket.

Application close reasons are stable codes, not user-visible prose:

| Close code | Meaning |
| --- | --- |
| `1008` | authentication, authorization, or protocol policy failure |
| `1009` | frame exceeds the size limit |
| `1011` | server-side failure; retry according to error policy |
| `1013` | temporary overload or slow consumer; reconnect with backoff |

## Security and abuse boundaries

- HTTPS/WSS is mandatory. TLS termination must preserve the authenticated
  origin and must not downgrade a WebSocket connection.
- JWT verification checks signature, issuer, audience, expiry, token id, and
  player status on every authenticated boundary.
- Refresh, bootstrap, matchmaking, save writes, secret submission, and turns
  have server-side rate limits. A `429` response includes a bounded retry hint;
  clients must not spin on it.
- Client scoring, winner, timers, entitlement flags, ad completion, and
  integrity claims are never trusted without server verification.
- Secrets use a cryptographic hash for comparison and authenticated encryption
  for the minimum server-side adjudication need. Encryption keys live in a
  managed secret store, not in the repository or a client payload.
- Logs contain request IDs, operation names, outcome codes, and pseudonymous
  resource IDs only. Redact keys matching `authorization`, `cookie`, `token`,
  `secret`, `guess`, `purchase`, `integrity`, `providerPayload`, and
  `rawPayload` (case-insensitive). Do not log full request/response bodies.
- Display names and locale/region hints are validated, length-limited, and
  treated as untrusted input. Error messages never reflect unsanitized input.

## Contract test obligations

The contract is testable without a running Ktor server:

1. Validate REST payloads with `schemas/online/v1/rest.schema.json` and WS
   envelopes with `schemas/online/v1/websocket.schema.json`.
2. Assert that a duplicate idempotency key returns byte-equivalent result
   metadata and does not increment a revision twice.
3. Assert that stale cloud-save and duel revisions return a redacted current
   snapshot and no mutation.
4. Assert that snapshots and all event variants contain submission status but
   never a secret, hash, ciphertext, token, or provider payload.
5. Assert that reconnect with a retained cursor replays in order and a missing
   cursor produces a snapshot before any later event.
6. Assert that a wrong participant, wrong phase, invalid guess, or client-made
   score is rejected with a typed error and no state transition.
7. Assert that a full outbound queue closes only the affected socket and leaves
   the authoritative session state available through REST.
8. Assert that a private invite cannot be self-accepted, reused by a third
   player, or converted into more than one session.

These checks apply to backend and client adapters. The Android runtime has one
canonical transport boundary: `KtorOnlineTransport`, composed by
`OnlineRuntime`. Authentication headers, refresh serialization, retry policy,
idempotency headers, WebSocket reconnect cursors, and frame-size limits must
remain inside that boundary; features must not introduce a parallel transport
client with independent security or retry rules. The authoritative backend
routes remain a separate gated delivery. This contract does not authorize
exposing production credentials or real provider configuration.
