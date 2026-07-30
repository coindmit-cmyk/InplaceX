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
  - `provider.ads.admobAppId`
  - `provider.ads.banner.game`
  - `provider.ads.rewarded.general`
  - `provider.ads.interstitial.postMatch`

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

## Future Activation Path

1. add real ids to `local.properties`
2. switch `provider.environment` to `live`
3. fill the SDK-ready adapter classes with real platform calls
4. keep the same UI flows and repository contracts

Email and Telegram activation additionally requires identity-service routes,
one-time challenge persistence, rate limiting, and a configured delivery
adapter. The shared verifier alone must not be presented as a live sign-in.
