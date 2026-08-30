# Auth, Ads, and Billing

## Mirkori Games Account And Online Identity

- local save remains the primary source of truth
- offline play does not require a linked account
- when online identity is needed, the game restores or creates a Mirkori Games
  guest profile with one stable InplaceX player ID
- the Profile action opens the Mirkori Games connection page in the system
  browser; WebView is not used
- the separate Google action opens Android's native account chooser instead of
  the Mirkori website, then links the selected Google account through Mirkori
  Games Platform without changing the existing InplaceX player ID
- linking Google, Telegram, or a website account keeps the same InplaceX game
  profile and does not replace local campaign progress
- if the chosen Google account already has another InplaceX profile, the game
  asks whether to enter that profile or remain in the current one. Nothing is
  switched automatically, neither profile is deleted or merged, and the local
  campaign remains on the phone
- online matches use the refreshed Mirkori game token directly; the InplaceX
  backend verifies it and does not create a second player identity

Until cloud save has an authoritative merge/reconciliation flow, Android platform backup is
disabled. Restoring the SQLite profile without the device-bound Keystore session could otherwise
show a linked account or local premium flags without valid backend credentials. Progress restore
must go through the future authenticated cloud-save contract, not an implicit device backup.

## Google, Email, And Telegram Providers

- production provider login is owned by Mirkori Games Platform; Mirkori,
  Telegram, and website-account linking use its browser connection page, while
  the dedicated Google button uses Android's native account chooser
- Android forwards the one-time Google ID token directly to Mirkori Games
  Platform and does not put it in a URL or log; the InplaceX online backend
  never receives raw Google or Telegram credentials and never verifies them
- provider availability depends on the Platform deployment and its own
  provider configuration
- the former Credential Manager plus InplaceX VPS identity-service flow and the
  shared direct-provider verification module remain debug/test or historical
  compatibility only; production Credential Manager exchange now terminates at
  Mirkori Games Platform instead
- Profile groups Mirkori Games and Google actions under one Connections section
- a Google-linked profile can disconnect Google Credential Manager on this
  device and reconnect it later; this does not sign out of Mirkori Games or
  clear the Platform session
- full Mirkori Games logout remains unavailable until the Platform can revoke
  the server refresh session before encrypted Platform credentials are cleared

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
- the owner's Yandex inventory is temporarily used in every market until a
  separate international provider is connected
- until a separate non-Russian provider is implemented and approved, the
  owner's Yandex inventory is the temporary route in every market
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
  - cannot be bought while Platform `Pro` or `Pro+` access is active

- `Remove Ads`
  - one-time purchase
  - disables banner ads and post-match ads

- `Pro`
  - prepaid access for the fixed term shown in the Platform offer
  - one-time checkout with no automatic renewal
  - enables auto table mode
  - removes banner ads and post-match ads

- `Pro+`
  - prepaid access for the fixed term shown in the Platform offer
  - one-time checkout with no automatic renewal
  - includes everything in `Pro`
  - disables all ads
  - grants infinite hints
  - includes Pro access, so the lower Pro purchase is unavailable while Pro+
    is active

Platform-paid products are sold by Mirkori Games Platform, not Google Play Billing.
The shop requires a linked Mirkori account, shows the Platform price, and opens
only the Platform's HTTPS payment page in the system browser. Returning from
the browser does not grant the product: the game checks the order and then
waits for the matching server entitlement. This prevents a redirect, local
flag, or interrupted payment from looking like a successful purchase.

Reinstalling the app or opening the same linked profile on another device first
restores exactly one unfinished server order through the dedicated pending-order
endpoint, not purchase history. If the server state is ambiguous,
the game blocks a new payment instead of risking a duplicate charge. Repricing
or removing an offer does not change an already started order and does not
revoke a server-confirmed entitlement.

If the device is offline, the payment provider is temporarily unavailable, or
the payment was cancelled, the shop keeps a truthful retry/status action.
Retries reuse the already created purchase attempt. A confirmed time-limited
entitlement can remain available offline only until its confirmed expiry time.
That countdown uses trusted server time and the device monotonic clock; changing
the date/time cannot extend it, and a reboot requires an online refresh before
timed access becomes active again.
Switching the linked Platform account/profile clears that cached purchase
state. The one-hour temporary `Pro` bought with coins stays completely local
and separate from these permanent products.

## Current Stage

- production account linking and online identity are connected through the
  Mirkori Games SDK, browser/PKCE plus native Google flow, rotating Platform
  credentials, and the stable game-scoped player ID
- the InplaceX backend accepts only verified Mirkori game tokens for release
  online routes; direct Google bootstrap, provider-token verification, and
  backend JWT issuing remain debug/test compatibility rather than deployment
  requirements
- Yandex Mobile Ads SDK is connected as the owner's live adapter for banner,
  rewarded, and optional post-match formats; until another provider is added,
  it becomes active in every market only after the player has made a privacy
  choice
- the backend market endpoint resolves the current numeric IP through a local
  country database and returns only a coarse market; nginx overwrites the
  client-IP header and the backend trusts it only from the configured proxy
- active-use time, completed matches, and games since the last presented
  interstitial are persisted locally and are not transferred through Android
  backup
- release and internal-distribution builds automatically validate the HTTPS
  backend origin plus required, distinct owner Yandex banner/rewarded IDs;
  post-match remains optional
- Mirkori Games Platform billing is connected in release for catalog, external
  HTTPS checkout, order polling, and server-authoritative entitlements; release
  fails closed when the Platform or product configuration is unavailable
- UI and local persistence use asynchronous provider callbacks; dismissal
  without the Yandex reward callback never grants coins or a hint
- temporary `Pro` is a local coin purchase rather than a billing-provider product
- code integration is complete; activation needs production deployment of the
  prepared backend/nginx/MMDB configuration and active Platform product/payment
  configuration. A separate Yandex post-match ID may be added later without
  blocking banner and rewarded placements.
