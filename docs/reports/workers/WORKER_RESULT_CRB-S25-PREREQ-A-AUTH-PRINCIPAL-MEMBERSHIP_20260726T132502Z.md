# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- `check_status`: passed
- `integration_status`: integration_requested
- `base`: `1b18cd9f97ba3882d2bffb7d103159b5ba5fcfb0` (`origin/develop`)

## Delivered scope

- Added an authentication-owned opaque `AuthenticatedPrincipal`. The verifier
  records the principal only in its own instance-local registry, so a
  reflection-created or foreign-verifier principal is rejected.
- Added strict HS256 JWT verification with canonical Base64URL, a UTF-8
  decoder configured with `CodingErrorAction.REPORT`, duplicate/unknown claim
  rejection, issuer/audience/time/token-id/subject/account-state checks and
  redacted rejection logging.
- Replaced the raw-subject private issuer with identity-owned opaque grants.
  No issuer method accepts a `String`, `UUID` or `ByteArray` identity; forged
  grants and reflective calls fail closed.
- Removed player identifiers from identity logs. No session membership,
  transport, persistence implementation, schema or duel code was changed.

## Verification

All commands used process-only toolchain configuration:

```text
JAVA_HOME=/home/main/.local/jdk21
JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
ANDROID_HOME=/home/main/.local/android-sdk
ANDROID_SDK_ROOT=/home/main/.local/android-sdk
```

| Command | Outcome |
| --- | --- |
| `bash gradlew :InplaceX-backend:test --rerun-tasks` | passed; 61 tests |
| `bash gradlew verifyProject` | passed |
| `git diff --check` | passed |
| `/home/main/.local/jdk21/bin/javap -p -s -classpath InplaceX-backend/build/classes/kotlin/main com.mirkori.inplacex.backend.auth.JwtAccessTokenService` | inspected: no public/internal/synthetic token-issuance method |

The hostile tests enumerate declared constructors and methods without filtering
synthetic members, invoke reflected principal/grant paths, cover a foreign JWT
service, malformed UTF-8, duplicate/unknown/noncanonical/oversized claims,
time/account failures, and redaction.

## Handoff

`integration_requested` for independent strong review. S25B remains responsible
for canonical server-owned session membership composition.
