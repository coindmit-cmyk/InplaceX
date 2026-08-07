# Provider Runtime Config

## Source of Provider IDs

- provider ids are resolved from `local.properties` during Android build and are scoped to a build variant
- an isolated worktree or CI runner may point to the same private-format file
  with `-PinplacexProviderConfigFile=<absolute-path>`; the file remains outside Git
- debug reads `provider.debug.*` keys and supplies sandbox product defaults when those keys are absent
- release reads `provider.release.*` keys; absent values are empty and the release environment is always `live`
- `app/build.gradle.kts` exports those variant-specific values as `BuildConfig` string fields
- the Mirkori Games platform origin uses `platform.debug.baseUrl` or
  `platform.release.baseUrl`; both default to `https://games.dmit.life`
- shared `defaultConfig` contains no sandbox provider mode, test ad id, or mock billing product default

## Canonical BuildConfig Fields

- `PROVIDER_ENVIRONMENT`
- `GOOGLE_PLAY_WEB_CLIENT_ID`
- `GOOGLE_PLAY_SERVER_CLIENT_ID`
- `GOOGLE_PLAY_GAMES_PROJECT_ID`
- `YANDEX_OWNER_GAME_BANNER_AD_UNIT_ID`
- `YANDEX_OWNER_REWARDED_AD_UNIT_ID`
- `YANDEX_OWNER_POST_MATCH_INTERSTITIAL_AD_UNIT_ID`
- `ADS_INTERSTITIAL_MINIMUM_COMPLETED_MATCHES`
- `ADS_INTERSTITIAL_MINIMUM_FOREGROUND_SECONDS`
- `ADS_INTERSTITIAL_GAMES_BETWEEN_IMPRESSIONS`
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
      - `ownerYandex`
      - `postMatchInterstitialPolicy`
    - `billing`

## Design Rule

- UI, repository, and gameplay depend on contracts and entitlements only
- provider ids are data, not hardcoded behavior
- beneficiary ownership is code-level policy, not local configuration; the
  active Yandex inventory belongs to the owner
- real SDK services must consume `PlatformConfig.providers` and preserve the current contract surface

## Runtime Adapter Layer

- `ProviderServicesFactory.create(...)` is the canonical entry point
- the factory is selected by the compile-time Android variant, not by the runtime environment string
- debug returns stub services for local test flows
- Google sign-in uses Android Credential Manager when a variant-specific web
  client ID and online endpoint are configured; the returned ID token is not a
  local success and becomes authenticated only after the identity process
  verifies it and returns InplaceX credentials
- debug and release create the same real Yandex owner adapter when at least one
  variant-specific Yandex placement is present; release separately requires
  banner and rewarded ids
- Yandex Mobile Ads SDK automatic initialization is disabled in the manifest;
  initialization occurs only after a persisted `ACCEPTED` or `DECLINED`
  privacy choice
- `AndroidAdRuntime` resolves `/api/v1/runtime/ad-market` through the configured
  online HTTPS base URL and routes Yandex only for `RUSSIA`; `GLOBAL` and
  `UNKNOWN` fail closed until another provider is explicitly implemented
- banner, rewarded, and post-match UI call sites use `AndroidAdRuntime`; reward
  state changes only after the provider reports completion
- billing continues to fail closed until its SDK integration exists
- a release artifact must not contain debug stub implementations or use a runtime `SANDBOX` value to select them

Absent provider configuration, Credential Manager cancellation, server
rejection, or network failure leaves the player in guest mode. Debug provider
stubs remain isolated tooling and are not used by the real Profile sign-in flow.

Mirkori Games login is independent from the legacy Google Play/online identity
adapter. Release requires an HTTPS platform origin. Debug may explicitly use
cleartext only for a loopback host and still uses the registered HTTPS App Link
callback.

The preferred backend market source is a local MMDB configured through
`INPLACEX_AD_MARKET_DB_PATH`. A reverse proxy must overwrite
`INPLACEX_AD_MARKET_CLIENT_IP_HEADER`, and the backend accepts it only from
`INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS`. Direct numeric remote addresses are
resolved without a header. `INPLACEX_AD_MARKET_REQUIRED=true` makes missing or
ambiguous production configuration fail at startup. The older trusted country
header remains an exclusive compatibility mode.

`preReleaseBuild` and `preInternalDistributionBuild` automatically depend on
`:app:validateReleaseAdsConfig`. It checks a strict HTTPS backend origin,
requires the live Yandex banner and rewarded placement IDs, and verifies that
all configured Yandex placement IDs are distinct without printing any value.
The post-match interstitial is optional and fails closed when it is absent.
