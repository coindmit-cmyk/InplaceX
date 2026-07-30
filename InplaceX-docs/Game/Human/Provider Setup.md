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

## Keys To Add Later

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

- Billing
  - `provider.billing.removeAdsProductId`
  - `provider.billing.proSubscriptionId`
  - `provider.billing.proPlusSubscriptionId`

- Identity service / VPS secret store
  - Google OAuth web client ID
  - email delivery provider credentials and the email-code HMAC key
  - Telegram bot token and the provider-subject HMAC key

## Android Preparation Already Done

- `BuildConfig` fields are generated for provider ids
- AdMob app id is already wired through `manifestPlaceholders`
- the manifest already has the ad application meta-data entry
- the owner-first router and provider identities are shared by banner,
  rewarded, and post-match interstitial formats
- Android can preload eligible providers concurrently after consent
- market and SDK-availability contracts are isolated from game/UI code

## Future Activation Path

1. add the trusted backend IP-to-coarse-market response
2. integrate consent before any provider initialization or preload
3. add real ids to the variant-specific `local.properties` keys
4. add Yandex, Google, and only the required distribution-specific Huawei SDK
   adapters
5. replace fail-closed providers with callback-verified asynchronous adapters
6. migrate UI calls from synchronous `AdService` to `AndroidAdRuntime`
7. keep the same placement, entitlement, owner-priority, and reward contracts

Email and Telegram activation additionally requires identity-service routes,
one-time challenge persistence, rate limiting, and a configured delivery
adapter. The shared verifier alone must not be presented as a live sign-in.
