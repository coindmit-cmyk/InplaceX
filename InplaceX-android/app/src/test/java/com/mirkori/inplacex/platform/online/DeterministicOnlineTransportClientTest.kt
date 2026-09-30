package com.mirkori.inplacex.platform.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicOnlineTransportClientTest {
    @Test
    fun `retries a timed out REST request with the same idempotency key`() {
        val executor = RecordingRestExecutor(
            attempts = ArrayDeque(
                listOf(
                    OnlineRestAttempt.Failed(OnlineFailureCode.TIMEOUT),
                    OnlineRestAttempt.Succeeded(OnlineRestResponse(statusCode = 200)),
                ),
            ),
        )
        val client = transport(restExecutor = executor)

        val result = client.execute(
            RemoteRequestSpec(
                method = RemoteHttpMethod.POST,
                path = "/api/v1/matchmaking/tickets",
                headers = mapOf("Idempotency-Key" to "ticket-123"),
                idempotencyKey = "ticket-123",
                requiresAuthentication = true,
            ),
        )

        assertEquals(OnlineTransportResult.Success(OnlineRestResponse(200), 2), result)
        assertEquals(2, executor.requests.size)
        assertTrue(executor.requests.all { it.idempotencyKey == "ticket-123" })
        assertTrue(executor.requests.all { it.headers["Authorization"] == "Bearer initial-token" })
    }

    @Test
    fun `refreshes token once after unauthorized REST response`() {
        val tokens = FakeTokenStore()
        val executor = RecordingRestExecutor(
            attempts = ArrayDeque(
                listOf(
                    OnlineRestAttempt.Failed(OnlineFailureCode.UNAUTHORIZED),
                    OnlineRestAttempt.Succeeded(OnlineRestResponse(statusCode = 204)),
                ),
            ),
        )
        val client = transport(tokenStore = tokens, restExecutor = executor)

        val result = client.execute(
            RemoteRequestSpec(
                method = RemoteHttpMethod.PUT,
                path = "/api/v1/me/save",
                requiresAuthentication = true,
            ),
        )

        assertEquals(OnlineTransportResult.Success(OnlineRestResponse(204), 2), result)
        assertEquals(1, tokens.refreshCalls)
        assertEquals("Bearer initial-token", executor.requests[0].headers["Authorization"])
        assertEquals("Bearer refreshed-token", executor.requests[1].headers["Authorization"])
    }

    @Test
    fun `offline state fails before REST executor runs so local play remains independent`() {
        val executor = RecordingRestExecutor(ArrayDeque())
        val client = transport(
            connectivity = OnlineConnectivity { false },
            restExecutor = executor,
        )

        val result = client.execute(
            RemoteRequestSpec(
                method = RemoteHttpMethod.POST,
                path = "/api/v1/matchmaking/tickets",
                requiresAuthentication = true,
            ),
        )

        assertEquals(OnlineTransportResult.Failure(OnlineFailureCode.OFFLINE, 0), result)
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun `websocket handshake uses header authentication and negotiated subprotocol`() {
        var capturedRequest: OnlineWebSocketRequest? = null
        val client = transport(
            webSocketConnector = OnlineWebSocketConnector { request ->
                capturedRequest = request
                OnlineWebSocketAttempt.Connected(NoOpConnection)
            },
        )

        val result = client.connect("session-1")

        assertTrue(result is OnlineTransportResult.Success)
        assertEquals("/api/v1/ws/sessions/session-1", capturedRequest?.path)
        assertEquals("inplacex.online.v1", capturedRequest?.subprotocol)
        assertEquals("Bearer initial-token", capturedRequest?.headers?.get("Authorization"))
        assertFalse(capturedRequest!!.path.contains("token"))
    }

    private fun transport(
        connectivity: OnlineConnectivity = OnlineConnectivity { true },
        tokenStore: FakeTokenStore = FakeTokenStore(),
        restExecutor: OnlineRestExecutor = RecordingRestExecutor(ArrayDeque()),
        webSocketConnector: OnlineWebSocketConnector = OnlineWebSocketConnector {
            OnlineWebSocketAttempt.Connected(NoOpConnection)
        },
    ) = DeterministicOnlineTransportClient(
        connectivity = connectivity,
        tokenStore = tokenStore,
        restExecutor = restExecutor,
        webSocketConnector = webSocketConnector,
    )

    private class FakeTokenStore : OnlineTokenStore {
        private var token = "initial-token"
        var refreshCalls = 0
            private set

        override fun accessTokenOrNull(): String = token

        override fun refreshAccessTokenOrNull(): String {
            refreshCalls += 1
            token = "refreshed-token"
            return token
        }
    }

    private class RecordingRestExecutor(
        private val attempts: ArrayDeque<OnlineRestAttempt>,
    ) : OnlineRestExecutor {
        val requests = mutableListOf<RemoteRequestSpec>()

        override fun execute(request: RemoteRequestSpec): OnlineRestAttempt {
            requests += request
            return attempts.removeFirst()
        }
    }

    private object NoOpConnection : OnlineWebSocketConnection {
        override fun close() = Unit
    }
}
