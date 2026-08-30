# Mirkori Games Platform Login

## Authority

Mirkori Games Platform owns the global account and stable InplaceX
`game_player_id`. InplaceX continues to own local saves, progression, matches,
and the existing game-backend online session. The platform database is never
read or written by the Android client.

## Android flow

1. `MirkoriPlatformRuntime` restores one encrypted installation identity or
   creates it once.
2. The vendored `platform-game-sdk:0.2.0` bootstraps a global guest profile at
   the configured platform origin.
3. Profile starts a PKCE S256 session and opens its `/connect` URL with the
   system browser. WebView is not used.
4. `MainActivity` receives only the registered HTTPS App Link
   `https://games.dmit.life/connect/inplacex/callback`.
5. The SDK validates the exact callback path, session, and state before it
   exchanges the stored verifier for linked credentials.
6. A profile conflict is shown without overwriting either game profile.

Settings information and support links use Android Custom Tabs against fixed
paths at the same configured Platform origin. They do not share app tokens with
the page and do not introduce an embedded WebView.

## Public profile and friend search

The immutable Platform `gamePlayerId` remains the only relationship and online
authorization identity. A player may additionally claim a mutable lowercase
handle matching `[a-z0-9_]{3,24}`. Android reads and updates that public profile
through the vendored SDK and searches only profiles in `gid=inplacex`.

Search results carry the immutable ID, optional handle, display name, and
optional avatar URL. The Friends UI stores the immutable ID as the target and
the handle only as display metadata. Changing a handle therefore does not break
existing friendships or invitations.

`PUT /api/v1/game-profiles/me/public-profile` accepts any non-empty subset of
`handle`, `displayName`, and the built-in `avatarKey`. The supported avatar
keys are `rocket`, `robot`, `star`, `gamepad`, `heart`, and `bolt`; the response
always returns the complete public profile with a nullable HTTPS `avatarUrl`.

Friendships are game-scoped Platform state. Android creates a request with
`POST /api/v1/game-profiles/me/friend-requests`, polls pending incoming requests
through `GET /api/v1/game-profiles/me/friend-requests/incoming`, accepts one at
`POST /api/v1/game-profiles/me/friend-requests/{requestId}/accept`, and
synchronizes accepted profiles from `GET /api/v1/game-profiles/me/friends`.
Create and accept operations use idempotency keys. Wire requests expose
`requestId`, `status` (`pending` or `accepted`), the other player's public
profile, and `createdAtEpochMs`. A crossed pending request becomes accepted;
mutable handles and names never identify the relationship.

## Protected state

`AndroidKeystoreMirkoriStateStore` encrypts one atomic record with AES-256-GCM.
It contains the installation ID/secret, current account/profile credentials,
an optional pending browser session/state/verifier, the exact refresh-token
plus idempotency-key pair for an in-flight refresh, a pending commerce retry
identity with its immutable offer snapshot, the last server-confirmed paid
grants, and a trusted server-time/monotonic/boot anchor for timed grants.
Commerce state is bound to the exact Platform account and `game_player_id`; an
identity change clears it. Corrupt ciphertext is discarded fail-closed. Secret
values, checkout URLs, account/profile IDs, idempotency keys, and HTTP bodies
are never logged. The state codec writes format v4 and continues to read format
v1/v2/v3 records. Legacy v3 pending purchases restore without inventing a
historical price; the first authoritative order read supplies that snapshot.

The access token may be refreshed through the stored rotating refresh token.
The client persists the refresh pair before network I/O, reuses it after an
ambiguous failure or process restart, and clears it only after a committed
response or an unambiguous rejection. This preserves the server's durable
refresh idempotency contract when the response is lost after token rotation.
If refresh is rejected or expired, the same installation is bootstrapped back
to a guest credential before a new browser login. Network or Platform failures
during current-token lookup, refresh, or bootstrap are reported to online
features as temporary unavailability, not as missing authentication, and do not
erase the persisted Platform session.

## Commerce flow

The same game-scoped Platform session loads products, creates an order and an
external HTTPS checkout, polls the order after browser return/resume, and
refreshes entitlements. Android durably saves and reuses separate order and
checkout idempotency keys across retries and process restarts. It never treats
the browser redirect or an order status alone as ownership: `Remove Ads`, `Pro`,
and `Pro+` become active only from matching server entitlements. Guest profiles
may browse local game content but cannot start a paid checkout until the
Platform account is linked.

Before a new order, Android reads the linked profile's explicit
`/api/v1/commerce/orders/pending` projection rather than bounded order history. It
restores exactly one compatible pending order and fails closed on ambiguous
state, so reinstall and a second device do not create a duplicate order.
Pending price/currency is immutable after creation and current catalog changes
are presentation only. Timed access is prepaid rather than auto-renewing and is
evaluated from HTTPS server time advanced by the monotonic clock; reboot or
monotonic rollback requires a fresh server observation.

Release product identity is immutable across builds:
`inplacex.remove_ads`, `inplacex.pro`, and `inplacex.pro_plus`. Debug may use a
separate configurable sandbox catalog. A future `order_pending` response
discards only the losing local attempt and then restores the authoritative
server pending order through the same projection.

## Online identity boundary

Mirkori Games is the only release identity authority for InplaceX online play.
Every online request uses a fresh Platform game token scoped to `gid=inplacex`;
the backend maps membership and persistence to its `pid` (`game_player_id`),
never to the global account `sub`. Connecting a linked account therefore keeps
the same Platform game profile across Google, Telegram, the website, and online
matches. It does not rewrite local campaign progress. The retired standalone
guest/Google credential path remains source-level debug/test compatibility and
is not composed into the release runtime as an identity authority.

If the selected Google account already owns another InplaceX game profile, the
native flow remains fail-closed and presents an explicit choice. Keeping the
current profile cancels the pending PKCE session. Confirming the existing
Google profile repeats the same verified native exchange with
`use_existing_profile`; the Platform session changes to that existing `pid`
without merging or deleting either server profile. The Google credential stays
memory-only during the dialog, and the local campaign database is not replaced.

For a bounded upgrade window, an unfinished match created before the Platform
cutover may use its encrypted legacy refresh token once as ownership proof. The
client attempts this only after a Platform-authorized session read returns a
membership rejection. The backend atomically replaces the legacy participant
with the current Platform `pid`, consumes and revokes the legacy credential,
and durably records the idempotent result. Android clears the legacy store only
after a follow-up Platform-authorized read confirms the transfer for the exact
session recorded in a non-secret durable attempt marker. Reading another
Platform-owned session cannot clear the proof. Offline, temporary Platform
failure, and ambiguous response loss preserve the proof and the same command
identifier for safe retry; an authoritative rejection of the proof clears both
stores. The raw proof is never logged.

No Mirkori Games sign-out button is exposed in v1 because the platform does not
yet have a provider-session revocation endpoint. When the Platform profile is
linked through Google, the Connections section may disconnect the local Google
Credential Manager state and local Google Play progress flag. This is explicitly
device-local: the Mirkori Games account, encrypted Platform credentials, refresh
family, and active online session remain unchanged, and the player can request a
fresh Google credential on the same linked profile. A future full Mirkori logout
flow must revoke the Platform session server-side before clearing encrypted
Platform state.

## Build and App Link

- application ID: `com.mirkori.inplacex`
- platform base URL fields are variant-specific public configuration
- release cleartext is always disabled
- debug cleartext is allowed only for loopback by the SDK
- manifest callback path is exact and `android:autoVerify=true`
- production verification additionally requires the website asset-links file
  to contain the actual release signing certificate fingerprint
