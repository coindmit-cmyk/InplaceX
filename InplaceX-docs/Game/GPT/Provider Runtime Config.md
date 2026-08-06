# Provider Runtime Config

## Source of Provider IDs

- provider ids are resolved from `local.properties` during Android build and are scoped to a build variant
- debug reads `provider.debug.*` keys and supplies Google test ad ids plus sandbox product defaults when those keys are absent
- release reads `provider.release.*` keys; absent values are empty and the release environment is always `live`
- `app/build.gradle.kts` exports those variant-specific values as `BuildConfig` string fields
- the Mirkori Games platform origin uses `platform.debug.baseUrl` or
  `platform.release.baseUrl`; both default to `https://games.dmit.life`
- the ads app id also flows into `AndroidManifest.xml` via variant-specific `manifestPlaceholders`
- shared `defaultConfig` contains no sandbox provider mode, test ad id, or mock billing product default

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
- `MIRKORI_PLATFORM_BASE_URL`
- `MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK`

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
- the factory is selected by the compile-time Android variant, not by the runtime environment string
- debug returns stub services for local test flows
- Google sign-in uses Android Credential Manager when a variant-specific web
  client ID and online endpoint are configured; the returned ID token is not a
  local success and becomes authenticated only after the identity process
  verifies it and returns InplaceX credentials
- release returns `GooglePlayAuthService`, `AdMobService`, and
  `GooglePlayBillingService`; ads and billing continue to fail closed until
  their SDK integrations exist
- a release artifact must not contain debug stub implementations or use a runtime `SANDBOX` value to select them

Absent provider configuration, Credential Manager cancellation, server
rejection, or network failure leaves the player in guest mode. Debug provider
stubs remain isolated tooling and are not used by the real Profile sign-in flow.

Mirkori Games login is independent from the legacy Google Play/online identity
adapter. Release requires an HTTPS platform origin. Debug may explicitly use
cleartext only for a loopback host and still uses the registered HTTPS App Link
callback.
