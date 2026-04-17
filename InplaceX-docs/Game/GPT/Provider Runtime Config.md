# Provider Runtime Config

## Source of Provider IDs

- provider ids are resolved from `local.properties` during Android build
- `app/build.gradle.kts` exports them as `BuildConfig` string fields
- ads app id also flows into `AndroidManifest.xml` via `manifestPlaceholders`

## Canonical BuildConfig Fields

- `PROVIDER_ENVIRONMENT`
- `GOOGLE_PLAY_WEB_CLIENT_ID`
- `GOOGLE_PLAY_SERVER_CLIENT_ID`
- `GOOGLE_PLAY_GAMES_PROJECT_ID`
- `ADMOB_APP_ID`
- `ADMOB_GAME_BANNER_AD_UNIT_ID`
- `ADMOB_REWARDED_AD_UNIT_ID`
- `ADMOB_POST_MATCH_INTERSTITIAL_AD_UNIT_ID`
- `BILLING_REMOVE_ADS_PRODUCT_ID`
- `BILLING_PRO_SUBSCRIPTION_ID`
- `BILLING_PRO_PLUS_SUBSCRIPTION_ID`

## Canonical Runtime Model

- `PlatformConfig.providers`
  - `ProviderConfig`
    - `environment`
    - `googlePlay`
    - `ads`
    - `billing`

## Design Rule

- UI, repository, and gameplay depend on contracts and entitlements only
- provider ids are data, not hardcoded behavior
- real SDK services must consume `PlatformConfig.providers` and preserve the current contract surface

## Runtime Adapter Layer

- `ProviderServicesFactory.create(...)` is the canonical entry point
- `SANDBOX` environment returns stub services
- `LIVE` environment returns SDK-ready adapters:
  - `GooglePlayAuthService`
  - `AdMobService`
  - `GooglePlayBillingService`

These adapters are intentionally scaffolds right now. Later implementation should fill in SDK calls without changing the app-facing interfaces.
