package com.mirkori.inplacex.platform.online

import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.logging.LogEvent
import com.mirkori.inplacex.logging.LogSink
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorOnlineTransportTest {
    @Test
    fun `authenticated request owns bearer header inside transport`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val transport = transport(
            engine = MockEngine { request ->
                requests += request
                respond(
                    content = """{"revision":1}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            tokenProvider = FixedTokenProvider("access-1"),
        )

        val result = transport.execute(
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/session-1",
            ),
        )

        assertTrue(result is RemoteCallResult.Success)
        assertEquals("Bearer access-1", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `unauthorized response refreshes exactly once and retries`() = runBlocking {
        var calls = 0
        val provider = RecordingTokenProvider()
        val transport = transport(
            engine = MockEngine {
                calls += 1
                if (calls == 1) {
                    respondError(HttpStatusCode.Unauthorized)
                } else {
                    respond("ok", HttpStatusCode.OK)
                }
            },
            tokenProvider = provider,
        )

        val result = transport.execute(
            RemoteRequestSpec(
                operation = "cloudsave.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/me/save",
            ),
        )

        assertTrue(result is RemoteCallResult.Success)
        assertEquals(2, calls)
        assertEquals(1, provider.refreshCalls)
    }

    @Test
    fun `retryable network failure uses deterministic bounded delay`() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        val transport = transport(
            engine = MockEngine {
                calls += 1
                if (calls < 3) throw IOException("network unavailable")
                respond("ok", HttpStatusCode.OK)
            },
            tokenProvider = FixedTokenProvider("access-1"),
            retryDelay = RetryDelay(delays::add),
        )

        val result = transport.execute(
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/session-1",
            ),
        )

        assertTrue(result is RemoteCallResult.Success)
        assertEquals(3, calls)
        assertEquals(listOf(10L, 20L), delays)
    }

    @Test
    fun `offline result performs no network request`() = runBlocking {
        var calls = 0
        val transport = transport(
            engine = MockEngine {
                calls += 1
                respond("unexpected", HttpStatusCode.OK)
            },
            tokenProvider = FixedTokenProvider("access-1"),
            connectivity = ConnectivityGate { false },
        )

        val result = transport.execute(
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/session-1",
            ),
        )

        assertEquals(RemoteCallResult.Offline, result)
        assertEquals(0, calls)
    }

    @Test
    fun `logs and token rendering do not expose access token`() = runBlocking {
        val events = mutableListOf<LogEvent>()
        val token = AccessToken.from("sensitive-access-token")
        val transport = transport(
            engine = MockEngine { respond("ok", HttpStatusCode.OK) },
            tokenProvider = object : AccessTokenProvider {
                override suspend fun currentAccessToken(): AccessToken = token
                override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken = token
            },
            logger = InplaceXLogger(sink = LogSink(events::add)),
        )

        transport.execute(
            RemoteRequestSpec(
                operation = "session.read",
                method = RemoteHttpMethod.GET,
                path = "/api/v1/sessions/session-1",
            ),
        )

        assertEquals("[redacted]", token.toString())
        assertFalse(events.joinToString().contains("sensitive-access-token"))
        assertTrue(events.single().attributes.containsKey("operation"))
    }

    @Test
    fun `single flight provider reuses token refreshed by another request`() = runBlocking {
        val delegate = MutableTokenProvider()
        val provider = SingleFlightAccessTokenProvider(delegate)
        val rejected = requireNotNull(provider.currentAccessToken())

        val first = provider.refreshAccessToken(rejected)
        val second = provider.refreshAccessToken(rejected)

        assertEquals(1, delegate.refreshCalls)
        assertTrue(requireNotNull(first).sameValueAs(requireNotNull(second)))
    }

    @Test
    fun `request and response rendering redact bodies and idempotency keys`() {
        val request = RemoteRequestSpec(
            operation = "auth.refresh",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/auth/refresh",
            bodyJson = """{"refreshToken":"sensitive-refresh"}""",
            idempotencyKey = "sensitive-idempotency",
            requiresAuthentication = false,
        )
        val response = RemoteResponse(
            statusCode = 200,
            headers = emptyMap(),
            body = """{"accessToken":"sensitive-access"}""",
        )

        assertFalse(request.toString().contains("sensitive-refresh"))
        assertFalse(request.toString().contains("sensitive-idempotency"))
        assertFalse(response.toString().contains("sensitive-access"))
    }

    @Test
    fun `endpoint rejects cleartext remote user info paths and fragments`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnlineEndpoint("http://example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineEndpoint("https://user@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineEndpoint("https://example.com/api")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineEndpoint("https://example.com#fragment")
        }
    }

    private fun transport(
        engine: MockEngine,
        tokenProvider: AccessTokenProvider,
        connectivity: ConnectivityGate = AlwaysOnlineConnectivity,
        retryDelay: RetryDelay = RetryDelay { },
        logger: InplaceXLogger = InplaceXLogger(),
    ): KtorOnlineTransport {
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }
        return KtorOnlineTransport(
            client = client,
            endpoint = OnlineEndpoint(
                baseUrl = "http://localhost:8080",
                allowCleartextLoopback = true,
            ),
            tokenProvider = tokenProvider,
            connectivity = connectivity,
            policy = OnlineTransportPolicy(
                maxAttempts = 3,
                retryDelayMillis = 10L,
            ),
            retryDelay = retryDelay,
            logger = logger,
        )
    }
}

private class FixedTokenProvider(
    token: String,
) : AccessTokenProvider {
    private val value = AccessToken.from(token)

    override suspend fun currentAccessToken(): AccessToken = value

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken = value
}

private class RecordingTokenProvider : AccessTokenProvider {
    var refreshCalls: Int = 0
        private set

    override suspend fun currentAccessToken(): AccessToken = AccessToken.from("access-old")

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken {
        refreshCalls += 1
        return AccessToken.from("access-new")
    }
}

private class MutableTokenProvider : AccessTokenProvider {
    private var token = AccessToken.from("access-old")
    var refreshCalls: Int = 0
        private set

    override suspend fun currentAccessToken(): AccessToken = token

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken {
        refreshCalls += 1
        token = AccessToken.from("access-new")
        return token
    }
}
