# Worker Result INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S28

- Generated: `2026-07-26T20:48:41Z`
- Worker: `auto-worker-5.5`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio/auto-worker-5.5/intake-design-pd-inplacex-final-20260725-v002-s28/android-online-transport-foundation`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/intake-design-pd-inplacex-final-20260725-v002-s28-20260726T204325Z`
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
Готово: добавлен детерминированный online transport foundation без UI и реальных сетевых вызовов.

- REST/WS адаптеры с retry, offline-fail-fast и однократным refresh токена.
- WS использует `Authorization` header и `inplacex.online.v1`.
- Сохранён idempotency key при повторе.
- Добавлены unit-тесты для timeout/retry, refresh, offline и WS handshake.
- Логгер редактирует `guess`, `purchase`, `integrity`, `providerPayload`, `rawPayload`.

Проверки:

- `bash gradlew :app:testDebugUnitTest` — passed.
- `bash gradlew verifyProject` — passed.
- `git diff --check` — passed.

`check_status=passed`

Отчёт Worker и событие `integration_requested` не создавались: их пути не входят в разрешённые packet `allowed_paths`; центральный runner должен записать их при интеграции.

```

## stderr

```text
nnector.connect(request)) {
+                is OnlineWebSocketAttempt.Connected -> return OnlineTransportResult.Success(attempt.connection, attempts)
+                is OnlineWebSocketAttempt.Failed -> {
+                    if (attempt.code == OnlineFailureCode.UNAUTHORIZED && !refreshedToken) {
+                        refreshedToken = tokenStore.refreshAccessTokenOrNull() != null
+                        if (refreshedToken) continue
+                    }
+                    if (!attempt.code.isRetryable() || attempts == policy.maxAttempts) {
+                        return failure(attempt.code, attempts)
+                    }
+                    logRetry("websocket", attempt.code, attempts)
+                }
+            }
+        }
+
+        return failure(OnlineFailureCode.NETWORK, attempts)
+    }
+
+    private fun authenticatedRequestOrNull(request: RemoteRequestSpec): RemoteRequestSpec? {
+        if (!request.requiresAuthentication) return request
+        val accessToken = tokenStore.accessTokenOrNull() ?: return null
+        return request.copy(headers = request.headers + ("Authorization" to "Bearer $accessToken"))
+    }
+
+    private fun offline(attempts: Int): OnlineTransportResult.Failure =
+        failure(OnlineFailureCode.OFFLINE, attempts)
+
+    private fun authenticationUnavailable(attempts: Int): OnlineTransportResult.Failure =
+        failure(OnlineFailureCode.AUTHENTICATION_UNAVAILABLE, attempts)
+
+    private fun failure(code: OnlineFailureCode, attempts: Int): OnlineTransportResult.Failure {
+        logger.warn(
+            tag = LogTag,
+            message = "online transport request failed",
+            attributes = mapOf("failureCode" to code.name, "attempts" to attempts.toString()),
+        )
+        return OnlineTransportResult.Failure(code, attempts)
+    }
+
+    private fun logRetry(operation: String, code: OnlineFailureCode, attempts: Int) {
+        logger.warn(
+            tag = LogTag,
+            message = "online transport retry scheduled",
+            attributes = mapOf(
+                "operation" to operation,
+                "failureCode" to code.name,
+                "attempt" to attempts.toString(),
+            ),
+        )
+    }
+
+    private fun OnlineFailureCode.isRetryable(): Boolean = when (this) {
+        OnlineFailureCode.TIMEOUT,
+        OnlineFailureCode.NETWORK,
+        OnlineFailureCode.RATE_LIMITED,
+        OnlineFailureCode.OVERLOADED,
+        -> true
+
+        OnlineFailureCode.OFFLINE,
+        OnlineFailureCode.AUTHENTICATION_UNAVAILABLE,
+        OnlineFailureCode.UNAUTHORIZED,
+        OnlineFailureCode.PROTOCOL,
+        -> false
+    }
+
+    private companion object {
+        const val LogTag = "OnlineTransport"
+        const val WebSocketSubprotocol = "inplacex.online.v1"
+    }
+}
diff --git a/InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/online/RemotePlatformGateway.kt b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/online/RemotePlatformGateway.kt
index 4bc12a3f8e319fa079134d9ad92c67452adb9a8e..09070916ff637e3d0fc60230933442177a84bc26
--- a/InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/online/RemotePlatformGateway.kt
+++ b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/online/RemotePlatformGateway.kt
@@ -45,6 +45,7 @@
     val headers: Map<String, String> = emptyMap(),
     val body: Map<String, Any?> = emptyMap(),
     val idempotencyKey: String? = null,
+    val requiresAuthentication: Boolean = false,
 )

 data class RemoteProgressPayload(
diff --git a/InplaceX-android/app/src/test/java/com/mirkori/inplacex/platform/online/DeterministicOnlineTransportClientTest.kt b/InplaceX-android/app/src/test/java/com/mirkori/inplacex/platform/online/DeterministicOnlineTransportClientTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..0090e8a6afbb8474761b449ca5e522113efbced2
--- /dev/null
+++ b/InplaceX-android/app/src/test/java/com/mirkori/inplacex/platform/online/DeterministicOnlineTransportClientTest.kt
@@ -0,0 +1,145 @@
+package com.mirkori.inplacex.platform.online
+
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class DeterministicOnlineTransportClientTest {
+    @Test
+    fun `retries a timed out REST request with the same idempotency key`() {
+        val executor = RecordingRestExecutor(
+            attempts = ArrayDeque(
+                listOf(
+                    OnlineRestAttempt.Failed(OnlineFailureCode.TIMEOUT),
+                    OnlineRestAttempt.Succeeded(OnlineRestResponse(statusCode = 200)),
+                ),
+            ),
+        )
+        val client = transport(restExecutor = executor)
+
+        val result = client.execute(
+            RemoteRequestSpec(
+                method = RemoteHttpMethod.POST,
+                path = "/api/v1/matchmaking/tickets",
+                headers = mapOf("Idempotency-Key" to "ticket-123"),
+                idempotencyKey = "ticket-123",
+                requiresAuthentication = true,
+            ),
+        )
+
+        assertEquals(OnlineTransportResult.Success(OnlineRestResponse(200), 2), result)
+        assertEquals(2, executor.requests.size)
+        assertTrue(executor.requests.all { it.idempotencyKey == "ticket-123" })
+        assertTrue(executor.requests.all { it.headers["Authorization"] == "Bearer initial-token" })
+    }
+
+    @Test
+    fun `refreshes token once after unauthorized REST response`() {
+        val tokens = FakeTokenStore()
+        val executor = RecordingRestExecutor(
+            attempts = ArrayDeque(
+                listOf(
+                    OnlineRestAttempt.Failed(OnlineFailureCode.UNAUTHORIZED),
+                    OnlineRestAttempt.Succeeded(OnlineRestResponse(statusCode = 204)),
+                ),
+            ),
+        )
+        val client = transport(tokenStore = tokens, restExecutor = executor)
+
+        val result = client.execute(
+            RemoteRequestSpec(
+                method = RemoteHttpMethod.PUT,
+                path = "/api/v1/me/save",
+                requiresAuthentication = true,
+            ),
+        )
+
+        assertEquals(OnlineTransportResult.Success(OnlineRestResponse(204), 2), result)
+        assertEquals(1, tokens.refreshCalls)
+        assertEquals("Bearer initial-token", executor.requests[0].headers["Authorization"])
+        assertEquals("Bearer refreshed-token", executor.requests[1].headers["Authorization"])
+    }
+
+    @Test
+    fun `offline state fails before REST executor runs so local play remains independent`() {
+        val executor = RecordingRestExecutor(ArrayDeque())
+        val client = transport(
+            connectivity = OnlineConnectivity { false },
+            restExecutor = executor,
+        )
+
+        val result = client.execute(
+            RemoteRequestSpec(
+                method = RemoteHttpMethod.POST,
+                path = "/api/v1/matchmaking/tickets",
+                requiresAuthentication = true,
+            ),
+        )
+
+        assertEquals(OnlineTransportResult.Failure(OnlineFailureCode.OFFLINE, 0), result)
+        assertTrue(executor.requests.isEmpty())
+    }
+
+    @Test
+    fun `websocket handshake uses header authentication and negotiated subprotocol`() {
+        var capturedRequest: OnlineWebSocketRequest? = null
+        val client = transport(
+            webSocketConnector = OnlineWebSocketConnector { request ->
+                capturedRequest = request
+                OnlineWebSocketAttempt.Connected(NoOpConnection)
+            },
+        )
+
+        val result = client.connect("session-1")
+
+        assertTrue(result is OnlineTransportResult.Success)
+        assertEquals("/api/v1/ws/sessions/session-1", capturedRequest?.path)
+        assertEquals("inplacex.online.v1", capturedRequest?.subprotocol)
+        assertEquals("Bearer initial-token", capturedRequest?.headers?.get("Authorization"))
+        assertFalse(capturedRequest!!.path.contains("token"))
+    }
+
+    private fun transport(
+        connectivity: OnlineConnectivity = OnlineConnectivity { true },
+        tokenStore: FakeTokenStore = FakeTokenStore(),
+        restExecutor: OnlineRestExecutor = RecordingRestExecutor(ArrayDeque()),
+        webSocketConnector: OnlineWebSocketConnector = OnlineWebSocketConnector {
+            OnlineWebSocketAttempt.Connected(NoOpConnection)
+        },
+    ) = DeterministicOnlineTransportClient(
+        connectivity = connectivity,
+        tokenStore = tokenStore,
+        restExecutor = restExecutor,
+        webSocketConnector = webSocketConnector,
+    )
+
+    private class FakeTokenStore : OnlineTokenStore {
+        private var token = "initial-token"
+        var refreshCalls = 0
+            private set
+
+        override fun accessTokenOrNull(): String = token
+
+        override fun refreshAccessTokenOrNull(): String {
+            refreshCalls += 1
+            token = "refreshed-token"
+            return token
+        }
+    }
+
+    private class RecordingRestExecutor(
+        private val attempts: ArrayDeque<OnlineRestAttempt>,
+    ) : OnlineRestExecutor {
+        val requests = mutableListOf<RemoteRequestSpec>()
+
+        override fun execute(request: RemoteRequestSpec): OnlineRestAttempt {
+            requests += request
+            return attempts.removeFirst()
+        }
+    }
+
+    private object NoOpConnection : OnlineWebSocketConnection {
+        override fun close() = Unit
+    }
+}
diff --git a/InplaceX-logging/src/main/kotlin/com/mirkori/inplacex/logging/InplaceXLog.kt b/InplaceX-logging/src/main/kotlin/com/mirkori/inplacex/logging/InplaceXLog.kt
index 0f797ad8823835a172e42aa001ffaa300a1fd4d4..76d364f9d3148e2af5c047e1732afca6acf806e7
--- a/InplaceX-logging/src/main/kotlin/com/mirkori/inplacex/logging/InplaceXLog.kt
+++ b/InplaceX-logging/src/main/kotlin/com/mirkori/inplacex/logging/InplaceXLog.kt
@@ -52,6 +52,11 @@
             "key",
             "password",
             "private",
+            "guess",
+            "purchase",
+            "integrity",
+            "providerpayload",
+            "rawpayload",
             "secret",
             "token",
         )
diff --git a/InplaceX-logging/src/test/kotlin/com/mirkori/inplacex/logging/InplaceXLoggerTest.kt b/InplaceX-logging/src/test/kotlin/com/mirkori/inplacex/logging/InplaceXLoggerTest.kt
index d6e27bd42ea8451109134a4ea971b97c3d8024a1..24459a97f855a9aaf86fa4b03b788357dada3e2d
--- a/InplaceX-logging/src/test/kotlin/com/mirkori/inplacex/logging/InplaceXLoggerTest.kt
+++ b/InplaceX-logging/src/test/kotlin/com/mirkori/inplacex/logging/InplaceXLoggerTest.kt
@@ -41,6 +41,11 @@
                 "accessToken" to "abc123",
                 "apiKey" to "real-key",
                 "cookie" to "session=secret",
+                "guess" to "1234",
+                "purchaseToken" to "purchase-token",
+                "integrityToken" to "integrity-token",
+                "providerPayload" to "provider-payload",
+                "rawPayload" to "raw-payload",
             ),
         )

@@ -49,6 +54,11 @@
         assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["accessToken"])
         assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["apiKey"])
         assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["cookie"])
+        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["guess"])
+        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["purchaseToken"])
+        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["integrityToken"])
+        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["providerPayload"])
+        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["rawPayload"])
     }

     @Test

tokens used
84 434

```
