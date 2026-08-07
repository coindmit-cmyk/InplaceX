package com.mirkori.inplacex.platform.mirkori

import com.mirkori.inplacex.platform.online.ConnectivityGate
import com.mirkori.platform.sdk.PlatformHttpMethod
import com.mirkori.platform.sdk.PlatformHttpRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KtorMirkoriPlatformTransportTest {
    @Test
    fun `catalog get has no body and uses the bounded transport`() = runBlocking {
        var method: HttpMethod? = null
        val transport = transport(
            MockEngine { request ->
                method = request.method
                respond("{}", HttpStatusCode.OK)
            },
        )

        val response = transport.execute(
            PlatformHttpRequest(
                method = PlatformHttpMethod.GET,
                url = "https://games.dmit.life/api/v1/catalog/games/inplacex/updates" +
                    "?platform=android&channel=stable&versionCode=42",
                headers = mapOf("Accept" to "application/json"),
                body = "",
            ),
        )

        assertEquals(HttpMethod.Get, method)
        assertEquals(200, response.status)
    }

    @Test
    fun `network retries stop at the configured maximum`() {
        var calls = 0
        val transport = transport(
            MockEngine {
                calls += 1
                throw IOException("offline")
            },
        )

        assertThrows(MirkoriTransportException::class.java) {
            runBlocking {
                transport.execute(
                    PlatformHttpRequest(
                        method = PlatformHttpMethod.GET,
                        url = "https://games.dmit.life/api/v1/catalog/games/inplacex/updates" +
                            "?platform=android&channel=stable&versionCode=42",
                        headers = emptyMap(),
                        body = "",
                    ),
                )
            }
        }
        assertEquals(2, calls)
    }

    private fun transport(engine: MockEngine): KtorMirkoriPlatformTransport {
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }
        return KtorMirkoriPlatformTransport(
            client = client,
            connectivity = ConnectivityGate { true },
            policy = MirkoriTransportPolicy(
                connectTimeoutMillis = 1_000L,
                requestTimeoutMillis = 1_000L,
                socketTimeoutMillis = 1_000L,
                maxAttempts = 2,
                retryDelayMillis = 0L,
            ),
        )
    }
}
