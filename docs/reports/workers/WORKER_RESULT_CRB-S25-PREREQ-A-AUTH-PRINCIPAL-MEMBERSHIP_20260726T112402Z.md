# Worker Result CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- Generated: `2026-07-26T11:24:02Z`
- Worker: `auto-worker-5.5`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-princip-retry-20260726T111228Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T111231Z`
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
Готово: реализована auth-owned JWT principal и закреплённая проверка session membership.

- JWT строго проверяет HS256, claims, время, canonical UUID и active account.
- Principal/capability registry-backed; reflection/proxy и foreign auth-service не проходят.
- Token issuance остаётся JVM-private и identity-owned.
- Добавлен worker report: [WORKER_RESULT…md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T111231Z/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T112311Z.md)

Проверки:

- `bash gradlew :InplaceX-backend:test --rerun-tasks` — passed.
- `bash gradlew verifyProject` — passed.
- `javap -p -s …` для JVM-поверхностей capability — inspected.
- `git diff --check` — passed.

`check_status=passed`

```

## stderr

```text
      val attacker = service(ATTACKER_KEY)
+        val resolver = resolver(canonical)
+
+        resolver.resolve(canonical.authenticate(signed(PLAYER_A, SIGNING_KEY)), SESSION_A)
+        assertThrows(SessionMembershipRejectedException::class.java) {
+            resolver.resolve(attacker.authenticate(signed(PLAYER_A, ATTACKER_KEY)), SESSION_A)
+        }
+    }
+
+    @Test
+    fun `wrong player session nonmember proxy and forged capability are rejected`() {
+        val authentication = service(SIGNING_KEY)
+        val principal = authentication.authenticate(signed(PLAYER_A, SIGNING_KEY))
+        val wrongPlayer = SessionMembershipResolver(
+            SessionMembershipPort { listOf(record(SESSION_A, PARTICIPANT_B, PLAYER_B)) }, authentication,
+        )
+        val wrongSession = SessionMembershipResolver(
+            SessionMembershipPort { listOf(record(SESSION_B, PARTICIPANT_A, PLAYER_A)) }, authentication,
+        )
+        listOf(wrongPlayer, wrongSession).forEach { resolver ->
+            assertThrows(SessionMembershipRejectedException::class.java) { resolver.resolve(principal, SESSION_A) }
+        }
+        assertThrows(SessionMembershipRejectedException::class.java) {
+            resolver(authentication).resolve(forgedPrincipal(), SESSION_A)
+        }
+        assertThrows(SessionMembershipRejectedException::class.java) {
+            forgedCapability().participantIdFor(SESSION_A)
+        }
+    }
+
+    @Test
+    fun `hostile reflection invokes every authority constructor and raw method without binding`() {
+        val authentication = service(SIGNING_KEY)
+        val resolver = resolver(authentication)
+        val capability = resolver.resolve(authentication.authenticate(signed(PLAYER_A, SIGNING_KEY)), SESSION_A)
+        val implementation = capability.javaClass
+
+        assertTrue(SessionParticipantCapability::class.java.declaredConstructors.isEmpty())
+        assertFalse(Modifier.isPublic(implementation.modifiers))
+        implementation.declaredConstructors.forEach { constructor ->
+            assertFalse(constructor.parameterTypes.any(::isRawAuthorityType))
+            assertTrue(constructor.trySetAccessible())
+            val forged = constructor.newInstance(resolver) as SessionParticipantCapability
+            assertThrows(SessionMembershipRejectedException::class.java) { forged.participantIdFor(SESSION_A) }
+        }
+        SessionMembershipResolver::class.java.declaredConstructors.forEach { constructor ->
+            assertFalse(constructor.parameterTypes.any {
+                isRawAuthorityType(it) && it.name != "kotlin.jvm.internal.DefaultConstructorMarker"
+            })
+            assertTrue(constructor.trySetAccessible())
+            val arguments = constructor.parameterTypes.map { parameter ->
+                when (parameter) {
+                    SessionMembershipPort::class.java -> SessionMembershipPort { emptyList() }
+                    JwtAccessTokenService::class.java -> authentication
+                    InplaceXLogger::class.java -> InplaceXLogger()
+                    Int::class.javaPrimitiveType -> 0
+                    else -> if (parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker") null
+                    else throw AssertionError("Unexpected resolver constructor: $constructor")
+                }
+            }.toTypedArray()
+            constructor.newInstance(*arguments)
+        }
+        SessionMembershipResolver::class.java.declaredMethods.forEach { method ->
+            assertFalse(
+                "caller participant id is accepted: $method",
+                method.parameterTypes.contains(PublicParticipantId::class.java),
+            )
+            if (
+                method.parameterTypes.any { it == SessionParticipantCapability::class.java || it == PublicSessionId::class.java } ||
+                method.name.startsWith("resolve") && method.parameterTypes.contains(AuthenticatedPrincipal::class.java)
+            ) {
+                assertTrue(method.trySetAccessible())
+                val args = method.parameterTypes.map { parameter ->
+                    when (parameter) {
+                        SessionParticipantCapability::class.java -> forgedCapability()
+                        PublicSessionId::class.java -> SESSION_A
+                        AuthenticatedPrincipal::class.java -> forgedPrincipal()
+                        String::class.java -> SESSION_A.value
+                        else -> throw AssertionError("Unexpected resolver method: $method")
+                    }
+                }.toTypedArray()
+                val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(resolver, *args) }
+                assertTrue(failure.cause is SessionMembershipRejectedException)
+            }
+        }
+        SessionMembershipRecord::class.java.declaredConstructors.forEach { constructor ->
+            assertTrue(constructor.trySetAccessible())
+            var stringIndex = 0
+            val forgedRecord = constructor.newInstance(*constructor.parameterTypes.map { parameter ->
+                when {
+                    parameter == String::class.java -> listOf(SESSION_A.value, PARTICIPANT_A.value, PLAYER_A)[stringIndex++]
+                    parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker" -> null
+                    else -> throw AssertionError("Unexpected membership constructor: $constructor")
+                }
+            }.toTypedArray()) as SessionMembershipRecord
+            assertFalse(forgedRecord.belongsTo(authentication, forgedPrincipal()))
+        }
+    }
+
+    private fun resolver(authentication: JwtAccessTokenService) = SessionMembershipResolver(
+        SessionMembershipPort {
+            listOf(record(SESSION_A, PARTICIPANT_A, PLAYER_A), record(SESSION_A, PARTICIPANT_B, PLAYER_B))
+        },
+        authentication,
+    )
+
+    private fun record(session: PublicSessionId, participant: PublicParticipantId, player: String) =
+        SessionMembershipRecord(session, participant, player)
+
+    private fun forgedPrincipal(): AuthenticatedPrincipal = proxy(AuthenticatedPrincipal::class.java) as AuthenticatedPrincipal
+    private fun forgedCapability(): SessionParticipantCapability = proxy(SessionParticipantCapability::class.java) as SessionParticipantCapability
+    private fun proxy(type: Class<*>): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
+        if (method.name == "toString") "Forged([redacted])" else null
+    }
+
+    private fun isRawAuthorityType(type: Class<*>): Boolean =
+        type == String::class.java || type == java.util.UUID::class.java || type == ByteArray::class.java ||
+            type == PublicParticipantId::class.java || type == PublicSessionId::class.java ||
+            type.name == "kotlin.jvm.internal.DefaultConstructorMarker"
+
+    private fun service(key: ByteArray) = JwtAccessTokenService(
+        key,
+        JwtAccessTokenPolicy(ISSUER, AUDIENCE, Duration.ofMinutes(15)),
+        PlayerAccountStatusResolver { PlayerAccountStatus.ACTIVE },
+        Clock.fixed(NOW, ZoneOffset.UTC),
+        InplaceXLogger(),
+    )
+
+    private fun signed(subject: String, key: ByteArray): String {
+        val encoder = Base64.getUrlEncoder().withoutPadding()
+        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray(StandardCharsets.UTF_8))
+        val payload = encoder.encodeToString(
+            "{\"iss\":\"$ISSUER\",\"aud\":\"$AUDIENCE\",\"sub\":\"$subject\",\"iat\":${NOW.epochSecond},\"exp\":${NOW.epochSecond + 900},\"jti\":\"$TOKEN_ID\"}"
+                .toByteArray(StandardCharsets.UTF_8),
+        )
+        val unsigned = "$header.$payload"
+        val signature = Mac.getInstance("HmacSHA256").run {
+            init(SecretKeySpec(key, "HmacSHA256"))
+            encoder.encodeToString(doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII)))
+        }
+        return "$unsigned.$signature"
+    }
+
+    private companion object {
+        val NOW: Instant = Instant.parse("2026-07-26T11:00:00Z")
+        val SIGNING_KEY: ByteArray = ByteArray(32) { 7 }
+        val ATTACKER_KEY: ByteArray = ByteArray(32) { 9 }
+        const val ISSUER = "inplacex-test"
+        const val AUDIENCE = "inplacex-client"
+        const val PLAYER_A = "10000000-0000-4000-8000-000000000001"
+        const val PLAYER_B = "10000000-0000-4000-8000-000000000002"
+        const val TOKEN_ID = "20000000-0000-4000-8000-000000000003"
+        val SESSION_A = PublicSessionId.parse("30000000-0000-4000-8000-000000000004")
+        val SESSION_B = PublicSessionId.parse("30000000-0000-4000-8000-000000000005")
+        val PARTICIPANT_A = PublicParticipantId.parse("40000000-0000-4000-8000-000000000006")
+        val PARTICIPANT_B = PublicParticipantId.parse("40000000-0000-4000-8000-000000000007")
+    }
+}
diff --git a/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T112311Z.md b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T112311Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..8c974efc097472f0345cc998d92aa21d4a902712
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP_20260726T112311Z.md
@@ -0,0 +1,47 @@
+# Worker Result: CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP
+
+- Generated: `2026-07-26T11:23:11Z`
+- Worker: `auto-worker-5.5`
+- Result: `integration_requested`
+- Check status: `passed`
+- Next owner: `Integrator`
+
+## Delivered scope
+
+- Added a strict HS256 JWT authentication boundary that verifies signature,
+  algorithm, issuer, audience, canonical subject and token id, expiry,
+  issued-at/not-before policy, bounded token shape, and active account status.
+- Added registry-backed opaque authenticated-principal and session-participant
+  capabilities. Reflection-created instances and proxy implementations are not
+  registered and therefore cannot bind membership.
+- Kept access-token issuance JVM-private and identity-owned through a private,
+  registry-backed grant; no public or internal raw-subject token issuer exists.
+- Pinned `SessionMembershipResolver` to its canonical `JwtAccessTokenService`.
+  A principal authenticated by another service instance, including one with an
+  active resolver and a different valid signing key, is rejected.
+- Added hostile reflection tests without `isSynthetic` filtering, plus token,
+  inactive-account, route-bound and cross-authority regression coverage.
+
+## Verification
+
+1. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks`
+   — passed (`BUILD SUCCESSFUL`).
+2. `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject`
+   — passed (`BUILD SUCCESSFUL`).
+3. `/home/main/.local/jdk21/bin/javap -p -s -classpath InplaceX-backend/build/classes/kotlin/main ...`
+   — inspected principal, identity-grant and session-capability constructor and
+   method surfaces; no raw-identity token issuer was present.
+4. `git diff --check`
+   — passed.
+
+## Scope and handoff
+
+- Changed only packet-allowed backend auth, identity, session-authorization,
+  test and this worker-report paths.
+- No transport route, JDBC/schema, duel transition, persistence implementation,
+  Task Manager state, lock or event was changed.
+- The remote `develop` advance was inspected before implementation and affected
+  only runner-owned Task Manager state; the immutable execution base remained
+  valid for this packet.
+
+`check_status=passed`

tokens used
172 600

```
