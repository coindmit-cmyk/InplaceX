# Sideload Update Contract

## Authority

Mirkori Games Platform is the only public release-catalog authority for the
first APK distribution. InplaceX queries:

```text
GET /api/v1/catalog/games/inplacex/updates?platform=android&channel=stable&versionCode=<installed>
```

The client uses the installed `BuildConfig.VERSION_CODE`. It accepts schema
version `1` only and verifies the Platform policy locally:

- `updateAvailable=false` requires `required=false` and no release object;
- an available release must have a greater numeric `versionCode`;
- `required` must equal `installedVersionCode < minimumSupportedVersionCode`;
- the release must belong to `inplacex`, `android`, and `stable`.

The public catalog request is independent of the current guest or linked
account and carries no bearer token.

## Artifact URL Boundary

Production Platform origins and download URLs must use HTTPS. Debug may use
HTTP only for an explicit loopback origin through
`MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK`.

The SDK and Android runtime both reject a download URL unless it:

- has the same scheme, host, and effective port as the configured Platform
  origin;
- has no user info, query, or fragment;
- exactly matches `/downloads/{encodedReleaseId}/{encodedFileName}`;
- describes the validated Android APK metadata and lowercase SHA-256 digest.

The first client opens that immutable URL through an external browsable
`ACTION_VIEW` intent. InplaceX does not receive, cache, or install APK bytes,
does not request `REQUEST_INSTALL_PACKAGES`, and does not weaken Android's
same-signing-certificate update check. The owner-published Platform manifest
remains responsible for matching the signed APK size and SHA-256 before the
release becomes visible.

## Runtime And Lifecycle

The app checks after `Activity.onStart`, including the first foreground entry
and a return from another app. Compose cancellation plus a monotonic request
generation prevents an older lifecycle request from replacing a newer result.
Account changes do not restart or authorize the public catalog request.

Network behavior uses the existing Mirkori transport: finite connect/request/
socket timeouts, at most two attempts by default, no redirects, a 64 KiB JSON
response limit, and sanitized error logging.

Only update policy metadata is persisted:

- current-version responses use a six-hour cooldown;
- transport or validation failures use a fifteen-minute retry cooldown;
- dismissing an optional release suppresses that exact release for 24 hours;
- required releases ignore optional dismissal state;
- persisted future timestamps beyond 48 hours are discarded as invalid.

A discovered update is not placed behind the ordinary check cooldown. This
prevents a process restart from silently bypassing a previously required
release while avoiding persistence of the download URL or artifact metadata.

## Presentation

An optional update dialog has `Download APK` and `Later`; dismissing it leaves
all game actions available. A required update dialog has one explicit download
action and cannot be dismissed by tapping outside or pressing Back. Failure to
resolve a browser is localized and keeps the dialog available for retry.

The dialog renders the catalog version, artifact size, and release changelog in
a bounded scrollable area. Russian and English entries live in the normal
localization catalogs.

## Verification

- `:Mirkori-platform-game-sdk:test` covers exact JSON, update semantics, GET
  shape, auth absence, HTTPS/same-origin policy, and debug loopback.
- `:app:testDebugUnitTest` covers persisted cooldown/dismissal, required-policy
  precedence, invalid-origin fail-closed behavior, transport retry bounds, and
  presentation formatting.
- `:app:testReleaseUnitTest` proves release cannot enable a cleartext remote
  Platform origin.
- Android instrumentation covers optional and required dialog actions.
