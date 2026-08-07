# Vendored platform-game-sdk

This module is a source snapshot of
`coindmit-cmyk/MirkoriGamesPlatform:platform-game-sdk` at merge commit
`be3f197a154de633e78f04cb7f8be8b3531c28a8`, coordinate
`com.mirkori.platform:platform-game-sdk:0.2.0`.

The InplaceX snapshot carries one reviewed transport-metadata extension on top
of that commit: `PlatformHttpResponse.serverTime` and the SDK's monotonic
observation revision expose a transport-validated HTTPS `Date` value without
changing any Platform request or JSON contract. This is used only to evaluate
offline timed entitlements without trusting the device wall clock.
The snapshot also exposes `pendingOrders()` at the reviewed Platform route
`GET /api/v1/commerce/orders/pending`; Android commerce uses this explicit
projection instead of inferring active work from bounded order history.

It is vendored because the platform repository is private and an InplaceX
GitHub Actions token cannot safely resolve a cross-repository source or Maven
dependency without adding a long-lived credential. Update it only from a
reviewed platform SDK release, preserve package/API compatibility, and run both
the SDK tests and root `verifyProject`.
