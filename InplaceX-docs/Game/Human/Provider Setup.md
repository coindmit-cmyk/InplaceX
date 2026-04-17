# Provider Setup

## Goal

Prepare the client so real Google Play auth, ads, and billing can be enabled later by adding ids and keys, without rewriting UI or game logic.

## Current Model

- provider ids are read from `local.properties`
- fallback defaults keep the app working in stub mode
- the canonical example file is `provider-config.example.properties`

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

## Android Preparation Already Done

- `BuildConfig` fields are generated for provider ids
- AdMob app id is already wired through `manifestPlaceholders`
- the manifest already has the ad application meta-data entry

## Future Activation Path

1. add real ids to `local.properties`
2. switch `provider.environment` to `live`
3. fill the SDK-ready adapter classes with real platform calls
4. keep the same UI flows and repository contracts
