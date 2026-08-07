package com.mirkori.inplacex.platform.mirkori

import com.mirkori.inplacex.platform.online.ConnectivityGate
import com.mirkori.platform.sdk.PlatformHttpMethod
import com.mirkori.platform.sdk.PlatformHttpRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorMirkoriPlatformTransportTest {
    @Test
    fun `response limit is enforced while streaming before oversized body is materialized`() {
        val bodyChannel = ByteReadChannel(ByteArray(128 * 1024) { 'x'.code.toByte() })
        val client = HttpClient(
            MockEngine {
                respond(
                    content = bodyChannel,
                    status = HttpStatusCode.OK,
                )
            },
        ) { expectSuccess = false }
        val transport = KtorMirkoriPlatformTransport(
            client = client,
            connectivity = ConnectivityGate { true },
            policy = MirkoriTransportPolicy(maxAttempts = 1),
        )

        val error = assertThrows(MirkoriTransportException::class.java) {
            runBlocking { transport.execute(request("https://games.dmit.life/api/v1/commerce/entitlements")) }
        }

        assertEquals(MirkoriTransportFailure.RESPONSE_TOO_LARGE, error.failure)
        assertTrue(bodyChannel.isClosedForRead)
        client.close()
    }

    @Test
    fun `https date header is surfaced as trusted server time`() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.Date, "Fri, 07 Aug 2026 10:00:00 GMT"),
                )
            },
        ) { expectSuccess = false }
        val transport = KtorMirkoriPlatformTransport(client, ConnectivityGate { true })

        val response = transport.execute(request("https://games.dmit.life/api/v1/commerce/entitlements"))

        assertEquals(Instant.parse("2026-08-07T10:00:00Z"), response.serverTime)
        client.close()
    }

    @Test
    fun `cleartext loopback date header is never trusted`() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.Date, "Fri, 07 Aug 2026 10:00:00 GMT"),
                )
            },
        ) { expectSuccess = false }
        val transport = KtorMirkoriPlatformTransport(client, ConnectivityGate { true })

        val response = transport.execute(request("http://localhost:8080/api/v1/commerce/entitlements"))

        assertNull(response.serverTime)
        client.close()
    }

    private fun request(url: String) = PlatformHttpRequest(
        method = PlatformHttpMethod.GET,
        url = url,
        headers = emptyMap(),
        body = "",
    )
}
