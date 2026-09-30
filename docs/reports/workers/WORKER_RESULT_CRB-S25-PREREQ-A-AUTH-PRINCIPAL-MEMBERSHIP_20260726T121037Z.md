# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

## Outcome

`check_status=passed`

The retry establishes a fail-closed JVM authentication prerequisite. JWT
verification creates a principal only after signature, issuer, audience,
canonical subject/token id, time claims and active-player checks. A principal
constructed reflectively with an authentication service is not registered by
that service and therefore cannot match any subject.

Guest token issuance remains exclusively inside `GuestIdentityService`: its
private issuer consumes an identity-owned opaque grant, rather than a raw
player id. Tokens, principals and guest bootstrap results redact their
`toString()` output; identity logs now use outcome-only attributes.

The session authorization surface contains no caller-owned membership port,
record, factory or participant-id input. `SessionMembershipResolver` and its
participant capability have non-public JVM constructors, so a later canonical
session repository must supply the sole concrete resolver without accepting
transport-controlled participant identities. Until that integration exists,
capability unwrapping fails closed.

## Changed paths

- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/auth/AuthenticatedPrincipal.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/auth/JwtAccessTokenService.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityService.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolver.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/auth/JwtAccessTokenServiceTest.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt`

## Evidence

All Gradle checks used process-only environment settings required by the task:

```text
JAVA_HOME=/home/main/.local/jdk21
JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
ANDROID_HOME=/home/main/.local/android-sdk
ANDROID_SDK_ROOT=/home/main/.local/android-sdk
```

| Command | Result |
| --- | --- |
| `bash gradlew :InplaceX-backend:test --rerun-tasks` | passed (63 tests) |
| `bash gradlew verifyProject` | passed |
| `git diff --check` | passed |
| `/home/main/.local/jdk21/bin/javap -p -classpath InplaceX-backend/build/classes/kotlin/main ...` | passed: no principal or capability constructor accepts `String`, UUID, participant id, session id or `DefaultConstructorMarker` |

The hostile tests cover malformed, oversized, duplicate-claim, wrong-key,
wrong-algorithm, active-state and cross-authority JWT cases; reflection-created
principal rejection; redaction; and absence of caller-owned membership ports,
records and resolver constructors.

## Handoff

`integration_requested`

Strong integration review is required by the task packet. The integrator should
retain the fail-closed membership boundary until the canonical session
repository/authorization implementation is wired in its own allowed package;
do not reintroduce a public membership port, caller-controlled resolver or
participant-id input.
