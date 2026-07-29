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

- first provider: `GOOGLE_PLAY`
- auth is optional
- local DB stores:
  - `player_display_name`
  - `google_play_signed_in`

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

## Ad Policy Stub

- no post-match ads in the first `20` matches
- after that, post-match ad cadence is sparse
- all ads are skipped when `adsDisabled == true`
- the game banner slot is requested only during an active game
- the shell reserves banner height only after the provider accepts the slot
- `REMOVE_ADS`, `PRO`, and `PRO_PLUS` all suppress the banner before the
  provider is called; debug tooling must never override this entitlement

## Integration Rule

- game code uses platform contracts only
- debug builds use `StubGooglePlayAuthService`, `StubAdService`, and `StubBillingService`
- release builds never contain or resolve those stub classes, even if runtime configuration says `SANDBOX`
- until the actual provider SDK integration is complete, release adapters fail closed: sign-in stays signed out, purchases return `false`, and ad methods return `false`; caller state must change only after a successful contract result
- later SDK integration must preserve these interfaces and may enable release operations only after the SDK proves the provider result
