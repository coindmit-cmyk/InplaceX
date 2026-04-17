# Auth, Ads, and Billing

## Google Play Auth

- local save remains the primary source of truth
- Google Play is the first auth and cloud-save provider
- sign-in is optional for offline play
- sign-in unlocks future cloud sync and multi-device restore

## Rewarded Ads

- if a player taps a hint with zero stock, the game offers a rewarded ad
- if the ad is completed, the player receives one immediate bonus use of that exact hint
- the match timer pauses while the reward prompt is open
- the shop also has a rewarded ad offer that grants `20` coins

## Banner and Post-Match Ads

- during gameplay the bottom slot is reserved for a banner ad
- players with `Remove Ads`, `Pro`, or `Pro+` do not see banner ads
- post-match ads are rare and delayed:
  - never in the first `20` matches
  - later only on some matches, not every game

## Paid Products

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

- auth, ads, and billing are implemented as local stubs
- UI and local persistence already use these contracts
- real SDK integration comes later without changing the game flow again
