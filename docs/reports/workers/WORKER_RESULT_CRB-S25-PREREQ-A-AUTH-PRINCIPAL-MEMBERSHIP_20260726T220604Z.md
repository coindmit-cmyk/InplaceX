# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

Status: `blocked` / `needs_architect`

## Scope and freshness

- Immutable execution base: `cc5fdfb3b0a2ab819576b91026d44110941aeb6b`.
- The GitHub freshness guard passed: this isolated worker branch and
  `origin/develop` both resolve to that base. There is no implementation or
  required-reference drift.
- No application source was changed. This report is the sole changed path.

## Blocking security finding

The assigned acceptance contract requires that an attacker with unrestricted
same-process JVM reflection cannot create a JWT accepted by the canonical
HS256 verifier, including after private fields and reachable issuer objects
are reflected.

That requirement is not realizable in the permitted single JVM-module
boundary. The current identity implementation demonstrates the unavoidable
primitive: `GuestIdentityService` retains a private `accessTokens` issuer;
`SignedAccessTokenIssuer` retains an HS256 `signingKey`; its reachable `issue`
method builds a token from a raw `playerId`. An unrestricted reflective caller
can read the signing key (or a `SecretKeySpec`) and sign a standards-compliant
token for any canonical active subject directly. Replacing this implementation
with private Kotlin classes, opaque grants, or mutable private registries does
not create a security boundary: reflection can invoke the issuer or mutate the
registry. The latter design was independently rejected in the mandatory
`f3151a5` review.

The allowed paths contain neither an encapsulated module boundary without
`opens` access nor an external/HSM-backed signer. Implementing another
private-in-source issuer would falsely claim the required hostile-reflection
property and repeat the rejected raw-issuer/registry vulnerability.

## Required next owner

Route to Architect for a revised trust boundary and attacker model. A safe
follow-up must place canonical signing/verification outside arbitrary
same-process reflection (for example an encapsulated non-open JVM module or
an external signing authority), then provide an explicit auth/identity packet
for that boundary. Session membership, routes, JDBC/schema work, duel
transitions, and persistence implementation remain out of this prerequisite.

## Required commands

- `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks` — passed.
- `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject` — passed.
- `git diff --check` — pending at report creation; run immediately before handoff.

`check_status=blocked`: the commands pass on the current baseline, but no
safe implementation can satisfy the packet's unrestricted-reflection
acceptance criteria in the authorized paths. Do not emit
`integration_requested` for this result.
