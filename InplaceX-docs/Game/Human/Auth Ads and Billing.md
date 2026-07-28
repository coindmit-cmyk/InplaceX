# Auth, Ads, and Billing

## Google Play Auth

- local save remains the primary source of truth
- Google Play is the first auth and cloud-save provider
- sign-in is optional for offline play
- sign-in unlocks future cloud sync and multi-device restore
- the Profile button opens the system Google account chooser
- successful sign-in is confirmed by the InplaceX server, not by a local flag
- an existing guest profile is linked in place, so current progress is kept

## Rewarded Ads

- if a player taps a hint with zero stock, the game offers a rewarded ad
- if the ad is completed, the player receives one immediate bonus use of that exact hint
- the match timer pauses while the reward prompt is open
- the shop also has a rewarded ad offer that grants `20` coins

## Banner and Post-Match Ads

- during gameplay the bottom slot is reserved for a banner ad
- players with `Remove Ads`, temporary `Pro`, permanent `Pro`, or `Pro+` do not see banner ads
- post-match ads are rare and delayed:
  - never in the first `20` matches
  - later only on some matches, not every game

## Paid Products

- temporary `Pro`
  - bought inside the game for `60` coins
  - lasts for `1` hour from purchase
  - another purchase extends the existing expiration by `1` hour
  - enables auto table mode
  - removes banner ads and post-match ads while active
  - does not grant the infinite hints included in `Pro+`
  - cannot be bought while permanent `Pro` or `Pro+` is active

- `Remove Ads`
  - one-time purchase
  - disables banner ads and post-match ads

- `Pro`
  - subscription
  - enables auto table mode
  - removes banner ads and post-match ads

- `Pro+`
  - subscription
  - includes everything in `Pro`
  - disables all ads
  - grants infinite hints

## Current Stage

- Google account authentication is connected to Android Credential Manager and
  the VPS identity service; activation requires the matching Google OAuth web
  client ID in the Android build and identity-service environment
- ads and billing remain local/debug stubs and fail closed in release
- UI and local persistence use the provider contracts
- temporary `Pro` is a local coin purchase rather than a billing-provider product
- real SDK integration comes later without changing the game flow again
