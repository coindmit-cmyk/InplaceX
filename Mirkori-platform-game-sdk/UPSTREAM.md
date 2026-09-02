# Vendored platform-game-sdk

This module is a source snapshot of
`coindmit-cmyk/MirkoriGamesPlatform:platform-game-sdk` at merge commit
`ac8f56aa3bf29661df8c362c9afabcf77c9ed50e`, coordinate
`com.mirkori.platform:platform-game-sdk:0.4.4-SNAPSHOT`.

That reviewed snapshot includes guest checkout handoffs, entitlement delivery
and acknowledgement, signed installed-build decisions, signed Pro membership
snapshots, and Pro concurrency leases.

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
