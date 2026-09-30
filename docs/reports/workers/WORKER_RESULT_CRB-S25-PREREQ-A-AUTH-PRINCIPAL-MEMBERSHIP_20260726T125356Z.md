# Worker Result CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- Generated: `2026-07-26T12:53:56Z`
- Worker: `auto-worker-5.5`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-princip-retry-20260726T124353Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T124355Z`
- Result: `needs_worker_fix`
- Check evidence: `partial`
- Next owner: `worker`

## Check Evidence

```json
{
  "ok": false,
  "route": "needs_worker_fix",
  "check_status": "partial",
  "integration_status": "needs_worker_fix",
  "reason": "worker explicitly reported required checks as partial",
  "negative_markers": [],
  "positive_markers": [
    "passed",
    "git diff --check"
  ],
  "explicit_check_statuses": [
    "partial"
  ]
}
```

## stdout

```text
Статус пакета: `check_status=partial`.

Реализованы JWT verification, opaque principal registry, guest identity grant и route-bound membership capability. Целевые проверки прошли:

- `bash gradlew :InplaceX-backend:test --rerun-tasks` — passed, 62 tests.
- `bash gradlew verifyProject` — passed.
- `git diff --check` — passed.
- `javap -p -s ...JwtAccessTokenService ...AuthenticatedPrincipal ...SessionMembershipResolver` — выполнен.

Но пакет не завершён безопасно: публичная composition-фабрика membership всё ещё принимает `SessionMembershipPort`, что не соответствует требованию о неэкспортируемом canonical authority. Поэтому `integration_requested` не отправлялся.

Отчёт: [WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T125500Z.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T124355Z/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T125500Z.md).

```

## stderr

```text
d(audience = "other")),
+            signed(payload(subject = "not-a-uuid")),
+            signed(payload(tokenId = "00000000-0000-0000-0000-000000000000")),
+            signed(payload(issuedAt = NOW.epochSecond + 1)),
+            signed(payload(expiresAt = NOW.epochSecond)),
+            "x".repeat(4_097),
+        ).forEach { token ->
+            assertThrows(AccessTokenRejectedException::class.java) { service.authenticate(token) }
+        }
+        assertThrows(AccessTokenRejectedException::class.java) {
+            service(PlayerAccountStatus.INACTIVE).authenticate(signed(payload()))
+        }
+    }
+
+    @Test
+    fun `all declared constructors and methods reject hostile raw authority paths`() {
+        val service = service()
+        val genuine = service.authenticate(signed(payload()))
+        AuthenticatedPrincipal::class.java.declaredConstructors.forEach { constructor ->
+            assertFalse(constructor.parameterTypes.any(::rawIdentity))
+            assertTrue(constructor.trySetAccessible())
+            val forged = constructor.newInstance() as AuthenticatedPrincipal
+            assertFalse(service.accepts(forged, SUBJECT))
+        }
+        JwtAccessTokenService::class.java.declaredConstructors.forEach { constructor ->
+            assertFalse(constructor.parameterTypes.any(::rawIdentity))
+        }
+        JwtAccessTokenService::class.java.declaredMethods.forEach { method ->
+            assertFalse("issuer leaked: $method", method.name.contains("issue", ignoreCase = true))
+            if (method.returnType == AuthenticatedPrincipal::class.java) {
+                assertTrue(method.trySetAccessible())
+                assertThrows(Throwable::class.java) { method.invoke(service, SUBJECT) }
+            }
+            if (method.returnType == Boolean::class.javaPrimitiveType && method.parameterTypes.contains(String::class.java)) {
+                assertTrue(method.trySetAccessible())
+                assertFalse(method.invoke(service, genuine, OTHER_SUBJECT) as Boolean)
+            }
+        }
+    }
+
+    private fun rawIdentity(type: Class<*>): Boolean =
+        type == String::class.java || type == java.util.UUID::class.java || type.name == "kotlin.jvm.internal.DefaultConstructorMarker"
+
+    private fun service(status: PlayerAccountStatus = PlayerAccountStatus.ACTIVE) = JwtAccessTokenService(
+        signingSecret = KEY,
+        policy = JwtAccessTokenPolicy(ISSUER, AUDIENCE, Duration.ofMinutes(15)),
+        accountStatus = PlayerAccountStatusResolver { status },
+        clock = Clock.fixed(NOW, ZoneOffset.UTC),
+        logger = com.mirkori.inplacex.logging.InplaceXLogger(),
+    )
+
+    private fun payload(
+        issuer: String = ISSUER,
+        audience: String = AUDIENCE,
+        subject: String = SUBJECT,
+        issuedAt: Long = NOW.epochSecond,
+        expiresAt: Long = NOW.epochSecond + 900,
+        tokenId: String = TOKEN_ID,
+    ) = "{\"iss\":\"$issuer\",\"aud\":\"$audience\",\"sub\":\"$subject\",\"iat\":$issuedAt,\"exp\":$expiresAt,\"jti\":\"$tokenId\"}"
+
+    private fun signed(payload: String, header: String = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", key: ByteArray = KEY): String {
+        val encoder = Base64.getUrlEncoder().withoutPadding()
+        val unsigned = "${encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))}.${encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))}"
+        val signature = Mac.getInstance("HmacSHA256").run {
+            init(SecretKeySpec(key, "HmacSHA256")); encoder.encodeToString(doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII)))
+        }
+        return "$unsigned.$signature"
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
diff --git a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..7dfb607e0f50d5c49e2faeaf168665db1fa151e8
--- /dev/null
+++ b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt
@@ -0,0 +1,75 @@
+package com.mirkori.inplacex.backend.session.authorization
+
+import com.mirkori.inplacex.backend.auth.JwtAccessTokenPolicy
+import com.mirkori.inplacex.backend.auth.JwtAccessTokenService
+import com.mirkori.inplacex.backend.auth.PlayerAccountStatus
+import com.mirkori.inplacex.backend.auth.PlayerAccountStatusResolver
+import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
+import com.mirkori.inplacex.backend.session.contract.PublicSessionId
+import com.mirkori.inplacex.logging.InplaceXLogger
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertThrows
+import org.junit.Test
+import java.nio.charset.StandardCharsets
+import java.time.Clock
+import java.time.Instant
+import java.time.ZoneOffset
+import java.util.Base64
+import javax.crypto.Mac
+import javax.crypto.spec.SecretKeySpec
+
+class SessionMembershipResolverTest {
+    @Test
+    fun `canonical resolver accepts only its auth principal and binds capability to route`() {
+        val canonical = auth(KEY)
+        val foreign = auth(ByteArray(32) { 9 })
+        val resolver = SessionMembershipResolver.canonical(
+            trustedAuthentication = canonical,
+            memberships = SessionMembershipPort { listOf(SessionMembershipRecord(SESSION, PARTICIPANT, SUBJECT)) },
+        )
+        val capability = resolver.resolve(canonical.authenticate(token(KEY)), SESSION)
+        assertEquals(PARTICIPANT, resolver.participantIdFor(capability, SESSION))
+        assertThrows(SessionMembershipRejectedException::class.java) { resolver.participantIdFor(capability, OTHER_SESSION) }
+        assertThrows(SessionMembershipRejectedException::class.java) { resolver.resolve(foreign.authenticate(token(ByteArray(32) { 9 })), SESSION) }
+    }
+
+    @Test
+    fun `caller owned resolver capability is rejected by canonical consumer`() {
+        val authentication = auth(KEY)
+        val canonical = SessionMembershipResolver.canonical(
+            memberships = SessionMembershipPort { listOf(SessionMembershipRecord(SESSION, PARTICIPANT, SUBJECT)) },
+            trustedAuthentication = authentication,
+        )
+        val attacker = SessionMembershipResolver.canonical(
+            memberships = SessionMembershipPort { listOf(SessionMembershipRecord(SESSION, ATTACKER_PARTICIPANT, SUBJECT)) },
+            trustedAuthentication = authentication,
+        )
+        val principal = authentication.authenticate(token(KEY))
+        val forged = attacker.resolve(principal, SESSION)
+        assertThrows(SessionMembershipRejectedException::class.java) { canonical.participantIdFor(forged, SESSION) }
+    }
+
+    private fun auth(key: ByteArray) = JwtAccessTokenService(
+        key, JwtAccessTokenPolicy("issuer", "audience"), PlayerAccountStatusResolver { PlayerAccountStatus.ACTIVE }, Clock.fixed(NOW, ZoneOffset.UTC), InplaceXLogger(),
+    )
+
+    private fun token(key: ByteArray): String {
+        val encoder = Base64.getUrlEncoder().withoutPadding()
+        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray(StandardCharsets.UTF_8))
+        val payload = encoder.encodeToString("{\"iss\":\"issuer\",\"aud\":\"audience\",\"sub\":\"$SUBJECT\",\"iat\":${NOW.epochSecond},\"exp\":${NOW.epochSecond + 900},\"jti\":\"$TOKEN\"}".toByteArray(StandardCharsets.UTF_8))
+        val unsigned = "$header.$payload"
+        val signature = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); encoder.encodeToString(doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII))) }
+        return "$unsigned.$signature"
+    }
+
+    private companion object {
+        val NOW: Instant = Instant.parse("2026-07-26T12:00:00Z")
+        val KEY = ByteArray(32) { 7 }
+        val SESSION = PublicSessionId.parse("30000000-0000-4000-8000-000000000003")
+        val OTHER_SESSION = PublicSessionId.parse("30000000-0000-4000-8000-000000000004")
+        val PARTICIPANT = PublicParticipantId.parse("40000000-0000-4000-8000-000000000005")
+        val ATTACKER_PARTICIPANT = PublicParticipantId.parse("40000000-0000-4000-8000-000000000006")
+        const val SUBJECT = "10000000-0000-4000-8000-000000000001"
+        const val TOKEN = "20000000-0000-4000-8000-000000000002"
+    }
+}
diff --git a/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T125500Z.md b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T125500Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..677cd5586b125bf90f4e82fa9993952bbec84951
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T125500Z.md
@@ -0,0 +1,39 @@
+# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP
+
+- Status: `needs_worker_fix`
+- Check status: `partial`
+- Base: `7f8e020dda690d75589cd0ee8e2ecf49fce37740`
+
+## Implemented evidence
+
+- Added strict HS256 JWT verification with issuer, audience, canonical subject,
+  token-id, time-window, duplicate-claim, bounded-input and active-account
+  checks. A verifier keeps accepted principals in an instance-local registry;
+  reflected or foreign-service principals are not accepted.
+- Moved guest access-token signing behind a private identity-grant registry so
+  the signer consumes an opaque registered grant rather than a raw public
+  subject argument.
+- Added route-bound membership capability checks, including a foreign verifier
+  and a caller-owned resolver capability rejected by the trusted resolver.
+- `javap -p -s` confirms that public `AuthenticatedPrincipal` is an interface
+  without constructors and `JwtAccessTokenService` has no token-issuance
+  method.
+
+## Remaining blocker
+
+The packet requires a non-exported canonical session-authorization authority
+whose membership port cannot be supplied by arbitrary JVM callers, while the
+current allowed paths contain no constructible session repository/composition
+owner. The submitted resolver keeps a private implementation and binds every
+capability to its issuer, but its public composition factory still accepts
+`SessionMembershipPort`; this does not meet the packet's stricter ownership
+criterion. Do not integrate this candidate as the prerequisite is not fully
+complete. Route to Dispatcher/Architect for a packet that names the canonical
+composition owner or permits the required session composition path.
+
+## Commands
+
+1. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks` — passed (62 tests).
+2. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject` — passed.
+3. `git diff --check` — passed.
+4. `/home/main/.local/jdk21/bin/javap -p -s -classpath InplaceX-backend/build/classes/kotlin/main com.mirkori.inplacex.backend.auth.JwtAccessTokenService com.mirkori.inplacex.backend.auth.AuthenticatedPrincipal com.mirkori.inplacex.backend.session.authorization.SessionMembershipResolver` — completed; evidence above.

tokens used
145 823

```
