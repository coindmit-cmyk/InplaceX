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
  startup applies database migrations before the module is ready when a database is configured
- runtime host, port, and deployment label are read from `INPLACEX_BACKEND_HOST`,
  `INPLACEX_BACKEND_PORT` (or conventional `PORT`), and
  `INPLACEX_BACKEND_ENVIRONMENT`
- PostgreSQL is optional for the foundation and is enabled only when all of
  `INPLACEX_DATABASE_JDBC_URL`, `INPLACEX_DATABASE_USERNAME`, and
  `INPLACEX_DATABASE_PASSWORD` are supplied by the process environment. These
  values are never written to configuration files or logs.
- versioned SQL migrations create player, cloud-save revision, matchmaking ticket,
  duel session, idempotent command, and event storage; startup records applied
  versions in `inplacex_schema_history`
- JDBC repositories use database constraints, transactional writes, and optimistic
  revisions for cloud saves; session commands are idempotent per client command id

Run the local server with `./gradlew :InplaceX-backend:run`. It binds to
`0.0.0.0:8080` by default.
