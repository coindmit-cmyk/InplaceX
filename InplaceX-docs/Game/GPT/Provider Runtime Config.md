# Provider Runtime Config

## Source of Provider IDs

- provider ids are resolved from `local.properties` during Android build and are scoped to a build variant
- an isolated worktree or CI runner may point to the same private-format file
  with `-PinplacexProviderConfigFile=<absolute-path>`; the file remains outside Git
- debug reads `provider.debug.*` keys and supplies sandbox product defaults when those keys are absent
- debug/sandbox always enables banner, rewarded, and interstitial through the
  corresponding official `demo-*-yandex` placements. A clean worktree and CI
  build therefore exercise test advertising without `local.properties`;
  configured release placement values are never used by this environment
- consent and placement UI gates consume the factory's effective provider
  availability rather than the raw variant input, so a clean debug install
  requests privacy choice and exposes the same demo inventory as its runtime
- release reads `provider.release.*` provider keys; the release environment is
  always `live`, while commerce product identity is compiled from canonical
  constants rather than mutable local configuration
- `app/build.gradle.kts` exports those variant-specific values as `BuildConfig` string fields
- the Mirkori Games platform origin uses `platform.debug.baseUrl` or
  `platform.release.baseUrl`; the runtime default is `https://games.dmit.life`,
  while a production candidate requires an explicit release origin
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

## Backend production secret inputs

The InplaceX backend production composition accepts secret material only from
mounted files:

- `INPLACEX_DATABASE_PASSWORD_PATH`;
- `INPLACEX_ONLINE_PUBLIC_KEY_X509_BASE64_PATH`;
- `INPLACEX_ONLINE_STATE_KEY_BASE64_PATH`.

Their inline counterparts remain development/test compatibility and must not
appear in a production manifest. The Compose release contract mounts all three
under `/run/secrets`, adds the numeric `INPLACEX_RUNTIME_SECRET_GID` to both
non-root containers, and proves readability from the actual PID 1 UID. Host
directory/files are respectively `root:GID` modes `0750`/`0640`.

`INPLACEX_DATABASE_JDBC_URL` contains only the PostgreSQL endpoint and database.
User info, query options and fragments are rejected; username and password use
their explicit fields. `INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK` is empty
normally and has one documented exact value only for the guarded v1-v8 checksum
recovery in the production runbook.

## Design Rule

- UI, repository, and gameplay depend on contracts and entitlements only
- provider ids are data, except the three public release commerce IDs whose
  stability is part of the persisted-purchase contract
- beneficiary ownership is code-level policy, not local configuration; the
  active Yandex inventory belongs to the owner
- real SDK services must consume `PlatformConfig.providers` and preserve the current contract surface

## Runtime Adapter Layer

- `ProviderServicesFactory.create(...)` is the canonical entry point
- the factory is selected by the compile-time Android variant, not by the runtime environment string
- debug returns stub services for local test flows
- production account linking, including Google and Telegram identities, uses
  the Mirkori Games browser/PKCE flow. The Platform returns a refreshed
  game-scoped identity with the same stable `gamePlayerId`; InplaceX never
  exchanges a provider token for a second online identity
- debug and release create the same real Yandex owner adapter. Debug/sandbox
  registers it with official demo placements even when local provider keys are
  absent; release requires live banner and rewarded ids
- Yandex Mobile Ads SDK automatic initialization is disabled in the manifest;
  initialization occurs only after a persisted `ACCEPTED` or `DECLINED`
  privacy choice
- release `AndroidAdRuntime` resolves `/api/v1/runtime/ad-market` through the
  configured online HTTPS base URL; until another provider is explicitly
  implemented, Yandex is the temporary route for `RUSSIA`, `GLOBAL`, and
  `UNKNOWN`
- debug/sandbox deliberately bypasses backend market detection and routes as
  `RUSSIA`, so the official Yandex demo placements can be exercised from any
  developer network; debug/live retains the backend market resolver
- banner, rewarded, and post-match UI call sites use `AndroidAdRuntime`; reward
  state changes only after the provider reports completion
- billing uses the typed Mirkori Platform SDK/runtime for catalog, checkout,
  order polling, and entitlements; no Google Play Billing adapter is composed
- release product IDs are immutable Platform IDs:
  `inplacex.remove_ads`, `inplacex.pro`, and `inplacex.pro_plus`. Debug IDs
  remain configurable for sandbox catalogs
- optional legacy `provider.release.billing.*` assertions must equal those
  canonical IDs exactly; the production gate rejects rotation and surrounding
  whitespace so a persisted pending purchase never changes identity
- a release artifact must not contain debug stub implementations or use a runtime `SANDBOX` value to select them

Absent provider configuration, Credential Manager cancellation, server
rejection, or network failure leaves the player in guest mode. Debug provider
stubs remain isolated tooling and are not used by the real Profile sign-in flow.

Mirkori Games login is the release identity authority for the online runtime.
The legacy InplaceX guest/Google adapter remains source-level debug/test
compatibility and is not instantiated by release online composition. Release
requires an HTTPS platform origin. Debug may explicitly use cleartext only for
a loopback host and still uses the registered HTTPS App Link callback.

The preferred backend market source is a local MMDB configured through
`INPLACEX_AD_MARKET_DB_PATH`. A reverse proxy must overwrite
`INPLACEX_AD_MARKET_CLIENT_IP_HEADER`, and the backend accepts it only from
`INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS`. Direct numeric remote addresses are
resolved without a header. `INPLACEX_AD_MARKET_REQUIRED=true` makes missing or
ambiguous production configuration fail at startup. The older trusted country
header remains an exclusive compatibility mode.

Ordinary `:app:assembleRelease` is deliberately allowed to produce an unsigned
artifact for PR compilation and static checks. The distribution-only
`:app:releaseCandidate` task depends on `:app:validateProductionReleaseConfig`
and `:app:validateReleaseSigningConfig`. It checks strict HTTPS origins for the
online backend and Mirkori Games Platform, requires live Yandex banner and
rewarded placement IDs, verifies that configured Yandex IDs are distinct, and
compiles the three canonical Mirkori Platform product IDs and rejects any
conflicting optional legacy assertions without printing configured values. The post-match interstitial is optional and fails closed
when absent.

Android version identity is centralized in
`InplaceX-android/version.properties`. Release signing is accepted only as a
complete external properties file (`-PinplacexReleaseSigningFile=...`) or the
five `INPLACEX_RELEASE_*` environment variables, including the mandatory
owner-approved `INPLACEX_RELEASE_EXPECTED_CERT_SHA256`. Partial signing fails
during Gradle configuration. The separate `signedReleaseCandidate` build type
uses that key, and `releaseCandidate` compares the real APK signer certificate
to the expected fingerprint through `apksigner`. Ordinary release and
internal-distribution variants remain unsigned even when the signing config is
present; no production-like variant falls back to the debug key.

Verified production bundles are atomically published under
`build/release-candidates/<releaseId>`. The ID follows the Mirkori catalog
pattern `[a-z0-9][a-z0-9._-]{1,63}`. Reusing an ID with another APK SHA-256 or
with a stale/incomplete directory is rejected without modifying the existing
bundle.

The verified candidate is converted to a complete, immutable Mirkori Platform
catalog snapshot by `ops/release/build_platform_catalog_release.py`. Routine
publication requires an export of Platform's exact resolved active `current`
catalog as its base, never a remembered copy or `backup`. The builder preserves
that supplied snapshot but cannot prove server activation state; the Platform
publisher independently requires the candidate to retain the active games,
releases, and artifacts. An empty base is available only through the explicit
one-time `--allow-empty-base` bootstrap flag.

The supported Gradle `buildPlatformCatalogRelease` workflow depends on
`:app:releaseCandidate` and the opt-in `testPlatformReleaseContract`, derives its
exact versioned directory, and passes the current full Git commit as mandatory
`--expected-commit`. It also requires a clean Platform checkout, exact Platform
commit, and exact SHA-256 of its tracked `ops/catalog_release_tool.py`. The
builder rechecks both Git identities, the candidate identity bundle, checksum,
package, version, non-debuggable status, signer report, certificate fingerprint,
and Platform schema, then executes a private byte-identical copy of that exact
validator before publishing through a same-filesystem staging directory.

The output parent must already exist and must not traverse a link, mount, NTFS
junction, or other reparse point. Validation never creates a missing parent. On
Windows, checked directory chains are pinned with non-delete-sharing handles and
their filesystem identities are revalidated at every publication boundary; an
unavailable lock fails closed.

The exact catalog layout remains `catalog.json` plus `artifacts/**`. A separate
immutable `<catalog-output>.provenance` sibling contains canonical JSON and its
SHA-256, binding the exact InplaceX commit/APK/certificate/catalog hash to the
exact Platform commit and validator hash. Its `activationProof` is always
`false`: Platform must verify and durably retain the attestation beside its
activation state, then create separate evidence only after activation, restart,
and live/public HTTPS smoke checks.

The exact reviewed Mirkori Platform `catalog_release_tool.py` remains the final
authority: it independently verifies the real APK and complete catalog before
server publication. The public `/.well-known/assetlinks.json` response is
derived from the activated catalog rather than maintained as a separate mutable
file. Adding the candidate certificate to `androidAppLink` is declarative only:
the builder never edits or overrides Platform's external root-owned catalog
trust policy. That policy must preapprove the exact InplaceX package and every
declared certificate; an intentional rotation requires an approved old/new
overlap before publication.
