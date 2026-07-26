package com.mirkori.inplacex.platform.online

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlatformGatewayTest {
    private val gateway = ContractRemotePlatformGateway()

    @Test
    fun `guest bootstrap uses v1 contract and does not treat installation id as auth`() {
        val request = gateway.prepareGuestBootstrap(
            payload = RemoteAuthBootstrapPayload(
                installationId = "install-1",
                appVersion = "1.0",
                locale = "ru-RU",
            ),
            idempotencyKey = "bootstrap-1",
        )

        assertEquals(RemoteHttpMethod.POST, request.method)
        assertEquals("/api/v1/auth/bootstrap", request.path)
        assertFalse(request.requiresAuthentication)
        assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertTrue(request.bodyJson.orEmpty().contains("\"installationId\":\"install-1\""))
    }

    @Test
    fun `cloud save write is authenticated idempotent and revision-bound`() {
        val request = gateway.prepareWriteCloudSave(
            payload = RemoteCloudSavePayload(
                commandId = UUID.randomUUID().toString(),
                expectedRevision = 7L,
                schemaVersion = 1,
                stateJson = """{"level":4}""",
            ),
            idempotencyKey = "sync-123",
        )

        assertEquals(RemoteHttpMethod.PUT, request.method)
        assertEquals("/api/v1/me/save", request.path)
        assertEquals("sync-123", request.idempotencyKey)
        assertEquals("7", request.headers["If-Match"])
        assertTrue(request.requiresAuthentication)
    }

    @Test
    fun `submit guess never sends player id score or winner`() {
        val request = gateway.prepareSubmitGuess(
            payload = RemoteSubmitGuessPayload(
                sessionId = UUID.randomUUID().toString(),
                commandId = UUID.randomUUID().toString(),
                expectedRevision = 3L,
                guess = "4060",
            ),
            idempotencyKey = "turn-1",
        )

        val body = request.bodyJson.orEmpty()
        assertFalse(body.contains("playerId"))
        assertFalse(body.contains("score"))
        assertFalse(body.contains("winner"))
        assertTrue(body.contains("\"guess\":\"4060\""))
    }

    @Test
    fun `websocket contract uses authorization header boundary not query credentials`() {
        val sessionId = UUID.randomUUID().toString()
        val request = gateway.prepareSessionTransport(sessionId)

        assertEquals("/api/v1/ws/sessions/$sessionId", request.path)
        assertEquals(RemoteWebSocketSpec.OnlineV1Subprotocol, request.subprotocol)
        assertTrue(request.queryParameters.isEmpty())
    }

    @Test
    fun `request spec rejects caller owned authorization and token query`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/1",
                headers = mapOf("Authorization" to "Bearer forged"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/1",
                queryParameters = mapOf("accessToken" to "forged"),
            )
        }
    }

    @Test
    fun `request spec rejects path traversal managed headers and header injection`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/../admin",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/1",
                headers = mapOf("Idempotency-Key" to "caller-owned"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/1",
                headers = mapOf("If-Match" to "1\r\nAuthorization: Bearer forged"),
            )
        }
    }

    @Test
    fun `websocket spec rejects every query parameter`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteWebSocketSpec(
                operation = "session.websocket",
                sessionId = UUID.randomUUID().toString(),
                path = "/api/v1/ws/sessions/${UUID.randomUUID()}",
                queryParameters = mapOf("playerId" to "caller-owned"),
            )
        }
    }

    @Test
    fun `state changing request without idempotency key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteRequestSpec(
                operation = "session.reconnect",
                method = RemoteHttpMethod.POST,
                path = "/api/v1/sessions/1/reconnect",
                bodyJson = "{}",
            )
        }
    }
}
