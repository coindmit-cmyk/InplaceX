# S25 prerequisite A integrator review: 69d80bb

## Verdict

`REJECT / needs_dispatcher_repair`.

Candidate `69d80bbbd496a9e7b371aa371cd144b4e5d878d0` is preserved as partial salvage. It must not be integrated as the combined authentication and membership prerequisite.

## Safe salvage

- strict HS256 verification with issuer, audience, canonical subject and token id, bounded time policy and active-account check;
- instance-local registration of authenticated principals, rejecting reflected and foreign-service principals;
- opaque identity-grant registry and redacted error/log/string behavior;
- capability-to-authority/session/participant registry pattern and hostile wrong-session/foreign-authority tests as negative design evidence.

## Blocking findings

1. `SessionMembershipResolver.Companion.canonical(SessionMembershipPort, JwtAccessTokenService, Logger)` and its synthetic default bridge are public JVM methods. Arbitrary callers can provide membership data.
2. `CanonicalSessionMembershipResolver` is package-private in source but has a public JVM constructor accepting the caller-owned port. The current test proves only that one resolver rejects another resolver's capability; it does not prove production composition cannot be built with the attacker resolver.
3. `SessionMembershipRecord` exposes a public synthetic constructor and `SessionParticipantCapability` exposes a public no-argument constructor. Registry binding rejects simple capability reflection but does not close the production-composition factory.
4. `SignedAccessTokenIssuer` is package-private but its JVM constructor and `issue(byte[], Instant, Instant)` method are public. Reflection through `GuestIdentityService.accessTokens` can issue a valid token for attacker-selected subject bytes.
5. JWT UTF-8 decoding uses replacement semantics instead of a decoder configured with `CodingErrorAction.REPORT`.
6. The current packet has no server-owned session application/composition path or concrete trusted membership source. Repeating membership work inside the auth-only allowed paths cannot satisfy the ownership contract.

## Independent evidence

- `:InplaceX-backend:test --rerun-tasks`: passed locally.
- Worker evidence: 62 backend tests, `verifyProject` and `git diff --check` passed.
- `javap -p -s` confirmed the public companion factory, default bridge, caller-port constructor, synthetic record constructor, capability constructor and raw-byte token issuer surface.
- Candidate worktree remained clean after review.

## Dispatcher repair

Split responsibility without weakening the dependency chain:

1. Keep this prerequisite responsible only for authentication-owned JWT verification, opaque principal creation and identity-owned token issuance.
2. Salvage the auth/identity subset from `69d80bb`, remove the candidate membership resolver, close the reflected raw-byte issuer and reject malformed UTF-8.
3. Move concrete canonical membership authority into S25B, where the server-owned session application state and trusted downstream consumer are created together.
4. Allow S25B a narrow app composition path plus session authorization implementation/tests. Do not export a factory or constructor accepting `SessionMembershipPort`, even as Kotlin `internal`.

## Mandatory auth retry tests

- foreign verifier with the same subject;
- reflection over all constructors, methods, synthetic/default bridges and private fields;
- raw `String`, UUID and `ByteArray` token/principal issuance attempts;
- reflected identity grant and principal;
- malformed UTF-8, duplicate/unknown claims, noncanonical Base64URL, wrong algorithm/signature/time/token id, oversized input and inactive account;
- redaction of tokens, subjects and secret bytes.

## Mandatory S25B composition tests

- genuine principal and server-owned membership positive path;
- genuine principal plus caller-owned port/record cannot select a participant in the production consumer;
- foreign resolver, reflected resolver, synthetic record and reflected capability are rejected;
- wrong session, wrong member, duplicate membership and membership limits fail closed;
- compiled JVM API exposes no production factory/constructor that accepts a caller-owned membership port.
