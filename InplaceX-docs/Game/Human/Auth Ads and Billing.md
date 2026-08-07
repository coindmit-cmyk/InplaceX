# Auth, Ads, and Billing

## Google Play Auth

- local save remains the primary source of truth
- Google Play is the first auth and cloud-save provider
- sign-in is optional for offline play
- sign-in unlocks future cloud sync and multi-device restore

Until cloud save has an authoritative merge/reconciliation flow, Android platform backup is
disabled. Restoring the SQLite profile without the device-bound Keystore session could otherwise
show a linked account or local premium flags without valid backend credentials. Progress restore
must go through the future authenticated cloud-save contract, not an implicit device backup.
- the Profile button opens the system Google account chooser
- successful sign-in is confirmed by the InplaceX server, not by a local flag
- an existing guest profile is linked in place, so current progress is kept

## Email and Telegram Auth

- the shared security layer is ready for passwordless email and Telegram
- email uses a short-lived six-digit code instead of a stored password
- Telegram login is accepted only after its signature and timestamp are checked
- both providers link to the same InplaceX player model as Google
- sending email, exposing Telegram login in the app, and VPS routes are not live
  until their delivery/provider configuration is installed

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
  - only after the configured minimum active-use time
  - later after every configured `N` completed games, not every game
  - a failed/no-fill attempt does not count as an impression

## Advertising Networks And Revenue Priority

- the current network location is resolved to a coarse `Russia`, `Global`, or
  `Unknown` market; the store used to install the app does not define location
- the owner's Yandex inventory is used only in Russia
- Global and Unknown markets show no ad until a separate non-Russian provider
  is implemented and approved
- an unknown market is not cached; an active game retries an unavailable
  banner with a bounded delay so routing can recover after connectivity returns
- the first configured launch asks whether personalized advertising is
  allowed; declining keeps advertising non-personalized, while closing the app
  before making a choice causes no advertising request
- changing that choice clears already loaded ads before the game requests a
  new banner, rewarded ad, or interstitial

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
- common email and Telegram verification rules are implemented and tested, but
  their delivery adapters and public endpoints are intentionally not active yet
- Yandex Mobile Ads SDK is connected as the owner's live adapter for banner,
  rewarded, and optional post-match formats; it becomes active only when the
  player has made a privacy choice and the backend returns `Russia`
- the backend market endpoint resolves the current numeric IP through a local
  country database and returns only a coarse market; nginx overwrites the
  client-IP header and the backend trusts it only from the configured proxy
- active-use time, completed matches, and games since the last presented
  interstitial are persisted locally and are not transferred through Android
  backup
- release and internal-distribution builds automatically validate the HTTPS
  backend origin plus required, distinct owner Yandex banner/rewarded IDs;
  post-match remains optional
- billing remains a local/debug stub and fails closed in release
- UI and local persistence use asynchronous provider callbacks; dismissal
  without the Yandex reward callback never grants coins or a hint
- temporary `Pro` is a local coin purchase rather than a billing-provider product
- code integration is complete; activation needs production deployment of the
  prepared backend/nginx/MMDB configuration. A separate Yandex post-match ID
  may be added later without blocking banner and rewarded placements.
