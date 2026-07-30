# Provider Setup

## Goal

Prepare provider adapters so Google, email, Telegram, ads, and billing can be
enabled without rewriting UI or game logic.

## Current Model

- provider ids are read from `local.properties`
- fallback defaults keep the app working in stub mode
- the canonical example file is `provider-config.example.properties`
- email and Telegram credentials are server-only environment secrets and never
  belong in `local.properties`, Android resources, or Git

## Provider Keys

- `provider.environment`
  - `sandbox` or `live`

- Google Play
  - `provider.googlePlay.webClientId`
  - `provider.googlePlay.serverClientId`
  - `provider.googlePlay.gamesProjectId`

- Ads
  - owner Yandex:
    - `provider.<variant>.ads.yandex.owner.banner.game`
    - `provider.<variant>.ads.yandex.owner.rewarded.general`
    - `provider.<variant>.ads.yandex.owner.interstitial.postMatch`
  - company AdMob:
    - `provider.<variant>.ads.admob.company.appId`
    - `provider.<variant>.ads.admob.company.banner.game`
    - `provider.<variant>.ads.admob.company.rewarded.general`
    - `provider.<variant>.ads.admob.company.interstitial.postMatch`
  - company Huawei:
    - `provider.<variant>.ads.huawei.company.appId`
    - `provider.<variant>.ads.huawei.company.banner.game`
    - `provider.<variant>.ads.huawei.company.rewarded.general`
    - `provider.<variant>.ads.huawei.company.interstitial.postMatch`
  - post-match cadence:
    - `provider.<variant>.ads.interstitial.minimumCompletedMatches`
    - `provider.<variant>.ads.interstitial.minimumForegroundSeconds`
    - `provider.<variant>.ads.interstitial.gamesBetweenImpressions`

- Billing
  - `provider.billing.removeAdsProductId`
  - `provider.billing.proSubscriptionId`
  - `provider.billing.proPlusSubscriptionId`

- Identity service / VPS secret store
  - Google OAuth web client ID
  - email delivery provider credentials and the email-code HMAC key
  - Telegram bot token and the provider-subject HMAC key

## Advertising Runtime

- `BuildConfig` fields are generated for provider ids
- AdMob app id is already wired through `manifestPlaceholders`
- the manifest already has the ad application meta-data entry
- the owner-first router and provider identities are shared by banner,
  rewarded, and post-match interstitial formats
- Android can preload eligible providers concurrently after consent
- market and SDK-availability contracts are isolated from game/UI code
- Yandex Mobile Ads SDK 8 is connected for owner banner, rewarded, and
  post-match interstitial inventory
- rewarded bonuses are granted only from the provider reward callback
- the synchronous compatibility service is no longer used by the live game UI
- default foreground threshold is `0`; choose and set the release value before
  publishing

## Backend Market Setup

Set both values in the backend process:

- `INPLACEX_AD_MARKET_COUNTRY_HEADER`
- `INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS`

The reverse proxy must determine the country from the current connection IP,
overwrite the named header, and connect from one of the exact trusted hosts.
The endpoint is `GET /api/v1/runtime/ad-market`. Missing, partial, forged, or
invalid input returns `UNKNOWN`, and Android shows no ad.

## Activation Path

1. configure the trusted reverse-proxy country header and Android HTTPS base URL
2. add the owner's real Yandex ids to variant-specific `local.properties`
3. set the release foreground-time and N-game cadence values
4. complete legal review of the displayed privacy wording and store disclosures
5. run Yandex demo placements in a debug build, then real placements on an
   allowlisted test device
6. add AdMob and distribution-specific Huawei company adapters without changing
   the fixed beneficiary identities or owner-first route

Email and Telegram activation additionally requires identity-service routes,
one-time challenge persistence, rate limiting, and a configured delivery
adapter. The shared verifier alone must not be presented as a live sign-in.
