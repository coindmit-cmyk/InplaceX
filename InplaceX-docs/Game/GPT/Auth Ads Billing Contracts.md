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

## Integration Rule

- game code uses platform contracts only
- current implementation uses `StubGooglePlayAuthService`, `StubAdService`, and `StubBillingService`
- later SDK integration must preserve these interfaces
