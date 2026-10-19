# Sideload Update Contract

## Authority

Mirkori Games Platform is the only public release-catalog authority for the
first APK distribution. InplaceX queries:

```text
GET /api/v2/catalog/games/inplacex/updates?distributionId=rf-mirkori&channel=stable&versionCode=<installed>
```

The signed RF candidate binds the immutable `BuildConfig.MIRKORI_DISTRIBUTION_ID`
and installed package to the existing distribution-aware SDK contract. Google
Play distributions retain their store update flow. The legacy schema-1 catalog
API remains available in the shared SDK for existing consumers; the Android
installer does not use it. Changelog text uses the persisted app language at
catalog refresh. The client uses the installed `BuildConfig.VERSION_CODE`. It accepts schema
version `1` only and verifies the Platform policy locally:

- `updateAvailable=false` requires `required=false` and no release object;
- an available release must have a greater numeric `versionCode`;
- `required` must equal `installedVersionCode < minimumSupportedVersionCode`;
- `minimumAndroidSdk` is compared with the device SDK; an incompatible optional
  release can be dismissed, while an incompatible required release remains an
  explicit blocking `Unsupported` state;
- the release must belong to `inplacex`, `android`, and `stable`.
- every Android release must include exact `packageName` and a non-empty
  `signingCertificateSha256Fingerprints` array. Fingerprints use uppercase,
  colon-separated SHA-256 form and catalog order is the signing lineage from
  the oldest accepted signer to the current signer.

Only the signed candidate uses the in-app installer; it suppresses the separate
browser/store update dialog. Ordinary release and debug retain that existing
distribution update flow and their original localization keys.

The public catalog request is independent of the current guest or linked
account and carries no bearer token.

## Artifact URL Boundary

The signed-candidate Platform origin and download URL must use HTTPS. The
shared SDK retains an explicit loopback-only development policy for contract
tests, but the sideload factory always disables it and no debug variant enables
the updater.

The SDK and Android runtime both reject a download URL unless it:

- has the same scheme, host, and effective port as the configured Platform
  origin;
- has no user info, query, or fragment;
- exactly matches `/downloads/{encodedReleaseId}/{encodedFileName}`;
- describes the validated Android APK metadata and lowercase SHA-256 digest.

Only the `signedReleaseCandidate` variant enables this runtime. Debug, ordinary
release, and internal-distribution variants compile `SIDELOAD_UPDATES_ENABLED`
as `false` and contain neither `REQUEST_INSTALL_PACKAGES` nor the update
`FileProvider`. The candidate provider exposes only
`cache/app-updates/` through `${applicationId}.update-files`.

The candidate downloads into `cache/app-updates/{releaseId}.part` with a
dedicated HTTPS client that has finite timeouts, disables HTTP and HTTPS
redirects at both the Ktor and OkHttp-engine layers, asks for identity transfer
encoding, and requires status `200`, the
APK media type, exact `Content-Length`, and exact `X-Artifact-SHA256`. The
response channel is cancelled on every status or required-header rejection.
The stream is bounded by the manifest size, SHA-256 is calculated while
writing, the file is fsynced, and publication to `{releaseId}.apk` uses an
atomic rename followed by directory fsync. Partial, stale, symlinked,
oversized, short, or digest-mismatched files are removed and never reach the
installer.

Before an install intent exists, Android `PackageManager` archive metadata must
prove all of the following:

- package name equals both the catalog package and the installed application;
- version name/code and minSdk exactly equal the release, the code is greater
  than the installed version, and the APK is not debuggable;
- the APK has exactly one current signer;
- its normalized oldest-to-current signer history exactly equals the ordered
  Platform lineage and the singular current signer is the last entry;
- the installed app also has one current signer and its ordered lineage is an
  exact prefix of the candidate lineage.

Multi-current-signer APKs and unrelated key rotation fail closed. The cached
identity stores both the singular current signer and the complete ordered
lineage. On API 29+ `PackageManager` is the available runtime parsing and
signing-metadata boundary; InplaceX does not embed `apksig`. The Android system
package installer remains the final cryptographic signature/proof-of-rotation
enforcement boundary, in addition to the app's exact byte and metadata checks.

## Runtime And Lifecycle

The app checks after every `Activity.onStart`, including while an update dialog
is already open. The runtime is composed independently of guest/bootstrap/login
state and sends no bearer credential. One mutex serializes catalog refresh,
download, verification, optional dismissal, installer recovery, permission
return, replacement, revocation, and the one-shot claim that hands an exact
reverified artifact to Android. The mutex remains held through the synchronous
Android launch callback, so refresh/revocation/replacement and hand-off have one
linear order. A successful callback remains in non-actionable `HandingOff` until
the Activity returns; success, failure, and cancellation all release the runtime
claim. A visible ready artifact is non-actionable while an authoritative refresh
is running.

Network behavior uses the existing Mirkori transport: finite connect/request/
socket timeouts, at most two attempts by default, redirects disabled at both
Ktor and OkHttp-engine layers, a 64 KiB JSON response limit, and sanitized error
logging.

One atomically committed state document persists:

- current-version responses use a six-hour cooldown;
- transport or validation failures use a fifteen-minute retry cooldown;
- dismissing an optional release suppresses that exact release for 24 hours;
- required releases ignore optional dismissal state;
- persisted future timestamps beyond 48 hours are discarded as invalid.
- the complete currently required release and, after verification, its cached
  artifact identity (relative cache name, bytes, digest, package/version/minSdk,
  debug flag, current signer, and ordered lineage).

An absent policy document on first install is normal. An existing document that
is corrupt, unreadable, has an unknown schema, or contains a required release
that no longer validates under the compiled authority enters a blocking policy
recovery state. It is never interpreted as `Current`; only a successful
authoritative catalog response may replace that recovery marker.

Before every fresh authority request, the runtime first commits
`policyRecoveryRequired=true` while retaining the last policy as a durable
refresh tombstone. This applies even when no APK is cached, because a prior
required policy can otherwise download a revoked artifact after restart and a
new required response can otherwise be forgotten. A failed preflight commit
aborts the request, so the process never learns a required decision,
revocation, or replacement it cannot remember. After a valid response, the
final policy is also attempted with the recovery marker and without a cached
artifact, while no-follow cache deletion runs independently even if that second
commit fails. Only successful cleanup and a final atomic commit clear the
preflight tombstone. If the response-marker write and cleanup both fail, the
earlier tombstone still blocks restart/offline restoration. A non-authoritative
network/validation failure may atomically roll the preflight marker back to the
last policy; rollback failure remains blocking.

A required release is committed before it is presented. On process restart it
blocks game access before the first network result, including offline. A
transient catalog/download/verification error never clears it. A successful
authoritative response may replace or revoke it; installing that release also
clears the stale gate when the new process observes its greater installed
version. That process starts in blocking policy recovery, retains the installed
required release in its cleanup marker, physically invalidates the update
cache, and only then atomically removes the durable gate. A cleanup or final-
write failure therefore remains retryable across an offline restart instead of
orphaning the verified APK behind a cleared policy. Open prompts continue to
refresh, so replacement and revocation are not hidden by UI state.

The state machine is explicit: `Checking`, `Current`, `Optional`, `Required`,
`Downloading`, `Verifying`, `Refreshing`, `NeedsPermission`, `Ready`,
`HandingOff`, `Unsupported`, `RecoverableError`, and `PolicyRecovery`. An
incompatible required release remains a visible blocking `Unsupported` state
rather than becoming a generic unavailable result.

## Presentation

An optional update dialog has `Download and verify` and `Later`; dismissing it
leaves all game actions available. A required dialog cannot be dismissed by
tapping outside or pressing Back. Download and verification progress, unknown-
sources permission, ready-to-install, unsupported-device, and recoverable-error
states have localized Russian and English text.

After verification, Android 8+ unknown-source permission is requested for this
application through `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. Only then does the app
create a one-time read grant for the narrow `FileProvider` URI and launch the
system package installer.

The dialog renders the catalog version, artifact size, and release changelog in
a bounded scrollable area. Russian and English entries live in the normal
localization catalogs.

## Verification

- `:Mirkori-platform-game-sdk:test` covers exact JSON, update semantics, GET
  shape, auth absence, HTTPS/same-origin policy, and debug loopback.
- `:app:testDebugUnitTest` covers durable required restart/outage behavior,
  claim-first and refresh-first replacement/revocation races, one-shot handoff,
  unknown-sources return, authoritative cleanup failure, response-marker write
  failure with both successful and failed physical deletion across restart,
  no-cache first-required/replacement/revocation recovery across offline
  restart, installed-version cleanup retry across offline restart,
  corrupt-policy recovery, atomic state round-trip, bounded streaming cleanup,
  response-channel cancellation for every required APK header mismatch,
  no-follow stale directory/symlink removal, publication cleanup failure, exact
  package/signing-lineage policy, unsupported state, dismissal, and UI
  formatting.
- Executable MockEngine tests prove that both the Platform transport and APK
  downloader stop at the first `3xx`; the redirect target receives no request.
- `:app:testReleaseUnitTest` proves ordinary release keeps the runtime disabled
  and cannot enable a cleartext Platform origin.
- `scripts/ci/validate_sideload_update_contract.py` proves the permission,
  provider path, build gate, no-redirect client, public-runtime composition,
  serialized installer claim, fail-closed policy recovery, safe cache cleanup,
  and absence of the legacy browser updater. CI additionally checks the merged
  debug and release manifests for absence of the installer capability, runs the
  signed-candidate unit suite with a freshly generated CI-only signing identity,
  and checks its merged manifest for the exact non-exported provider boundary.
- Android instrumentation covers optional/required dialog actions and proves
  debug contains no installer permission. CI never publishes its ephemeral
  candidate identity or builds a distributable candidate; the real signed APK
  remains available only through the external production/signing gates.
