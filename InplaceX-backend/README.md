# InplaceX Backend

This module contains the first backend-facing runtime contract for PvP bots and
a modular Ktor application foundation.

Planned responsibilities:

- player profile sync
- cloud save
- PvP matchmaking
- server-side bot player runtime
- rankings and seasonal progression
- entitlement validation for ads, Pro, and Pro+

Current state:

- JVM Gradle module
- `ServerBotPlayer` wraps the shared `InplaceX-bot-core` brain as a backend match participant
- Ktor runtime entry point: `com.mirkori.inplacex.backend.app.BackendApplicationKt`
- `GET /health` returns `200 {"status":"ok"}` when the process is alive
- `GET /ready` returns `200 {"status":"ready"}` when configured modules are ready;
  future database and external-service probes will participate in this endpoint
- runtime host, port, and deployment label are read from `INPLACEX_BACKEND_HOST`,
  `INPLACEX_BACKEND_PORT` (or conventional `PORT`), and
  `INPLACEX_BACKEND_ENVIRONMENT`; no secret is read or logged by this foundation
- future transport, matchmaking, persistence, and room/session code will build around this module

Run the local server with `./gradlew :InplaceX-backend:run`. It binds to
`0.0.0.0:8080` by default.
