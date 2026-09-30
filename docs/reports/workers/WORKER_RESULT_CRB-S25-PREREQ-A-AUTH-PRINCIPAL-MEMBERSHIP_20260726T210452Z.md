# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

Status: `blocked` / `needs_architect`

## Scope and freshness

- Immutable execution base: `78d53a878d2a17b3901c60b96c16e050318ef9ee`.
- `origin/develop` advanced only in runner-owned
  `AiStudio/Task_manager/agent_locks.json` and
  `AiStudio/Task_manager/task_queue.json`. It has no implementation or
  required-reference drift, so this packet remains safe to assess from its
  immutable base without a merge or rebase.
- No application source is changed. This report is the only worker change.

## Blocking security finding

The packet requires hostile unrestricted JVM reflection to be unable to mint a
JWT accepted by the canonical HS256 verifier. That property is impossible in
the permitted single JVM module and paths:

1. A canonical HS256 verifier must retain key material in this process.
2. The required attacker can use `setAccessible` reflection to read that key
   material, whether it is stored as a byte array or a `SecretKeySpec`.
3. The attacker can sign a standards-compliant token for any active canonical
   subject outside every Kotlin-private issuer, registry, constructor and
   method boundary. The canonical verifier must then accept that token.

The existing `GuestIdentityService` demonstrates the same in-process signing
shape through private `SignedAccessTokenIssuer.signingKey`; changing its
visibility or adding another private registry does not protect it from the
stated attacker. The mandatory review of `f3151a5` independently rejected the
mutable-registry variant after reflected state minted a canonically accepted
caller-selected token.

No safe implementation can satisfy the raw-issuer and unrestricted-reflection
acceptance criteria simultaneously. Implementing another private issuer or
grant would repeat a rejected security design.

## Required architecture route

Route to Architect for a trust boundary that is inaccessible to arbitrary
in-process reflection, then re-dispatch with the necessary module/composition
paths. Suitable directions include a strongly encapsulated Java module without
`opens` access from untrusted code, or an external/HSM-backed signing
authority. The redesigned acceptance contract must distinguish ordinary
application reflection from same-process key compromise.

No session membership, route, JDBC, schema, duel, persistence or other
out-of-scope behavior was introduced. `integration_requested` must not be
emitted for this blocked packet.

## Required checks

- `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks` — passed (`BUILD SUCCESSFUL`; 11 tasks executed).
- `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject` — passed (`BUILD SUCCESSFUL`; 43 actionable tasks).
- `git diff --check` — passed.
- `git diff --no-index --check /dev/null docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T210452Z.md` — passed for the untracked report content.
