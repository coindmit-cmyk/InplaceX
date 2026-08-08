# InplaceX Backend

This module contains the first backend-facing runtime contract for PvP bots and
a modular Ktor application foundation.

Future responsibilities, not release routes yet:

- player profile sync
- cloud save
- rankings and seasonal progression
- entitlement validation for ads, Pro, and Pro+

Current state:

- JVM Gradle module
- `ServerBotPlayer` wraps the shared `InplaceX-bot-core` brain as a backend match participant
- Ktor runtime entry point: `com.mirkori.inplacex.backend.app.BackendApplicationKt`
- `GET /health` returns `200 {"status":"ok"}` when the process is alive
- `GET /ready` returns `200 {"status":"ready"}` when configured modules are ready;
  startup applies database migrations before the module is ready when a database is configured
- `GET /api/v1/runtime/ad-market` returns only `RUSSIA`, `GLOBAL`, or `UNKNOWN`
  for Android advertising routing and never returns the client IP
- runtime host, port, and deployment label are read from `INPLACEX_BACKEND_HOST`,
  `INPLACEX_BACKEND_PORT` (or conventional `PORT`), and
  `INPLACEX_BACKEND_ENVIRONMENT`
- PostgreSQL is optional outside production and is enabled only when JDBC URL,
  username, and exactly one password source are present. Production uses
  `INPLACEX_DATABASE_PASSWORD_PATH`; inline
  `INPLACEX_DATABASE_PASSWORD` is a development/test compatibility input and
  must not be used in a production manifest. JDBC URLs containing user info,
  query parameters or fragments are rejected so credentials and options cannot
  bypass the explicit runtime policy.
- versioned SQL migrations create player, cloud-save revision, matchmaking ticket,
  duel session, idempotent command, and event storage; startup records applied
  versions in `inplacex_schema_history`. Checksums are based on canonical LF content,
  so Windows CRLF checkouts do not change migration identity.
- JDBC repositories use database constraints, transactional writes, and optimistic
  revisions for cloud saves; session commands are idempotent per client command id
- when PostgreSQL and the online API are enabled together,
  a state key is required and must decode to 32 random bytes. Production uses
  `INPLACEX_ONLINE_STATE_KEY_BASE64_PATH`; the inline form is development/test
  compatibility only. The runtime encrypts each recoverable duel aggregate with AES-256-GCM,
  restores active/recently-finished matches during startup, and preserves exact
  command replays across a backend restart. Never commit or log this key.
- the same PostgreSQL runtime restores non-expired matchmaking tickets and
  retained private invites before online routes start. Create/accept command
  ids are reconstructed from their rows, so retries after restart return the
  existing ticket, invite, or shared session instead of minting duplicates.
- production online authentication accepts only Mirkori Games Platform RS256
  game tokens. Configure `INPLACEX_ONLINE_TOKEN_ISSUER` and
  `INPLACEX_ONLINE_TOKEN_AUDIENCE` to the Platform values (normally
  `mirkori-platform` and `mirkori-games`) and provide only the Platform X509
  RSA public key through `INPLACEX_ONLINE_PUBLIC_KEY_X509_BASE64_PATH`. The
  inline form is development/test compatibility only. Keys below
  2048 bits are rejected during configuration and verifier construction. The
  verifier requires `pid` plus exact `gid=inplacex`; it never treats account
  `sub` as the player. PostgreSQL creates only an idempotent local player
  projection for the verified `pid`, not a second identity account.
- public matchmaking coordinates active backend instances through PostgreSQL:
  the oldest compatible waiting row is claimed with `FOR UPDATE SKIP LOCKED`,
  the duel and both matched tickets commit together, and bot fallback locks the
  same row. Ticket polling reloads database truth and sessions created by a
  peer instance are restored lazily for reconnect reads.
- preferred advertising market resolution uses a local MMDB country database
  configured by `INPLACEX_AD_MARKET_DB_PATH`. Direct connections are resolved
  from their numeric remote address. Behind nginx, the backend accepts
  `INPLACEX_AD_MARKET_CLIENT_IP_HEADER` only from
  `INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS`; nginx must overwrite that header.
- the legacy trusted-country-header mode remains available through
  `INPLACEX_AD_MARKET_COUNTRY_HEADER` plus trusted proxy hosts. The two source
  modes are mutually exclusive.
- `INPLACEX_AD_MARKET_REQUIRED=true` makes startup fail when neither safe
  source is configured. Missing database files also fail during startup,
  preventing a falsely ready production process.
- the production activation guard also fingerprints the mounted canonical
  GeoIP artifact through internal
  `INPLACEX_ACTIVATION_GEOIP_FINGERPRINT_PATH` wiring. Operators continue to
  configure the source with `INPLACEX_GEOIP_DB_PATH`; the internal variable is
  owned by the reviewed Compose manifest and is not a second provider setting.
- online REST requests have bounded in-process limits keyed by verified
  Platform principal plus operation. Invalid authentication has a separate
  bounded budget, `429` includes `Retry-After`, and concurrent WebSockets are
  capped per principal and globally. Nginx supplies an additional coarse
  per-client perimeter; one process-local limiter assumes the production
  single-backend Compose topology.
- database readiness uses bounded connection/validation/query timeouts and logs
  only state transitions plus a safe exception type. Credentials, JDBC query
  strings, payloads and exception messages are not emitted. A database-backed
  runtime also exposes counter/gauge telemetry at loopback-only `GET /metrics`;
  nginx deliberately does not publish this endpoint.

The release runtime currently exposes matchmaking create/read, friend invite
create/read/accept, session read/reconnect/secret/turn routes, the v1 session
WebSocket, ad-market routing, health/readiness and release metadata. Profile,
cloud-save HTTP routes, ticket cancellation, rankings and entitlements remain
future Platform/backend integration work; their schemas and repository code do
not make them deployed endpoints.

Run the local server with `./gradlew :InplaceX-backend:run`. It binds to
`0.0.0.0:8080` by default.

Production GeoIP setup and verification:
[`InplaceX-docs/Backend/Advertising Market Operations.md`](../InplaceX-docs/Backend/Advertising%20Market%20Operations.md).

Production deployment, secret file modes, immutable image evidence and rollback:
[`InplaceX-docs/Backend/Production Deployment.md`](../InplaceX-docs/Backend/Production%20Deployment.md).
