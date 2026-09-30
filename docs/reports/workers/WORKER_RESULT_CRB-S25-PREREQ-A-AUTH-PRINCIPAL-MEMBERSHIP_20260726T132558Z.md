# Worker Result CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- Generated: `2026-07-26T13:25:58Z`
- Worker: `auto-worker-5.5`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-authent-retry-20260726T131440Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T131442Z`
- Result: `agent_done`
- Check evidence: `passed`
- Next owner: `Integrator`

## Check Evidence

```json
{
  "ok": true,
  "route": "agent_done",
  "check_status": "passed",
  "integration_status": "pending",
  "reason": "worker output contains explicit passed check_status",
  "negative_markers": [],
  "positive_markers": [
    "passed",
    "git diff --check",
    "check_status=passed"
  ]
}
```

## stdout

```text
Готово: добавлена закрытая JVM auth-грань и identity-owned выпуск токенов без session membership.

- Новый opaque `AuthenticatedPrincipal` и строгая HS256 JWT-проверка.
- Malformed UTF‑8, duplicate/unknown/noncanonical claims и невалидные time/account проверки отклоняются.
- Поддельные/foreign principals и reflected grants не принимаются.
- Убран raw-subject issuer; логи больше не содержат player ID.
- Отчёт: [WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T132502Z.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T131442Z/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T132502Z.md)

Проверки:

- `bash gradlew :InplaceX-backend:test --rerun-tasks` — passed, 61 тест.
- `bash gradlew verifyProject` — passed.
- `git diff --check` — passed.
- `javap -p -s ... JwtAccessTokenService` — проверен JVM surface, token issuer не экспортируется.

`check_status=passed`, `integration_status=integration_requested`.

```

## stderr

```text
ss.java ||
+            type.name == "kotlin.jvm.internal.DefaultConstructorMarker"
+
+    private fun defaultValue(type: Class<*>): Any? = when {
+        !type.isPrimitive -> null
+        type == Boolean::class.javaPrimitiveType -> false
+        type == Char::class.javaPrimitiveType -> '\u0000'
+        type == Byte::class.javaPrimitiveType -> 0.toByte()
+        type == Short::class.javaPrimitiveType -> 0.toShort()
+        type == Int::class.javaPrimitiveType -> 0
+        type == Long::class.javaPrimitiveType -> 0L
+        type == Float::class.javaPrimitiveType -> 0f
+        type == Double::class.javaPrimitiveType -> 0.0
+        else -> null
+    }
+
+    private fun serviceArgument(type: Class<*>): Any? = when (type) {
+        ByteArray::class.java -> KEY
+        JwtAccessTokenPolicy::class.java -> JwtAccessTokenPolicy(ISSUER, AUDIENCE, Duration.ofMinutes(15))
+        PlayerAccountStatusResolver::class.java -> PlayerAccountStatusResolver { PlayerAccountStatus.ACTIVE }
+        Clock::class.java -> Clock.fixed(NOW, ZoneOffset.UTC)
+        InplaceXLogger::class.java -> InplaceXLogger()
+        else -> defaultValue(type)
+    }
+
+    private fun service(
+        status: PlayerAccountStatus = PlayerAccountStatus.ACTIVE,
+        key: ByteArray = KEY,
+        logger: InplaceXLogger = InplaceXLogger(),
+    ) = JwtAccessTokenService(
+        signingSecret = key,
+        policy = JwtAccessTokenPolicy(ISSUER, AUDIENCE, Duration.ofMinutes(15)),
+        accountStatus = PlayerAccountStatusResolver { status },
+        clock = Clock.fixed(NOW, ZoneOffset.UTC),
+        logger = logger,
+    )
+
+    private fun payload(
+        issuer: String = ISSUER,
+        audience: String = AUDIENCE,
+        subject: String = SUBJECT,
+        issuedAt: Long = NOW.epochSecond,
+        expiresAt: Long = NOW.epochSecond + 900,
+        tokenId: String = TOKEN_ID,
+        notBefore: Long? = null,
+        extra: String = "",
+    ): String = "{\"iss\":\"$issuer\",\"aud\":\"$audience\",\"sub\":\"$subject\",\"iat\":$issuedAt,\"exp\":$expiresAt,\"jti\":\"$tokenId\"${notBefore?.let { ",\"nbf\":$it" } ?: ""}$extra}"
+
+    private fun signed(payload: String, header: String = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", key: ByteArray = KEY): String {
+        val encoder = Base64.getUrlEncoder().withoutPadding()
+        val unsigned = "${encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))}.${encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))}"
+        return "$unsigned.${signature(unsigned, key)}"
+    }
+
+    private fun tokenWithPayloadBytes(payload: ByteArray): String {
+        val encoder = Base64.getUrlEncoder().withoutPadding()
+        val unsigned = "${encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray(StandardCharsets.UTF_8))}.${encoder.encodeToString(payload)}"
+        return "$unsigned.${signature(unsigned, KEY)}"
+    }
+
+    private fun nonCanonicalPayloadToken(): String {
+        val encoder = Base64.getUrlEncoder().withoutPadding()
+        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray(StandardCharsets.UTF_8))
+        val payload = encoder.encodeToString(payload().toByteArray(StandardCharsets.UTF_8)) + "="
+        val unsigned = "$header.$payload"
+        return "$unsigned.${signature(unsigned, KEY)}"
+    }
+
+    private fun signature(unsigned: String, key: ByteArray): String = Mac.getInstance("HmacSHA256").run {
+        init(SecretKeySpec(key, "HmacSHA256"))
+        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII)))
+    }
+
+    private companion object {
+        val NOW: Instant = Instant.parse("2026-07-26T12:00:00Z")
+        val KEY = ByteArray(32) { 7 }
+        const val ISSUER = "inplacex-test"
+        const val AUDIENCE = "inplacex-client"
+        const val SUBJECT = "10000000-0000-4000-8000-000000000001"
+        const val OTHER_SUBJECT = "10000000-0000-4000-8000-000000000002"
+        const val TOKEN_ID = "20000000-0000-4000-8000-000000000002"
+    }
+}
diff --git a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
index a4a52fc84182c25c18c7f7d2ee2e75f972fe699a..2c58f8e43129452c8c21e6292266c2cd2c3cdaac
--- a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
+++ b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
@@ -1,5 +1,6 @@
 package com.mirkori.inplacex.backend.identity

+import com.mirkori.inplacex.backend.auth.JwtAccessTokenService
 import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
 import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
 import com.mirkori.inplacex.logging.InplaceXLogger
@@ -12,6 +13,7 @@
 import org.junit.Assert.assertThrows
 import org.junit.Assert.assertTrue
 import org.junit.Test
+import java.lang.reflect.InvocationTargetException
 import java.time.Clock
 import java.time.Instant
 import java.time.ZoneOffset
@@ -126,9 +128,70 @@
         assertFalse(renderedEvents.contains(installation))
         assertFalse(renderedEvents.contains(bootstrap.credentials.accessToken))
         assertFalse(renderedEvents.contains(bootstrap.credentials.refreshToken))
+        assertFalse(renderedEvents.contains(bootstrap.playerId))
         assertFalse(renderedEvents.contains("private-marker"))
     }

+    @Test
+    fun `identity-owned issuance accepts only registered opaque grants and exposes no raw issuer`() {
+        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
+        val service = service(clock)
+        val bootstrap = service.bootstrap(bootstrap("installation-auth"))
+        val authenticationField = GuestIdentityService::class.java.declaredFields.single { it.name == "authentication" }
+        assertTrue(authenticationField.trySetAccessible())
+        val authentication = authenticationField.get(service) as JwtAccessTokenService
+        val principal = authentication.authenticate(bootstrap.credentials.accessToken)
+
+        assertTrue(authentication.accepts(principal, bootstrap.playerId))
+        assertFalse(GuestIdentityService::class.java.declaredFields.any { it.name.contains("issuer", ignoreCase = true) })
+        GuestIdentityService::class.java.declaredMethods.forEach { method ->
+            assertFalse(
+                "raw identity can reach an issuer: $method",
+                (method.name.contains("issue", ignoreCase = true) || method.name == "credentialsFor") &&
+                    method.parameterTypes.any(::rawIdentity),
+            )
+        }
+
+        val grantClass = Class.forName("com.mirkori.inplacex.backend.identity.IdentityTokenGrant")
+        grantClass.declaredConstructors.forEach { constructor ->
+            assertFalse("raw grant constructor: $constructor", constructor.parameterTypes.any(::rawIdentity))
+            assertTrue(constructor.trySetAccessible())
+            val forgedGrant = constructor.newInstance(*constructor.parameterTypes.map(::defaultValue).toTypedArray())
+            val issue = GuestIdentityService::class.java.declaredMethods.single { it.name == "issueAccessToken" }
+            assertTrue(issue.trySetAccessible())
+            assertThrows(InvocationTargetException::class.java) {
+                issue.invoke(service, forgedGrant, clock.instant(), clock.instant().plusSeconds(60))
+            }
+        }
+        val renewalGrantClass = Class.forName("com.mirkori.inplacex.backend.identity.RenewableCredentialGrant")
+        val forgedIdentityGrant = grantClass.declaredConstructors.single().apply { trySetAccessible() }.newInstance()
+        val forgedRenewalGrant = renewalGrantClass.declaredConstructors.single().apply { trySetAccessible() }.newInstance()
+        val credentialsFor = GuestIdentityService::class.java.declaredMethods.single { it.name == "credentialsFor" }
+        assertTrue(credentialsFor.trySetAccessible())
+        assertThrows(InvocationTargetException::class.java) {
+            credentialsFor.invoke(service, forgedIdentityGrant, forgedRenewalGrant)
+        }
+    }
+
+    private fun rawIdentity(type: Class<*>): Boolean =
+        type == String::class.java ||
+            type == java.util.UUID::class.java ||
+            type == ByteArray::class.java ||
+            type.name == "kotlin.jvm.internal.DefaultConstructorMarker"
+
+    private fun defaultValue(type: Class<*>): Any? = when {
+        !type.isPrimitive -> null
+        type == Boolean::class.javaPrimitiveType -> false
+        type == Char::class.javaPrimitiveType -> '\u0000'
+        type == Byte::class.javaPrimitiveType -> 0.toByte()
+        type == Short::class.javaPrimitiveType -> 0.toShort()
+        type == Int::class.javaPrimitiveType -> 0
+        type == Long::class.javaPrimitiveType -> 0L
+        type == Float::class.javaPrimitiveType -> 0f
+        type == Double::class.javaPrimitiveType -> 0.0
+        else -> null
+    }
+
     private fun bootstrap(installationId: String) = GuestBootstrapCommand(
         installationId = installationId,
         platform = GuestPlatform.ANDROID,
diff --git a/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T132502Z.md b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T132502Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..5c6c320b3e89d2c91bdace4acb48fde85684cdcf
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T132502Z.md
@@ -0,0 +1,48 @@
+# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP
+
+- `check_status`: passed
+- `integration_status`: integration_requested
+- `base`: `1b18cd9f97ba3882d2bffb7d103159b5ba5fcfb0` (`origin/develop`)
+
+## Delivered scope
+
+- Added an authentication-owned opaque `AuthenticatedPrincipal`. The verifier
+  records the principal only in its own instance-local registry, so a
+  reflection-created or foreign-verifier principal is rejected.
+- Added strict HS256 JWT verification with canonical Base64URL, a UTF-8
+  decoder configured with `CodingErrorAction.REPORT`, duplicate/unknown claim
+  rejection, issuer/audience/time/token-id/subject/account-state checks and
+  redacted rejection logging.
+- Replaced the raw-subject private issuer with identity-owned opaque grants.
+  No issuer method accepts a `String`, `UUID` or `ByteArray` identity; forged
+  grants and reflective calls fail closed.
+- Removed player identifiers from identity logs. No session membership,
+  transport, persistence implementation, schema or duel code was changed.
+
+## Verification
+
+All commands used process-only toolchain configuration:
+
+```text
+JAVA_HOME=/home/main/.local/jdk21
+JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
+ANDROID_HOME=/home/main/.local/android-sdk
+ANDROID_SDK_ROOT=/home/main/.local/android-sdk
+```
+
+| Command | Outcome |
+| --- | --- |
+| `bash gradlew :InplaceX-backend:test --rerun-tasks` | passed; 61 tests |
+| `bash gradlew verifyProject` | passed |
+| `git diff --check` | passed |
+| `/home/main/.local/jdk21/bin/javap -p -s -classpath InplaceX-backend/build/classes/kotlin/main com.mirkori.inplacex.backend.auth.JwtAccessTokenService` | inspected: no public/internal/synthetic token-issuance method |
+
+The hostile tests enumerate declared constructors and methods without filtering
+synthetic members, invoke reflected principal/grant paths, cover a foreign JWT
+service, malformed UTF-8, duplicate/unknown/noncanonical/oversized claims,
+time/account failures, and redaction.
+
+## Handoff
+
+`integration_requested` for independent strong review. S25B remains responsible
+for canonical server-owned session membership composition.

tokens used
145 391

```
