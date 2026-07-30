# Auth, Ads, and Billing

## Google Play Auth

- local save remains the primary source of truth
- Google Play is the first auth and cloud-save provider
- sign-in is optional for offline play
- sign-in unlocks future cloud sync and multi-device restore
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
- the owner's Yandex inventory always has first display priority
- a configured company provider may be used only when the owner's provider is
  not ready, unavailable, or returns a failure and that company SDK supports
  the current market/device; AdMob is excluded in Russia
- if the owner ad was shown and then dismissed, company fallback is not used
- an unknown market shows no ad
- SDKs may prepare ads in parallel after privacy consent so fallback does not
  add avoidable waiting
- the first configured launch asks whether personalized advertising is
  allowed; declining keeps advertising non-personalized, while closing the app
  before making a choice causes no advertising request

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
  rewarded, and post-match formats; it becomes active only when the build has
  all three owner placement IDs, the player has made a privacy choice, and the
  backend returns a known market
- the backend market endpoint resolves the current numeric IP through a local
  country database and returns only a coarse market; nginx overwrites the
  client-IP header and the backend trusts it only from the configured proxy
- active-use time, completed matches, and games since the last presented
  interstitial are persisted locally and are not transferred through Android
  backup
- AdMob and Huawei company fallback adapters are connected for banner,
  rewarded, and post-match formats; they are used only after owner Yandex is
  unavailable and only in a supported market/device
- AdMob also runs Google's UMP privacy flow, and both company SDKs use
  non-personalized requests after a declined personalization choice
- billing remains a local/debug stub and fails closed in release
- UI and local persistence use asynchronous provider callbacks; dismissal
  without the Yandex reward callback never grants coins or a hint
- temporary `Pro` is a local coin purchase rather than a billing-provider product
- code integration is complete; activation needs the remaining provider app/ad
  unit IDs, the separate Yandex post-match interstitial ID, and production
  deployment of the prepared backend/nginx configuration
