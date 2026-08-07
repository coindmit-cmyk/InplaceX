# Mirkori Games Platform Login

## Authority

Mirkori Games Platform owns the global account and stable InplaceX
`game_player_id`. InplaceX continues to own local saves, progression, matches,
and the existing game-backend online session. The platform database is never
read or written by the Android client.

## Android flow

1. `MirkoriPlatformRuntime` restores one encrypted installation identity or
   creates it once.
2. The vendored `platform-game-sdk:0.1.0` bootstraps a global guest profile at
   the configured platform origin.
3. Profile starts a PKCE S256 session and opens its `/connect` URL with the
   system browser. WebView is not used.
4. `MainActivity` receives only the registered HTTPS App Link
   `https://games.dmit.life/connect/inplacex/callback`.
5. The SDK validates the exact callback path, session, and state before it
   exchanges the stored verifier for linked credentials.
6. A profile conflict is shown without overwriting either game profile.

## Protected state

`AndroidKeystoreMirkoriStateStore` encrypts one atomic record with AES-256-GCM.
It contains the installation ID/secret, current account/profile credentials,
an optional pending browser session/state/verifier, and the exact refresh-token
plus idempotency-key pair for an in-flight refresh. Corrupt ciphertext is
discarded fail-closed. Secret values and HTTP bodies are never logged. The state
codec writes format v2 and continues to read format v1 records.

The access token may be refreshed through the stored rotating refresh token.
The client persists the refresh pair before network I/O, reuses it after an
ambiguous failure or process restart, and clears it only after a committed
response or an unambiguous rejection. This preserves the server's durable
refresh idempotency contract when the response is lost after token rotation.
If refresh is rejected or expired, the same installation is bootstrapped back
to a guest credential before a new browser login.

## Compatibility boundary

The existing Google Play sign-in and InplaceX online backend credentials remain
separate during this slice. Connecting Mirkori Games does not rewrite local
progress and does not silently switch online-match identity.

No Mirkori Games sign-out button is exposed in v1 because the platform does not
yet have a provider-session revocation endpoint. Discarding a local refresh
token while leaving its server family active would present a false logout.

## Build and App Link

- application ID: `com.mirkori.inplacex`
- platform base URL fields are variant-specific public configuration
- release cleartext is always disabled
- debug cleartext is allowed only for loopback by the SDK
- manifest callback path is exact and `android:autoVerify=true`
- production verification additionally requires the website asset-links file
  to contain the actual release signing certificate fingerprint
