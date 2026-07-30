# Provider Runtime Config

## Source of Provider IDs

- provider ids are resolved from `local.properties` during Android build and are scoped to a build variant
- an isolated worktree or CI runner may point to the same private-format file
  with `-PinplacexProviderConfigFile=<absolute-path>`; the file remains outside Git
- debug reads `provider.debug.*` keys and supplies Google test ad ids plus sandbox product defaults when those keys are absent
- release reads `provider.release.*` keys; absent values are empty and the release environment is always `live`
- `app/build.gradle.kts` exports those variant-specific values as `BuildConfig` string fields
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
- `YANDEX_OWNER_GAME_BANNER_AD_UNIT_ID`
- `YANDEX_OWNER_REWARDED_AD_UNIT_ID`
- `YANDEX_OWNER_POST_MATCH_INTERSTITIAL_AD_UNIT_ID`
- `HUAWEI_COMPANY_APP_ID`
- `HUAWEI_COMPANY_GAME_BANNER_AD_UNIT_ID`
- `HUAWEI_COMPANY_REWARDED_AD_UNIT_ID`
- `HUAWEI_COMPANY_POST_MATCH_INTERSTITIAL_AD_UNIT_ID`
- `ADS_INTERSTITIAL_MINIMUM_COMPLETED_MATCHES`
- `ADS_INTERSTITIAL_MINIMUM_FOREGROUND_SECONDS`
- `ADS_INTERSTITIAL_GAMES_BETWEEN_IMPRESSIONS`
- `BILLING_REMOVE_ADS_PRODUCT_ID`
- `BILLING_PRO_SUBSCRIPTION_ID`
- `BILLING_PRO_PLUS_SUBSCRIPTION_ID`

## Canonical Runtime Model

- `PlatformConfig.providers`
  - `ProviderConfig`
    - `environment`
    - `googlePlay`
    - `ads`
      - `ownerYandex`
      - `companyAdMob`
      - `companyHuawei`
      - `postMatchInterstitialPolicy`
    - `billing`

## Design Rule

- UI, repository, and gameplay depend on contracts and entitlements only
- provider ids are data, not hardcoded behavior
- beneficiary ownership is code-level policy, not local configuration:
  Yandex belongs to the owner, while AdMob and Huawei belong to the company
- real SDK services must consume `PlatformConfig.providers` and preserve the current contract surface

## Runtime Adapter Layer

- `ProviderServicesFactory.create(...)` is the canonical entry point
- the factory is selected by the compile-time Android variant, not by the runtime environment string
- debug returns stub services for local test flows
- Google sign-in uses Android Credential Manager when a variant-specific web
  client ID and online endpoint are configured; the returned ID token is not a
  local success and becomes authenticated only after the identity process
  verifies it and returns InplaceX credentials
- debug and release create the same real Yandex owner adapter when all three
  variant-specific Yandex placement ids are present
- Yandex Mobile Ads SDK automatic initialization is disabled in the manifest;
  initialization occurs only after a persisted `ACCEPTED` or `DECLINED`
  privacy choice
- `AndroidAdRuntime` resolves `/api/v1/runtime/ad-market` through the configured
  online HTTPS base URL, routes owner-first, and drives real Yandex callbacks
- banner, rewarded, and post-match UI call sites use `AndroidAdRuntime`; reward
  state changes only after the provider reports completion
- AdMob and Huawei have live Android fallback adapters for banner, rewarded,
  and post-match interstitial formats
- AdMob uses Google Mobile Ads 25 plus UMP 4; SDK initialization and any
  required consent form complete before its first preload
- Huawei uses Ads Kit 13 and receives the persisted personalization decision
  plus `requestLocation=false`
- billing continues to fail closed until its SDK integration exists
- a release artifact must not contain debug stub implementations or use a runtime `SANDBOX` value to select them

Absent provider configuration, Credential Manager cancellation, server
rejection, or network failure leaves the player in guest mode. Debug provider
stubs remain isolated tooling and are not used by the real Profile sign-in flow.

The preferred backend market source is a local MMDB configured through
`INPLACEX_AD_MARKET_DB_PATH`. A reverse proxy must overwrite
`INPLACEX_AD_MARKET_CLIENT_IP_HEADER`, and the backend accepts it only from
`INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS`. Direct numeric remote addresses are
resolved without a header. `INPLACEX_AD_MARKET_REQUIRED=true` makes missing or
ambiguous production configuration fail at startup. The older trusted country
header remains an exclusive compatibility mode.

Run `:app:validateReleaseAdsConfig` before a release. It checks the HTTPS backend
origin, requires all live Yandex placement IDs, and rejects partially configured
AdMob or Huawei groups without printing any configured value.
