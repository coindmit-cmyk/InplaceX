# Vendored platform-game-sdk

This module is a source snapshot of
`coindmit-cmyk/MirkoriGamesPlatform:platform-game-sdk` through merge commit
`71234e8e3427d3737d52c7b8a2bc062b40a7894b`, coordinate
`com.mirkori.platform:platform-game-sdk:0.4.6-SNAPSHOT`.

That reviewed snapshot includes guest checkout handoffs, entitlement delivery
and acknowledgement, signed installed-build decisions, signed Pro membership
snapshots, Pro concurrency leases, distribution-bound orders, and server-only
Google Play purchase-token verification.

The `0.4.6-SNAPSHOT` update adds the backend-only game-server telemetry client
for achievement and ordered gameplay facts. It accepts a per-call redacted
server credential and is not wired into the Android module.

The InplaceX snapshot carries two reviewed compatibility extensions on top of
that commit. `PlatformHttpResponse.serverTime` and the SDK's monotonic
observation revision expose a transport-validated HTTPS `Date` value without
changing any Platform request or JSON contract. Timed entitlements and signed
installed-build decision validity use that trusted observation instead of
depending solely on the device wall clock. The snapshot also preserves
`pendingOrders()` at the reviewed Platform route
`GET /api/v1/commerce/orders/pending`; Android commerce uses this explicit
projection instead of inferring active work from bounded order history.

It is vendored because the platform repository is private and an InplaceX
GitHub Actions token cannot safely resolve a cross-repository source or Maven
dependency without adding a long-lived credential. Update it only from a
reviewed platform SDK release, preserve package/API compatibility, and run both
the SDK tests and root `verifyProject`.
