# Worker Result CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- Generated: `2026-07-26T12:11:35Z`
- Worker: `auto-worker-5.5`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-princip-retry-20260726T115946Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T115949Z`
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
Готово: безопасный JVM prerequisite для JWT principal и membership boundary реализован. Caller-owned membership port/record удалён из поверхности, token issuance остался private внутри identity service.

check_status=passed
Handoff: `integration_requested` (требуется strong integration review).

Проверки прошли:

- `JAVA_HOME=... bash gradlew :InplaceX-backend:test --rerun-tasks` — passed, 63 tests.
- `JAVA_HOME=... bash gradlew verifyProject` — passed.
- `git diff --check` — passed.
- `javap -p` JVM-аудит — principal/capability не имеют конструкторов с raw id или `DefaultConstructorMarker`.

Worker report: [WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T121037Z.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T115949Z/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T121037Z.md).

```

## stderr

```text
     val principal = service.authenticate(token)
+
+        assertTrue(service.acceptsPrincipalForSubject(principal, PLAYER_A))
+        assertFalse(principal.toString().contains(PLAYER_A))
+        assertFalse(service.toString().contains(PLAYER_A))
+    }
+
+    @Test
+    fun `rejects malformed duplicate oversized inactive and invalid claim tokens`() {
+        val service = service(SIGNING_KEY)
+        val inactive = service(SIGNING_KEY, PlayerAccountStatus.INACTIVE)
+        val invalid = listOf(
+            "not-a-token",
+            "x".repeat(4_097),
+            signedToken(PLAYER_A, ATTACKER_KEY),
+            signedToken(PLAYER_A, SIGNING_KEY, header = "{\"alg\":\"none\",\"typ\":\"JWT\"}"),
+            signedToken(PLAYER_A, SIGNING_KEY, payload = payload(PLAYER_A).replace("\"aud\":\"$AUDIENCE\",", "")),
+            signedToken(PLAYER_A, SIGNING_KEY, payload = payload(PLAYER_A).replace("\"sub\":\"$PLAYER_A\"", "\"sub\":\"$PLAYER_A\",\"sub\":\"$PLAYER_A\"")),
+            signedToken("10000000-0000-4000-8000-00000000000A", SIGNING_KEY),
+        )
+
+        invalid.forEach { token -> assertThrows(AccessTokenRejectedException::class.java) { service.authenticate(token) } }
+        assertThrows(AccessTokenRejectedException::class.java) { inactive.authenticate(signedToken(PLAYER_A, SIGNING_KEY)) }
+    }
+
+    @Test
+    fun `principal is pinned to its issuing authentication authority`() {
+        val canonical = service(SIGNING_KEY)
+        val attacker = service(ATTACKER_KEY)
+        val foreignPrincipal = attacker.authenticate(signedToken(PLAYER_A, ATTACKER_KEY))
+
+        assertFalse(canonical.acceptsPrincipalForSubject(foreignPrincipal, PLAYER_A))
+    }
+
+    @Test
+    fun `public jvm surface cannot mint tokens or principals from raw identities`() {
+        JwtAccessTokenService::class.java.declaredConstructors.forEach { constructor ->
+            assertFalse(Modifier.isPublic(constructor.modifiers) && constructor.parameterTypes.contains(String::class.java))
+        }
+        JwtAccessTokenService::class.java.declaredMethods.forEach { method ->
+            val createsAuthority = AuthenticatedPrincipal::class.java.isAssignableFrom(method.returnType) || method.returnType == String::class.java
+            if (createsAuthority && method.name != "authenticate") {
+                assertFalse("raw issuer method: $method", method.parameterTypes.contains(String::class.java))
+            }
+        }
+        assertFalse(Modifier.isAbstract(AuthenticatedPrincipal::class.java.modifiers))
+        AuthenticatedPrincipal::class.java.declaredConstructors.forEach { constructor ->
+            assertFalse(constructor.parameterTypes.any { it == String::class.java || it.name == "kotlin.jvm.internal.DefaultConstructorMarker" })
+            val forged = constructor.newInstance(service(SIGNING_KEY)) as AuthenticatedPrincipal
+            assertFalse(service(SIGNING_KEY).acceptsPrincipalForSubject(forged, PLAYER_A))
+        }
+    }
+
+    private fun service(key: ByteArray, status: PlayerAccountStatus = PlayerAccountStatus.ACTIVE) = JwtAccessTokenService(
+        signingSecret = key,
+        policy = JwtAccessTokenPolicy(ISSUER, AUDIENCE, Duration.ofMinutes(15)),
+        accountStatus = PlayerAccountStatusResolver { status },
+        clock = Clock.fixed(NOW, ZoneOffset.UTC),
+        logger = InplaceXLogger(),
+    )
+
+    private fun signedToken(
+        subject: String,
+        key: ByteArray,
+        header: String = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
+        payload: String = payload(subject),
+    ): String {
+        val encoder = Base64.getUrlEncoder().withoutPadding()
+        val encodedHeader = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))
+        val encodedPayload = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
+        val unsigned = "$encodedHeader.$encodedPayload"
+        val signature = Mac.getInstance("HmacSHA256").run {
+            init(SecretKeySpec(key, "HmacSHA256"))
+            encoder.encodeToString(doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII)))
+        }
+        return "$unsigned.$signature"
+    }
+
+    private fun payload(subject: String) =
+        """{"iss":"$ISSUER","aud":"$AUDIENCE","sub":"$subject","iat":${NOW.epochSecond},"exp":${NOW.epochSecond + 900},"jti":"$TOKEN_ID"}"""
+
+    private companion object {
+        val NOW: Instant = Instant.parse("2026-07-26T12:00:00Z")
+        val SIGNING_KEY: ByteArray = ByteArray(32) { 7 }
+        val ATTACKER_KEY: ByteArray = ByteArray(32) { 9 }
+        const val ISSUER = "inplacex-test"
+        const val AUDIENCE = "inplacex-client"
+        const val PLAYER_A = "10000000-0000-4000-8000-000000000001"
+        const val TOKEN_ID = "20000000-0000-4000-8000-000000000003"
+    }
+}
diff --git a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
index a4a52fc84182c25c18c7f7d2ee2e75f972fe699a..e32dd74090d5816d27d019dc5318c42c80329c6b
--- a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
+++ b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
@@ -32,7 +32,9 @@
         assertEquals(clock.instant().plusSeconds(30L * 24 * 60 * 60), first.credentials.refreshExpiresAt)
         assertNotEquals(first.credentials.refreshToken, repeated.credentials.refreshToken)
         assertFalse(first.credentials.toString().contains(first.credentials.refreshToken))
+        assertFalse(first.toString().contains(first.playerId))
         assertEquals(3, first.credentials.accessToken.split('.').size)
+        assertFalse(service.authenticateAccessToken(first.credentials.accessToken).toString().contains(first.playerId))
     }

     @Test
diff --git a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..184a970088fad82e1914082753d759deabbf1f24
--- /dev/null
+++ b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt
@@ -0,0 +1,34 @@
+package com.mirkori.inplacex.backend.session.authorization
+
+import com.mirkori.inplacex.backend.session.contract.PublicSessionId
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertThrows
+import org.junit.Assert.assertTrue
+import org.junit.Test
+import java.lang.reflect.Modifier
+
+class SessionMembershipResolverTest {
+    @Test
+    fun `membership boundary exposes neither caller owned port nor participant record factory`() {
+        assertThrows(ClassNotFoundException::class.java) {
+            Class.forName("com.mirkori.inplacex.backend.session.authorization.SessionMembershipPort")
+        }
+        assertThrows(ClassNotFoundException::class.java) {
+            Class.forName("com.mirkori.inplacex.backend.session.authorization.SessionMembershipRecord")
+        }
+        assertTrue(Modifier.isAbstract(SessionMembershipResolver::class.java.modifiers))
+        SessionMembershipResolver::class.java.declaredConstructors.forEach { constructor ->
+            assertFalse(Modifier.isPublic(constructor.modifiers))
+            assertFalse(constructor.parameterTypes.any { it == String::class.java || it.name == "kotlin.jvm.internal.DefaultConstructorMarker" })
+        }
+    }
+
+    @Test
+    fun `forged capability cannot be moved to a route or reveal participant identity`() {
+        assertFalse(Modifier.isAbstract(SessionParticipantCapability::class.java.modifiers))
+        SessionParticipantCapability::class.java.declaredConstructors.forEach { constructor ->
+            assertFalse(Modifier.isPublic(constructor.modifiers))
+            assertFalse(constructor.parameterTypes.any { it == String::class.java || it.name == "kotlin.jvm.internal.DefaultConstructorMarker" })
+        }
+    }
+}
diff --git a/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T121037Z.md b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T121037Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..b53ee587612085cd4e873b75c593054bc747a2ff
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T121037Z.md
@@ -0,0 +1,66 @@
+# Worker result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP
+
+## Outcome
+
+`check_status=passed`
+
+The retry establishes a fail-closed JVM authentication prerequisite. JWT
+verification creates a principal only after signature, issuer, audience,
+canonical subject/token id, time claims and active-player checks. A principal
+constructed reflectively with an authentication service is not registered by
+that service and therefore cannot match any subject.
+
+Guest token issuance remains exclusively inside `GuestIdentityService`: its
+private issuer consumes an identity-owned opaque grant, rather than a raw
+player id. Tokens, principals and guest bootstrap results redact their
+`toString()` output; identity logs now use outcome-only attributes.
+
+The session authorization surface contains no caller-owned membership port,
+record, factory or participant-id input. `SessionMembershipResolver` and its
+participant capability have non-public JVM constructors, so a later canonical
+session repository must supply the sole concrete resolver without accepting
+transport-controlled participant identities. Until that integration exists,
+capability unwrapping fails closed.
+
+## Changed paths
+
+- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/auth/AuthenticatedPrincipal.kt`
+- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/auth/JwtAccessTokenService.kt`
+- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityService.kt`
+- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolver.kt`
+- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/auth/JwtAccessTokenServiceTest.kt`
+- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt`
+- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt`
+
+## Evidence
+
+All Gradle checks used process-only environment settings required by the task:
+
+```text
+JAVA_HOME=/home/main/.local/jdk21
+JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
+ANDROID_HOME=/home/main/.local/android-sdk
+ANDROID_SDK_ROOT=/home/main/.local/android-sdk
+```
+
+| Command | Result |
+| --- | --- |
+| `bash gradlew :InplaceX-backend:test --rerun-tasks` | passed (63 tests) |
+| `bash gradlew verifyProject` | passed |
+| `git diff --check` | passed |
+| `/home/main/.local/jdk21/bin/javap -p -classpath InplaceX-backend/build/classes/kotlin/main ...` | passed: no principal or capability constructor accepts `String`, UUID, participant id, session id or `DefaultConstructorMarker` |
+
+The hostile tests cover malformed, oversized, duplicate-claim, wrong-key,
+wrong-algorithm, active-state and cross-authority JWT cases; reflection-created
+principal rejection; redaction; and absence of caller-owned membership ports,
+records and resolver constructors.
+
+## Handoff
+
+`integration_requested`
+
+Strong integration review is required by the task packet. The integrator should
+retain the fail-closed membership boundary until the canonical session
+repository/authorization implementation is wired in its own allowed package;
+do not reintroduce a public membership port, caller-controlled resolver or
+participant-id input.

tokens used
167 944

```
