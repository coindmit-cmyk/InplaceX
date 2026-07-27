# Auth Process Boundary

## Decision

Online authentication is split across two process compositions:

- the identity process owns guest bootstrap, refresh-token rotation, and the RSA
  private signing key;
- the game API owns matchmaking and authoritative sessions and receives only the
  matching RSA public key.

Access tokens use `RS256`. The game API must never receive an HMAC JWT secret or
an RSA private key through configuration, constructors, test fixtures, or
fallback development paths.

## Trust boundary

Arbitrary reflection inside one JVM is treated as compromise of that process.
Therefore token issuance and game-session authorization cannot share a JVM in
production or staging. The identity process is the only component allowed to
construct `Rs256AccessTokenIssuer`; the game API constructs
`JwtAccessTokenVerifier` from a public key.

The verified player principal is still not proof of session membership.
Authoritative session composition must resolve membership from server-owned
session state before returning a snapshot, accepting a command, reconnecting a
player, or opening a WebSocket.

## Deployment gate

Staging remains disabled until all of the following are proven together:

1. identity and game API run as separate processes;
2. only the identity process can read the private key;
3. the game API starts with the public key and fails closed when it is absent;
4. hostile-key, malformed UTF-8, expiry, issuer, audience, and membership tests
   pass;
5. authenticated matchmaking, reconnect, and WebSocket E2E pass on the exact
   server and Android revisions selected for staging.
