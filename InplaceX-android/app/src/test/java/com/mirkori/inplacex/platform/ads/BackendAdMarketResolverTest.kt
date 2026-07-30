package com.mirkori.inplacex.platform.ads

import com.mirkori.inplacex.ads.AdMarket
import com.mirkori.inplacex.platform.online.OnlineSessionOpenResult
import com.mirkori.inplacex.platform.online.RemoteCallResult
import com.mirkori.inplacex.platform.online.RemoteResponse
import com.mirkori.inplacex.platform.online.RemoteWebSocketSpec
import com.mirkori.inplacex.platform.online.RemoteRequestSpec
import com.mirkori.inplacex.platform.online.TransportBoundary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendAdMarketResolverTest {
    @Test
    fun `resolver accepts only the bounded coarse market contract`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                success("""{"market":"RUSSIA"}"""),
                success("""{"market":"GLOBAL","ip":"203.0.113.5"}"""),
            ),
        )
        var now = 0L
        val resolver = BackendAdMarketResolver(
            transport = transport,
            nowMillis = { now },
            cacheDurationMillis = 1,
        )

        assertEquals(AdMarket.RUSSIA, resolver.resolveMarket())
        now = 2
        assertEquals(AdMarket.UNKNOWN, resolver.resolveMarket())
        assertEquals(
            listOf(false, false),
            transport.requests.map(RemoteRequestSpec::requiresAuthentication),
        )
    }

    @Test
    fun `resolver caches only the coarse result for a bounded duration`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                success("""{"market":"GLOBAL"}"""),
                success("""{"market":"RUSSIA"}"""),
            ),
        )
        var now = 100L
        val resolver = BackendAdMarketResolver(
            transport = transport,
            nowMillis = { now },
            cacheDurationMillis = 100,
        )

        assertEquals(AdMarket.GLOBAL, resolver.resolveMarket())
        now = 150
        assertEquals(AdMarket.GLOBAL, resolver.resolveMarket())
        now = 200
        assertEquals(AdMarket.RUSSIA, resolver.resolveMarket())
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `unknown market is retried so network recovery can restore routing`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                RemoteCallResult.Offline,
                success("""{"market":"GLOBAL"}"""),
            ),
        )
        val resolver = BackendAdMarketResolver(
            transport = transport,
            nowMillis = { 100L },
            cacheDurationMillis = 5 * 60 * 1_000L,
        )

        assertEquals(AdMarket.UNKNOWN, resolver.resolveMarket())
        assertEquals(AdMarket.GLOBAL, resolver.resolveMarket())
        assertEquals(2, transport.requests.size)
    }

    private fun success(body: String): RemoteCallResult =
        RemoteCallResult.Success(RemoteResponse(200, emptyMap(), body))

    private class FakeTransport(
        private val results: List<RemoteCallResult>,
    ) : TransportBoundary {
        val requests = mutableListOf<RemoteRequestSpec>()

        override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
            requests += request
            return results[requests.lastIndex]
        }

        override suspend fun openSession(
            request: RemoteWebSocketSpec,
        ): OnlineSessionOpenResult = error("not used")
    }
}
