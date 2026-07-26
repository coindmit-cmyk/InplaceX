# S25 prerequisite A integrator review: 618dfd0

Mode: `ManualIntegrationMode`

Disposition: `rejected`

Candidate `618dfd088e314c86ba847a9cb6b0e8316abacc16` is preserved on its worker
branch and must not be integrated into `develop`.

## Blocking finding: cross-authority principal forgery

The candidate removed the previous public raw-subject token issuer. Its
`JwtAccessTokenService` constructor is still public, however, and
`SessionMembershipResolver` does not bind a principal to the server's canonical
authentication service.

An in-process caller can therefore:

1. construct another `JwtAccessTokenService` with an attacker-controlled
   signing key;
2. supply an account resolver that returns `ACTIVE`;
3. sign a token whose subject is a known active player's id;
4. call `authenticate` on the attacker-owned service;
5. pass the resulting principal to `SessionMembershipResolver`.

The resolver asks the principal's own service whether its subject matches the
membership record. It does not ask a trusted canonical authentication
authority. The attacker-owned service consequently validates its own principal
and can bind the victim's participant.

The candidate's `SessionMembershipResolverTest.principal` helper demonstrates
the missing trust pin: it constructs an arbitrary verifier with a test key and
resolver, then uses its principal successfully in membership resolution.

## Verified improvements retained as salvage evidence

- `JwtAccessTokenService` has no `issue`, Kotlin-internal issuer or
  `issue$default` method.
- Token issuance in `GuestIdentityService` is JVM-private and consumes a
  registry-backed `IdentityTokenGrant`.
- Reflection-created principal, identity-grant and participant-capability
  instances remain unregistered and are rejected.
- The candidate passed 72 backend tests, `verifyProject` and whitespace checks.

These improvements are evidence for a clean retry, not merge authorization.

## Required retry contract

- Bind `SessionMembershipResolver` to the exact trusted authentication
  authority instance used by production identity/bootstrap wiring.
- Subject matching must be performed by that trusted authority against its own
  registry; it must not delegate trust to the owner embedded in the supplied
  principal.
- A principal produced by any other `JwtAccessTokenService` instance must be
  rejected, even when its subject matches a real member and its self-selected
  account resolver returns `ACTIVE`.
- Add a hostile cross-authority test with two services and two signing keys:
  the canonical principal succeeds, while the attacker-service principal for
  the same player id fails membership resolution.
- Preserve the private identity-owned issuance and method/constructor
  reflection gates proven by `618dfd0`.

## Evidence

- Fresh integration base:
  `b253e865fcf783c1f482a6ef61b50b94330a2f80`
- Candidate:
  `618dfd088e314c86ba847a9cb6b0e8316abacc16`
- Candidate branch:
  `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-princip-retry-20260725T234343Z`
- Independent checks:
  `:InplaceX-backend:test --rerun-tasks`, `verifyProject`,
  `git diff --check`, and method-level `javap -p -s` passed.
- The newly adopted Integrator rules reference
  `scripts/agent_control/capability_preservation_check.py`, but that script is
  absent from this repository snapshot. No preservation approval is claimed;
  the candidate is rejected on the direct-source security finding above.

## Next route

Return the same Worker Packet v2 task to `auto-worker-5.5` with the
cross-authority hostile test and trusted-authority binding added to its
acceptance criteria. Keep all rejected branches and worktrees as live recovery
evidence until a safe successor is accepted.
