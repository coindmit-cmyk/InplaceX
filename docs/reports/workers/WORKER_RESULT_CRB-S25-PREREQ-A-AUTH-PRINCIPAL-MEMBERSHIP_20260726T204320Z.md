# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

Status: `blocked` / `needs_architect`

## Scope and freshness

- Immutable execution base: `eedd13d03c1a2afcced3c8660ffe2784e583ac58`.
- `origin/develop` advanced by one commit, but its only changed paths are
  `AiStudio/Task_manager/agent_locks.json` and
  `AiStudio/Task_manager/task_queue.json`. No implementation or required
  reference drift affects this packet, so no merge or rebase was performed.
- No application source was changed. The only changed path is this required
  worker report.

## Blocking security finding

The packet requires that hostile unrestricted JVM reflection cannot mint a JWT
accepted by the canonical verifier, even after reflecting private fields and
reachable issuer objects. That property cannot be implemented in this ordinary
single JVM module with the allowed paths:

1. The canonical verifier necessarily retains an HS256 signing key in process.
2. `setAccessible` reflection in that same process can read the private key
   material (or a `SecretKeySpec`) from the verifier.
3. The hostile caller can then construct a standards-compliant JWT for any
   existing active canonical subject and sign it directly. This bypasses every
   Kotlin-private issuer/grant API and is accepted by the required canonical
   HS256 verifier.

Private registry/grant designs do not repair this boundary. The mandatory
review of `f3151a5` already demonstrated the concrete variant: a reflected
mutable identity-grant registry plus reachable private issuer minted an
accepted caller-selected subject. Replacing it with another private registry
would repeat the rejected design, while storing an immutable or sealed record
does not protect the verifier key from the stated unrestricted reflection
attacker.

## Required architecture route

Route to Architect for a trust boundary outside arbitrary in-process
reflection, then re-dispatch with paths that implement it. Viable directions
include a separately encapsulated Java module with no `opens` access from the
untrusted module, or an external/HSM-backed signing authority. The redesigned
acceptance contract must also state the supported attacker model; a same-process
attacker with unrestricted reflection and access to the HS256 key is equivalent
to a key-compromise attacker.

No session membership, transport, JDBC, schema, duel, or persistence behavior
was introduced.

## Checks

- `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks` — passed (57 tests, 0 failures, 0 errors).
- `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject` — passed.
- `git diff --check` — passed.

This task must not emit `integration_requested`, because no safe implementation
exists under the current authority boundary.
