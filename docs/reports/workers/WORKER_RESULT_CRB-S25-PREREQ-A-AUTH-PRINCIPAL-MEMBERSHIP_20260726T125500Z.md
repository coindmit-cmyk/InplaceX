# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- Status: `needs_worker_fix`
- Check status: `partial`
- Base: `7f8e020dda690d75589cd0ee8e2ecf49fce37740`

## Implemented evidence

- Added strict HS256 JWT verification with issuer, audience, canonical subject,
  token-id, time-window, duplicate-claim, bounded-input and active-account
  checks. A verifier keeps accepted principals in an instance-local registry;
  reflected or foreign-service principals are not accepted.
- Moved guest access-token signing behind a private identity-grant registry so
  the signer consumes an opaque registered grant rather than a raw public
  subject argument.
- Added route-bound membership capability checks, including a foreign verifier
  and a caller-owned resolver capability rejected by the trusted resolver.
- `javap -p -s` confirms that public `AuthenticatedPrincipal` is an interface
  without constructors and `JwtAccessTokenService` has no token-issuance
  method.

## Remaining blocker

The packet requires a non-exported canonical session-authorization authority
whose membership port cannot be supplied by arbitrary JVM callers, while the
current allowed paths contain no constructible session repository/composition
owner. The submitted resolver keeps a private implementation and binds every
capability to its issuer, but its public composition factory still accepts
`SessionMembershipPort`; this does not meet the packet's stricter ownership
criterion. Do not integrate this candidate as the prerequisite is not fully
complete. Route to Dispatcher/Architect for a packet that names the canonical
composition owner or permits the required session composition path.

## Commands

1. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks` — passed (62 tests).
2. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject` — passed.
3. `git diff --check` — passed.
4. `/home/main/.local/jdk21/bin/javap -p -s -classpath InplaceX-backend/build/classes/kotlin/main com.mirkori.inplacex.backend.auth.JwtAccessTokenService com.mirkori.inplacex.backend.auth.AuthenticatedPrincipal com.mirkori.inplacex.backend.session.authorization.SessionMembershipResolver` — completed; evidence above.
