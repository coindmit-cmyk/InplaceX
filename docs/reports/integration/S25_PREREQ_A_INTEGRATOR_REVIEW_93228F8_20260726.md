# S25 prerequisite A integrator review: 93228f8

## Verdict

`REJECT / needs_worker_fix`.

Candidate `93228f8b21e2de0d28e50d12e8870177b0e2e2c3` is preserved as negative/salvage evidence and must not be integrated.

## Blocking findings

1. `SessionMembershipResolver` is abstract but has only a private JVM constructor and no nested trusted implementation or factory. No production composition or canonical repository can instantiate or subclass it.
2. `SessionParticipantCapability` also has only a private constructor and no trusted issuer.
3. The only `participantIdFor` implementation always throws. The candidate therefore provides no positive canonical path that can resolve real membership or consume the capability.
4. The negative-only tests prove that arbitrary callers are rejected, but do not prove that a trusted canonical authority can actually work. This contradicts the acceptance criteria requiring current membership resolution through a port owned by a non-exported canonical authority.

`javap -p -s` confirms the private resolver/capability constructors and the absence of a realizable implementation/factory. It also confirms that Kotlin `internal` `AuthenticatedPrincipal(JwtAccessTokenService)` compiles as a public JVM constructor; the registry check prevents that object from authenticating, but hostile surface coverage must keep proving this path cannot become accepted.

## Independent evidence

- `gradlew :InplaceX-backend:test --rerun-tasks`: passed.
- `gradlew verifyProject`: passed.
- `git diff --check`: passed.
- JVM surface inspection: failed the positive realizability requirement described above.

## Mandatory retry contract

- Keep the principal, membership record, resolver, and participant capability non-forgeable by arbitrary JVM callers.
- Provide one concrete non-exported canonical session authorization authority that production composition can construct and that owns the trusted auth service plus membership repository/port.
- Prove a positive canonical path: a genuine authenticated member resolves to a session-bound participant capability and the trusted consumer unwraps it.
- Prove negative paths: foreign auth authority, caller-owned membership data, wrong member, wrong session, inactive account, reflection/default bridges, and raw String/UUID identities are rejected.
- Do not expose a caller-supplied membership port, public resolver constructor, raw participant-id issuer, route, JDBC implementation, schema, or persistence behavior.
- Hostile JVM tests must enumerate all constructors and methods without filtering synthetic members.
