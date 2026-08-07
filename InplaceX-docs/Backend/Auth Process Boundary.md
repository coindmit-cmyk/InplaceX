# Auth Process Boundary

## Decision

Online authentication is split across the Mirkori Platform and the InplaceX
game backend:

- Mirkori Games Platform owns guest bootstrap, provider identities,
  refresh-token rotation, stable per-game player ids, and the RSA private
  signing key;
- the Platform alone links Google, Telegram, local, or future provider
  identities to the global account while preserving the InplaceX
  `gamePlayerId`;
- the game API owns matchmaking and authoritative sessions and receives only the
  matching Platform RSA public key.

Access tokens use `RS256`. The game API must never receive an HMAC JWT secret or
an RSA private key through configuration, constructors, test fixtures, or
fallback development paths. The configured X509 RSA public key must have a
modulus of at least 2048 bits; weaker keys fail during configuration and verifier
construction.

## Trust boundary

Arbitrary reflection inside one JVM is treated as compromise of that process.
Therefore token issuance and game-session authorization cannot share a JVM in
production or staging. The Platform is the only production token issuer; the
game API constructs `JwtAccessTokenVerifier` from its public key and requires
the configured issuer, audience, `pid`, and exact `gid=inplacex`. The global
account `sub` is retained for audit context but never becomes the online
player principal.

The verified player principal is still not proof of session membership.
Authoritative session composition must resolve membership from server-owned
session state before returning a snapshot, accepting a command, reconnecting a
player, or opening a WebSocket.

Provider configuration is Platform-only. The game API never receives Google or
Telegram credentials, provider tokens, or provider payloads. The retired
InplaceX identity sources remain debug/test compatibility only and are not
composed by the release backend or Android online runtime.

The bounded upgrade exception is the one-time active-session bridge at
`POST /api/v1/sessions/{sessionId}/legacy-membership`. It does not issue an
identity or accept a legacy access token as game authorization. The caller must
first authenticate with a valid Platform game token; one still-active legacy
refresh token is accepted only as proof of ownership of the member being
replaced by that token's `pid`. PostgreSQL commits the encrypted membership
change, one revision advance, legacy credential consumption and family
revocation, and the durable idempotency record in one transaction. Raw proof
material is bounded, hashed on ingress, and never persisted in the migration
record or written to logs. The bridge can be removed after the supported
upgrade window and all pre-Platform online sessions have expired.

## Deployment gate

Staging remains disabled until all of the following are proven together:

1. Mirkori Platform and the InplaceX game API run as separate processes;
2. only the Platform can read the private key;
3. the game API starts with an RSA public key of at least 2048 bits and fails
   closed when it is absent, malformed, or weaker;
4. hostile-key, exact Bearer-header, malformed UTF-8, duplicate claim,
   signed-additive-claim, canonical `sub/pid/jti`, expiry/not-yet-valid, issuer,
   audience, `gid`, concurrent verification, public JVM surface, and membership
   tests pass;
5. authenticated matchmaking, reconnect, and WebSocket E2E pass on the exact
   server and Android revisions selected for staging.
6. compatibility migration proves wrong-player rejection, atomic rollback,
   exact replay after response loss and backend restart, credential revocation,
   and a subsequent Platform-authorized session read; lock/replay/rollback races
   run against a real PostgreSQL instance rather than an H2 compatibility mode.
