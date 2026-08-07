# InplaceX release distribution

`build_platform_catalog_release.py` converts the immutable directory produced by
`:app:releaseCandidate` into the exact catalog snapshot consumed by Mirkori Games
Platform. It does not sign an APK, upload files, publish a catalog, or handle
credentials. It is a deterministic transformer, not a certificate trust root.

The builder fails closed unless it receives either the previously verified
Platform catalog snapshot or the explicit one-time `--allow-empty-base` flag.
Routine releases must always use `--base-release-dir`; otherwise another game's
catalog entry or an older retained InplaceX release could be lost. The supplied
base must be an export of the exact resolved `current` release from Platform,
not a remembered local copy or `backup`. The builder cannot discover server
activation state by itself; the Platform publisher must independently enforce
that the candidate is a superset of its active catalog.

The supported routine workflow is the root Gradle task below. It depends on
`:app:releaseCandidate`, derives the exact candidate directory from canonical
Android version properties, resolves the current Git `HEAD`, and requires the
candidate identity to contain that same full commit. Configure external release
signing first; this command intentionally fails if signing is unavailable.

```powershell
.\gradlew.bat buildPlatformCatalogRelease `
  -PinplacexPlatformCatalogBaseReleaseDir=D:\secure\mirkori-platform-current-catalog `
  -PinplacexPlatformCatalogOutputDir=D:\secure\catalog-inplacex-1.0-1 `
  -PinplacexPlatformCatalogMinimumSupportedVersionCode=1 `
  -PinplacexPlatformCatalogPublishedAt=2026-08-07T12:00:00Z `
  "-PinplacexPlatformCatalogChangelog=Первый ограниченный релиз."
```

Direct Python invocation is reserved for reviewed recovery/bootstrap procedures
and must pass `--expected-commit` explicitly. `--allow-empty-base` does not prove
that Platform is empty and must never be substituted for the active base during
a routine release.

Before transfer, validate the output with the tool from the exact reviewed
Mirkori Games Platform revision:

```powershell
python D:\Work\DevOps\MirkoriGamesPlatform\ops\catalog_release_tool.py validate `
  D:\secure\catalog-inplacex-1.0-1
```

The Platform validator independently opens the APK, verifies its modern
signature, signer certificate, package, version, minimum SDK, non-debuggable
manifest, size and SHA-256. On the server, the Platform catalog publisher
repeats the same validation before activation and performs exact live and public
HTTPS smoke checks.

`assetlinks.json` is not copied by hand. Mirkori Games Platform derives
`/.well-known/assetlinks.json` from the catalog's `androidAppLink` entry. The
catalog builder preserves existing fingerprints and adds the exact fingerprint
from the signed candidate. This addition is declarative only: it does not grant
trust and never modifies Platform's external root-owned catalog trust policy.
Before a first release or intentional key rotation, that policy must explicitly
preapprove `com.mirkori.inplacex` and every declared fingerprint. During rotation
the old and new certificates must overlap until the reviewed migration is
complete; otherwise the production Platform validator rejects the snapshot.
