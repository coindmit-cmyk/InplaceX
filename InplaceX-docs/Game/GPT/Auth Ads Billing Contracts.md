# Auth Ads Billing Contracts

## Canonical Services

- production `MirkoriPlatformRuntime`
  - `restoreOrBootstrap()`
  - `beginLogin()`
  - `completeLogin(callbackUrl)`
  - `beginGoogleLogin()`
  - `completeGoogleLogin(idToken, conflictResolution)`
  - `cancelPendingLogin()`
- production `AccessTokenProvider`
  - `currentAccessToken()`
  - `refreshAccessToken(rejectedToken)`
- legacy `AuthService` is debug/test compatibility only
  - `currentSession()`
  - `signInWithGooglePlay()`
  - `signOut()`

- `AndroidAdRuntime`
  - `plan(request)`
  - `preload(request)`
  - `show(request)`
- legacy synchronous `AdService` remains only as a compatibility boundary for
  variant tooling and code not yet removed

- `BillingService`
  - `cachedState()`
  - `refresh()`
  - `purchase(productId)`

`BillingService` is asynchronous and returns typed availability, notice,
catalog, pending-order, and server-confirmed entitlement state. The RF flavor
may return a validated external HTTPS checkout URL; the global flavor performs
the Platform-selected Google Play flow and returns only updated state. Browser
navigation and Play callbacks are never payment proof.

## Auth Model

- Mirkori Games Platform is the only production identity and token authority
- offline gameplay does not require a linked account; when online identity is
  needed, the Platform SDK restores or bootstraps a guest game profile
- account linking preserves the same stable InplaceX `gamePlayerId` across
  `GOOGLE`, `TELEGRAM`, `LOCAL`/email, website, and future providers
- the Mirkori action uses the Platform browser/PKCE flow; the dedicated Google
  action uses Android Credential Manager with the pending PKCE `state` as the
  Google nonce and submits the returned ID token only to Mirkori Games Platform
- Android holds the Google ID token only for the duration of that native
  exchange and never places it in a URL or log; the InplaceX online backend
  never receives provider credentials, and provider subjects plus verification
  rules remain inside Mirkori Games Platform
- a Google account that already owns an InplaceX profile first returns the
  fail-closed `profile_conflict`; Android keeps the Google credential only in
  memory while showing the explicit choice. `USE_EXISTING_PROFILE` switches the active
  Platform session only after confirmation, while cancel clears the pending
  login. Neither path merges or deletes profiles, and local campaign data is
  unchanged
- every release online request uses the same refreshed game-scoped Platform
  bearer token; the backend verifies `RS256`, configured issuer/audience,
  canonical `sub/pid/jti`, and exact `gid=inplacex`
- the online principal is `pid`; the global account `sub` is audit context and
  must never replace the game player identity
- the former InplaceX guest bootstrap, direct Google challenge/ID-token
  exchange, backend JWT issuing, and local logout contract are retired from
  release composition and remain only debug/test or historical compatibility
- `InplaceX-auth-core` retains provider-neutral legacy rules and tests, but it
  is not a production identity authority

## Monetization Model

- Mirkori Games Platform is authoritative for permanent paid products:
  - `REMOVE_ADS`
  - `PRO`
  - `PRO_PLUS`
- legacy local premium columns are ignored when deriving permanent paid access
- the encrypted Android cache contains only the last server-confirmed grants,
  their validity window, a trusted server-time anchor, and a pending
  order/checkout retry identity scoped to the exact Platform account and game
  profile
- entitlement projection uses the stable typed contract, independently of the
  currently saleable catalog: `REMOVE_ADS -> ads.disabled/DURABLE/1`,
  `PRO -> pro.active/TIMED/1`, and
  `PRO_PLUS -> pro-plus.active/TIMED/1`. Delisting or repricing an offer cannot
  revoke an already confirmed entitlement
- `PRO_PLUS` includes `PRO`: effective entitlement state, gameplay, Profile,
  Shop, and paid-order completion all treat an active Pro+ grant as active Pro,
  and the lower Pro checkout is disabled
- debug builds may OR explicit local developer toggles with server grants;
  release builds ignore all local paid flags and remain server-only
- changing account or game profile invalidates both the cached grants and the
  pending checkout
- temporary `Pro`, bought with local coins, remains a separate local bonus and
  must never be presented as a provider purchase

- derived entitlements:
  - `adsDisabled`
  - `autoTableAssistEnabled`
  - `infiniteHintsEnabled`

## Distribution Checkout Flow

1. Android refreshes the Platform catalog and current entitlements for the
   current guest or linked game profile.
2. Before creating a new order, Android calls the explicit authenticated
   `/api/v1/commerce/orders/pending` projection, never the bounded order
   history. Exactly one compatible `PENDING` order is restored; multiple,
   unknown-product, or wrong-currency candidates fail closed and no
   new order is created. This also recovers after reinstall or on a second
   linked device.
3. Before the first order request it durably stores one order idempotency key,
   one checkout idempotency key, and an immutable offer snapshot containing
   amount, currency, entitlement-schema version, and product version. Ambiguous
   network failure, process restart, repricing, delisting, and user retry reuse
   that exact attempt instead of comparing it with the current catalog.
   If order creation returns `order_pending`, the losing local attempt is
   cleared and the same pending-order reconciliation is repeated; cancellation
   still propagates and cannot be converted into a retry state.
4. The immutable Android distribution selects the payment path. `rf-mirkori`
   (`com.mirkori.inplacex.rf`) keeps the validated external HTTPS Mirkori
   checkout and currently requires a linked profile. `global-google`
   (`com.mirkori.inplacex`) accepts guest and linked profiles and permits only
   the Platform-returned `google_play` Android method with
   `nextAction.type=embedded_sdk` and `sdkAdapter=google_play_billing`.
5. Global sets the exact opaque `clientToken` as
   `BillingFlowParams.setObfuscatedProfileId`, queries owned purchases on every
   foreground commerce refresh, and launches Play only when no matching owned
   or pending purchase exists. Product, package, distribution, payment channel,
   obfuscated profile, and order must all match.
6. A `PURCHASED` token is sent only to
   `/api/v1/commerce/payments/{paymentId}/google-play-purchase` with the persisted
   idempotency identity. It is never stored or logged by InplaceX. Pending and
   cancelled Play results remain explicit fail-closed states; the server owns
   acknowledgement or consumption after durable settlement.
7. Returning to the app triggers order polling, owned-purchase recovery, and
   entitlement refresh. Browser return, provider redirect, Play callback, or a
   `PAID` order alone does not unlock anything.
8. Permanent access changes only after the Platform returns the matching active
   entitlement grant for the current account/profile. A paid order without the
   grant remains explicitly `AWAITING_ENTITLEMENT`.
9. Cancelled/refunded orders are terminal even if the product was delisted or
   repriced. Expired or reconciliation-required checkout links, offline state,
   Platform `503`, token refresh, and retry remain explicit UI states. A conclusively
   expired checkout retains its key and blocks a replacement until the server
   authoritatively confirms cancellation of the original provider payment; ambiguous
   failures retain the original key. Last confirmed timed grants use an HTTPS
   `Date` observation plus the device monotonic clock and persisted boot marker;
   wall-clock changes never extend access. Clock rollback or reboot fails timed
   access closed until a fresh trusted server observation is available.
10. Current YooKassa and Google Play product contracts are prepaid, fixed-term
   access with no automatic
   renewal. UI must say `Pro access`/`Pro+ access`, show the typed offer duration
   when available, and never claim that this flow is a recurring subscription.
11. Platform response bodies are streamed with a hard 64 KiB limit. Overflow
   explicitly cancels the response body channel before returning a typed
   transport failure.

## Game Purchase Delivery

- After a successful entitlement projection refresh, both Android
  distributions poll `/api/v1/commerce/game-deliveries` with the current
  profile token. A `401` refreshes the same account/player session before the
  exact request is retried.
- InplaceX applies `coins` and the legacy-compatible `currency.coins`
  `consumable_balance` keys to the local coin balance. The balance mutation and
  insertion of the immutable delivery journal row occur in one SQLite
  transaction.
- The journal is scoped to the Platform account and game player and retains the
  delivery/event/entitlement/order identities, sequence, action, signed payload
  hash, exact quantity delta, correction quantity, validity, and one persisted
  acknowledgement idempotency key. A replay with changed immutable data fails
  closed.
- Process loss after local application but before acknowledgement cannot grant
  value twice: the next poll finds the same journal row, skips the balance
  mutation, and retries `ack` with the stored key. The local acknowledged time
  is written only after the Platform returns the matching acknowledgement.
- Refund delivery applies only the signed `quantityDelta`. A non-zero
  `correctionQuantity` remains an operator-side correction obligation and is
  never converted into additional local debit or credit.
- Before local application, the exact `productId` must resolve uniquely in the
  freshly fetched Platform catalog and its product kind, entitlement key/type,
  and original quantity must match the delivery. A missing, duplicate, or
  mismatched product remains pending and is not acknowledged.
- `ads.disabled`, `pro.active`, and `pro-plus.active` delivery events are
  journalled and acknowledged only after the authoritative entitlement
  projection has been persisted. Unknown products, entitlement keys, kinds,
  and permanent-game deliveries remain pending and are never acknowledged by
  this game adapter.
- After refresh or purchase, Compose reloads the same local progress repository
  so delivered coins become visible without restarting the app.

## Rewarded Hint Flow

1. player taps a hint with zero stock
2. UI opens a reward dialog
3. Android awaits `AndroidAdRuntime.show(...)`
4. only `AdPresentationResult.Completed`, produced by the SDK reward callback,
   grants the selected hint one immediate-use allowance
5. the allowance is consumed on the next actual hint application

This is intentionally separate from permanent hint inventory.

## Ad Policy

- no post-match ads in the first `20` matches
- after that, post-match ads are eligible every fourth match
- post-match policy can additionally require accumulated foreground-use time;
  the shared compatibility default remains `0`, while the release variant
  defaults to `1800` seconds
- once the runtime persists the last successful interstitial, cadence is
  measured as completed matches since that impression; a failed/no-fill
  attempt must not reset cadence
- `adsDisabled == true` suppresses forced banner and post-match ads; opt-in
  rewarded offers remain available because they grant a player-requested item
- the game banner slot is requested only during an active game
- the shell reserves banner height only after the SDK reports `onAdLoaded`
- `REMOVE_ADS`, `PRO`, and `PRO_PLUS` all suppress the banner before the
  provider is called; debug tooling must never override this entitlement
- a rewarded item may be granted only after the provider reports `Completed`
- a modal rewarded prompt may temporarily own window focus; Android host
  eligibility therefore requires a resumed, non-finishing, non-destroyed
  Activity but does not reject it solely because `hasWindowFocus()` is false
- `InplaceX-ads-core` is the canonical placement/result policy; a provider SDK
  must not duplicate or bypass it

## Runtime Routing And Revenue Ownership

- provider identity fixes the revenue beneficiary and is never selected from
  caller-authored account data:
  - `OWNER_YANDEX` -> owner
  - company provider identifiers are reserved extension points and are not
    active in the first release
- the backend-facing market resolver returns only the coarse runtime market
  `RUSSIA`, `GLOBAL`, or `UNKNOWN`; Android must not derive ad routing from the
  store account, device locale, or a raw IP stored on the client
- raw IP must not be returned to Android, persisted for ad routing, or logged
- Yandex owner inventory is temporarily eligible in `RUSSIA`, `GLOBAL`, and
  `UNKNOWN` until a non-Russian provider is implemented and approved
- privacy consent and configured placements remain mandatory in every market
- provider availability is a separate Android/runtime capability filter so a
  build or device can exclude an SDK without changing beneficiary ownership
- providers may preload concurrently only after the player has made an
  explicit privacy choice; accepted and declined are both decisions, while
  `UNDECIDED` performs no SDK initialization or network request
- a declined choice is passed to the SDK as no personalization; it does not
  fabricate a successful impression
- changing the privacy decision invalidates every loaded fullscreen ad and
  recreates the banner before any request under the new decision
- `Completed` and `Dismissed` mean an ad was presented; `NotReady`,
  `ProviderUnavailable`, and `Failed` remain explicit fail-closed results
- route logs may contain coarse market, placement, provider enum, beneficiary,
  result, and attempt count; they must not contain raw IP, account identifiers,
  ad-unit identifiers, credentials, or provider payloads

## Integration Rule

- game code uses platform contracts only
- release Android identity uses `MirkoriPlatformRuntime` and the Platform Game
  SDK; release online transport obtains tokens through `AccessTokenProvider`
- the release backend verifies Platform game tokens and provisions only the
  game-local `pid` projection; it does not compose an InplaceX token issuer
- direct provider adapters and `InplaceX-auth-core` remain debug/test or
  historical compatibility only
- Android ad adapters use `InplaceX-ads-core`
- debug builds may use `StubGooglePlayAuthService`, `StubAdService`, and
  `StubBillingService` only when no real Mirkori billing runtime is injected
- release builds never contain or resolve those stub classes, even if runtime configuration says `SANDBOX`
- release composes `MirkoriBillingService`; its compile-time distribution
  source set selects RF browser checkout or global Google Play Billing.
  Absent/invalid Platform commerce configuration fails closed through
  `UnavailableBillingService`
- Yandex Mobile Ads SDK 8 is the temporary active owner adapter for banner and
  rewarded placements in every market until another provider is connected;
  post-match interstitial is enabled only when its optional placement id is
  present
- Yandex automatic initialization is disabled; Android passes the persisted
  consent choice and initializes the SDK manually before preload
- rewarded completion is accepted only from `onRewarded`; dismissal without
  that callback never grants a reward
- the UI uses the asynchronous runtime; `AdService` is no longer the live game
  presentation path
- `GET /api/v1/runtime/ad-market` returns only `RUSSIA`, `GLOBAL`, or `UNKNOWN`;
  production resolves the current numeric IP through a local MMDB database and
  accepts the proxy-overwritten client IP only from configured proxy hosts
- the Android resolver caches only a known coarse result for five minutes;
  `UNKNOWN` is not cached, so a later request can recover after connectivity
  returns, while invalid response, network failure, or missing HTTPS endpoint
  still fail closed
- ordinary `assembleRelease` may produce an unsigned CI artifact, while the
  separate signed-candidate variant and distribution-only `releaseCandidate`
  depend on both
  `validateProductionReleaseConfig` and `validateReleaseSigningConfig`; they
  cannot bypass the online/platform HTTPS, Yandex, external signing, or
  owner-certificate fingerprint gates; ordinary release/internal-distribution
  variants remain unsigned even when the key is configured
