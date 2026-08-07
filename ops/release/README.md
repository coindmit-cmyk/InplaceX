# InplaceX release distribution

`build_platform_catalog_release.py` converts the immutable directory produced by
`:app:releaseCandidate` into the exact catalog snapshot consumed by Mirkori Games
Platform. It does not sign an APK, upload files, publish a catalog, or handle
credentials. It is a deterministic transformer and validator orchestrator, not
a certificate trust root or an activation authority.

Python 3.9+ and Git are required. The output parent must already exist, must not
traverse a symlink, NTFS junction, mount/reparse point, or hard-linked file, and
must be protected from untrusted writers. The builder never creates a missing
output parent. On Windows it holds non-delete-sharing handles for the checked
directory chains and repeatedly verifies their filesystem identity; an
unavailable boundary lock fails closed.

## Active base and exact Platform validator

Routine publication requires an export of Platform's exact resolved active
`current` catalog as `--base-release-dir`, never a remembered local copy or
`backup`. The builder cannot discover server activation state by itself. The
Platform publisher must independently compare the candidate with its live
`current` and reject removal or mutation of active games, releases, artifacts,
package names, or certificate overlap. `--allow-empty-base` is reserved for a
separately reviewed first bootstrap.

The supported workflow requires all three Platform validator inputs:

- a clean Platform Git checkout directory;
- its exact full lowercase commit;
- the lowercase SHA-256 of `ops/catalog_release_tool.py` at that commit.

The workflow verifies `HEAD`, clean status, tracked tool bytes, Git-object bytes,
tool SHA-256, and schema constants. It then executes a private byte-for-byte copy
of that exact validator against the generated catalog and the supplied previous
catalog before publishing the output. A mutable `PATH` lookup or an unpinned
Platform checkout is never accepted.

The contract can be checked without signing or building an APK:

```powershell
$platformRepo = 'D:\Work\DevOps\MobileGame\MirkoriGamesPlatform-worktrees\platform-production-ops-v1-20260807'
$platformCommit = git -C $platformRepo rev-parse HEAD
$validatorSha = (Get-FileHash -Algorithm SHA256 "$platformRepo\ops\catalog_release_tool.py").Hash.ToLowerInvariant()

.\gradlew.bat testPlatformReleaseContract --no-configuration-cache `
  "-PinplacexPlatformRepositoryDir=$platformRepo" `
  "-PinplacexPlatformExpectedCommit=$platformCommit" `
  "-PinplacexPlatformValidatorSha256=$validatorSha"
```

This task is intentionally opt-in and fails when any property is absent. The
hermetic `testReleaseDistribution` task does not depend on another repository.
The production `buildPlatformCatalogRelease` task always depends on both the
opt-in contract check and `:app:releaseCandidate`, so production cannot bypass
either signing or the exact cross-repository contract.

## Routine build

Pre-create the trusted output parent and configure external release signing
before running the aggregate workflow:

```powershell
New-Item -ItemType Directory -Path D:\secure\inplacex-platform-releases -ErrorAction Stop

.\gradlew.bat buildPlatformCatalogRelease --no-configuration-cache `
  "-PinplacexPlatformRepositoryDir=$platformRepo" `
  "-PinplacexPlatformExpectedCommit=$platformCommit" `
  "-PinplacexPlatformValidatorSha256=$validatorSha" `
  -PinplacexPlatformCatalogBaseReleaseDir=D:\secure\mirkori-platform-current-catalog `
  -PinplacexPlatformCatalogOutputDir=D:\secure\inplacex-platform-releases\catalog-inplacex-1.0-1 `
  -PinplacexPlatformCatalogMinimumSupportedVersionCode=1 `
  -PinplacexPlatformCatalogPublishedAt=2026-08-07T12:00:00Z `
  "-PinplacexPlatformCatalogChangelog=Первый ограниченный релиз."
```

The task derives the exact candidate directory from canonical Android version
properties and requires its identity manifest to contain the current full
InplaceX `HEAD`. Missing or partial signing configuration still fails inside
`:app:releaseCandidate`; ordinary release/internal builds remain unsigned.

## Immutable provenance bundle

The exact Platform catalog layout remains only:

```text
<catalog-output>/catalog.json
<catalog-output>/artifacts/**
```

After the exact Platform validator succeeds, the builder publishes a separate
immutable sibling directory `<catalog-output>.provenance` containing canonical
`release-provenance.json` and `release-provenance.json.sha256`. The attestation
binds:

- exact InplaceX commit, package, release ID, version and certificate;
- APK file name, size and SHA-256;
- candidate and previous catalog manifest SHA-256;
- exact Platform repository commit, validator relative path and tool SHA-256;
- `validationStatus=passed` and `activationProof=false`.

Canonical JSON uses UTF-8, sorted keys, compact separators, and one trailing LF.
The checksum file is `<sha256><two spaces>release-provenance.json<LF>`. Reusing
either catalog or provenance path is accepted only for byte-identical content.

This attestation is **not** proof of server publication, activation, service
restart, or public HTTPS health. The Platform publisher must implement the
following fail-closed contract before InplaceX release activation:

1. require both the exact catalog directory and provenance sibling;
2. verify canonical JSON and its checksum, then recompute `catalog.json` SHA-256;
3. verify the exact Platform checkout commit/tool SHA and run that tool in
   production validation mode against live `current` and the candidate;
4. match the selected InplaceX release and real APK package, size, SHA, signer
   certificate, release ID and version to the attestation;
5. verify the attested InplaceX commit is the owner-approved source commit for
   that exact APK SHA;
6. atomically preserve the attestation JSON and checksum in durable activation
   state beside catalog release ID and manifest SHA-256;
7. create separate activation evidence only after pointer switch, restart, and
   exact live/public HTTPS smoke checks succeed.

`assetlinks.json` is not copied by hand. Platform derives it from the activated
catalog's `androidAppLink`. Adding a certificate is declarative only and never
modifies Platform's external root-owned trust policy; first release and rotation
must be preapproved there, with old/new overlap retained through migration.
