# Worker Result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- Generated: `2026-07-26T11:23:11Z`
- Worker: `auto-worker-5.5`
- Result: `integration_requested`
- Check status: `passed`
- Next owner: `Integrator`

## Delivered scope

- Added a strict HS256 JWT authentication boundary that verifies signature,
  algorithm, issuer, audience, canonical subject and token id, expiry,
  issued-at/not-before policy, bounded token shape, and active account status.
- Added registry-backed opaque authenticated-principal and session-participant
  capabilities. Reflection-created instances and proxy implementations are not
  registered and therefore cannot bind membership.
- Kept access-token issuance JVM-private and identity-owned through a private,
  registry-backed grant; no public or internal raw-subject token issuer exists.
- Pinned `SessionMembershipResolver` to its canonical `JwtAccessTokenService`.
  A principal authenticated by another service instance, including one with an
  active resolver and a different valid signing key, is rejected.
- Added hostile reflection tests without `isSynthetic` filtering, plus token,
  inactive-account, route-bound and cross-authority regression coverage.

## Verification

1. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks`
   — passed (`BUILD SUCCESSFUL`).
2. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject`
   — passed (`BUILD SUCCESSFUL`).
3. `/home/main/.local/jdk21/bin/javap -p -s -classpath InplaceX-backend/build/classes/kotlin/main ...`
   — inspected principal, identity-grant and session-capability constructor and
   method surfaces; no raw-identity token issuer was present.
4. `git diff --check`
   — passed.

## Scope and handoff

- Changed only packet-allowed backend auth, identity, session-authorization,
  test and this worker-report paths.
- No transport route, JDBC/schema, duel transition, persistence implementation,
  Task Manager state, lock or event was changed.
- The remote `develop` advance was inspected before implementation and affected
  only runner-owned Task Manager state; the immutable execution base remained
  valid for this packet.

`check_status=passed`
