# Auth Ads Billing Contracts

## Canonical Services

- `AuthService`
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
  - `purchase(productId): Boolean`

## Auth Model

- canonical providers:
  - `GOOGLE`
  - `EMAIL`
  - `TELEGRAM`
- auth is optional
- Google keeps the existing server challenge plus verified ID-token flow
- email is passwordless:
  - six-digit, short-lived code
  - verification proof is HMAC-protected
  - raw email addresses and codes must not appear in logs or token claims
- Telegram accepts only the documented signed login payload:
  - HMAC signature must be valid
  - `auth_date` must be fresh
  - unknown fields fail closed
  - the bot token stays in the identity-service/VPS secret store
- new email and Telegram provider subjects are opaque and domain-separated by
  provider; the already-shipped Google link keeps its provider-issued subject
  until a separately versioned persistence migration is approved
- `InplaceX-auth-core` owns these provider-neutral rules; HTTP routes, SMTP,
  persistence, and Android provider UI remain adapter responsibilities

## Monetization Model

- DB stores:
  - `ad_free_purchased`
  - `pro_subscription_active`
  - `pro_plus_subscription_active`

- derived entitlements:
  - `adsDisabled`
  - `autoTableAssistEnabled`
  - `infiniteHintsEnabled`

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
- `UNKNOWN` fails closed without an ad request
- Yandex owner inventory is eligible only in `RUSSIA`
- `GLOBAL` and `UNKNOWN` return an empty route until a non-Russian provider is
  implemented and approved
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
- server auth adapters use `InplaceX-auth-core`
- Android ad adapters use `InplaceX-ads-core`
- debug builds use `StubGooglePlayAuthService`, `StubAdService`, and `StubBillingService`
- release builds never contain or resolve those stub classes, even if runtime configuration says `SANDBOX`
- Yandex Mobile Ads SDK 8 is the active owner adapter for banner and rewarded
  placements in Russia; post-match interstitial is enabled only when its
  optional placement id is present
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
- `preReleaseBuild` and `preInternalDistributionBuild` depend on
  `validateReleaseAdsConfig`, so a production-like artifact cannot bypass the
  required HTTPS/Yandex gate
