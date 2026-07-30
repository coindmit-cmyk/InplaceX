# Auth Ads Billing Contracts

## Canonical Services

- `AuthService`
  - `currentSession()`
  - `signInWithGooglePlay()`
  - `signOut()`

- `AdService`
  - `showBanner(slotId): Boolean`
  - `showRewardedAd(placement): Boolean`
  - `shouldShowPostGameInterstitial(matchesPlayed, entitlements): Boolean`
  - `showInterstitial(placement): Boolean`

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
3. if `showRewardedAd(...) == true`, the selected hint gets one immediate-use allowance
4. the allowance is consumed on the next actual hint application

This is intentionally separate from permanent hint inventory.

## Ad Policy

- no post-match ads in the first `20` matches
- after that, post-match ads are eligible every fourth match
- post-match policy can additionally require accumulated foreground-use time;
  the current compatibility default is `0` seconds until the owner selects the
  live threshold
- once the runtime persists the last successful interstitial, cadence is
  measured as completed matches since that impression; a failed/no-fill
  attempt must not reset cadence
- all ads are skipped when `adsDisabled == true`
- the game banner slot is requested only during an active game
- the shell reserves banner height only after the provider accepts the slot
- `REMOVE_ADS`, `PRO`, and `PRO_PLUS` all suppress the banner before the
  provider is called; debug tooling must never override this entitlement
- a rewarded item may be granted only after the provider reports `Completed`
- `InplaceX-ads-core` is the canonical placement/result policy; a provider SDK
  must not duplicate or bypass it

## Runtime Routing And Revenue Ownership

- provider identity fixes the revenue beneficiary and is never selected from
  caller-authored account data:
  - `OWNER_YANDEX` -> owner
  - `COMPANY_ADMOB` -> company
  - `COMPANY_HUAWEI` -> company
- the backend-facing market resolver returns only the coarse runtime market
  `RUSSIA`, `GLOBAL`, or `UNKNOWN`; Android must not derive ad routing from the
  store account, device locale, or a raw IP stored on the client
- raw IP must not be returned to Android, persisted for ad routing, or logged
- `UNKNOWN` fails closed without an ad request
- Yandex owner inventory has display priority in every supported market
- company inventory is fallback-only and is eligible only in markets supported
  by its provider; AdMob is global-only, while Huawei still passes through the
  runtime market/device availability filter
- provider availability is a separate Android/runtime capability filter so a
  build or device can exclude an SDK without changing beneficiary ownership
- providers may preload concurrently after consent, but display attempts keep
  owner-first order
- `Completed` and `Dismissed` mean an ad was presented and stop fallback;
  `NotReady`, `ProviderUnavailable`, and `Failed` may advance to the next
  eligible provider
- route logs may contain coarse market, placement, provider enum, beneficiary,
  result, and attempt count; they must not contain raw IP, account identifiers,
  ad-unit identifiers, credentials, or provider payloads

## Integration Rule

- game code uses platform contracts only
- server auth adapters use `InplaceX-auth-core`
- Android ad adapters use `InplaceX-ads-core`
- debug builds use `StubGooglePlayAuthService`, `StubAdService`, and `StubBillingService`
- release builds never contain or resolve those stub classes, even if runtime configuration says `SANDBOX`
- until the actual provider SDK integration is complete, release adapters fail closed: sign-in stays signed out, purchases return `false`, and ad methods return `false`; caller state must change only after a successful contract result
- later SDK integration must preserve these interfaces and may enable release operations only after the SDK proves the provider result
- the new asynchronous `AndroidAdRuntime` is the migration target for real SDK
  adapters; the synchronous `AdService` remains a compatibility boundary until
  the UI call sites are migrated without fake success
- the initial `AdMarketResolver` is fail-closed; a later backend adapter must
  supply the IP-derived coarse market through an authenticated/trusted network
  boundary before live routing is enabled
