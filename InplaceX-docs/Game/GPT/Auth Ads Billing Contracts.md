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
- all ads are skipped when `adsDisabled == true`
- the game banner slot is requested only during an active game
- the shell reserves banner height only after the provider accepts the slot
- `REMOVE_ADS`, `PRO`, and `PRO_PLUS` all suppress the banner before the
  provider is called; debug tooling must never override this entitlement
- a rewarded item may be granted only after the provider reports `Completed`
- `InplaceX-ads-core` is the canonical placement/result policy; a provider SDK
  must not duplicate or bypass it

## Integration Rule

- game code uses platform contracts only
- server auth adapters use `InplaceX-auth-core`
- Android ad adapters use `InplaceX-ads-core`
- debug builds use `StubGooglePlayAuthService`, `StubAdService`, and `StubBillingService`
- release builds never contain or resolve those stub classes, even if runtime configuration says `SANDBOX`
- until the actual provider SDK integration is complete, release adapters fail closed: sign-in stays signed out, purchases return `false`, and ad methods return `false`; caller state must change only after a successful contract result
- later SDK integration must preserve these interfaces and may enable release operations only after the SDK proves the provider result
