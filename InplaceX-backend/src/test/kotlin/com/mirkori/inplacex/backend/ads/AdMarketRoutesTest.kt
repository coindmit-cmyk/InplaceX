package com.mirkori.inplacex.backend.ads

import com.mirkori.inplacex.backend.app.BackendRuntimeConfig
import com.mirkori.inplacex.backend.app.backendModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class AdMarketRoutesTest {
    @Test
    fun `unconfigured market endpoint fails closed`() = testApplication {
        application {
            backendModule(BackendRuntimeConfig.fromEnvironment(emptyMap()))
        }

        val response = client.get("/api/v1/runtime/ad-market")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"market\":\"UNKNOWN\"}", response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `trusted proxy country is reduced to coarse market`() = testApplication {
        val config = AdMarketRuntimeConfig(
            trustedCountryHeader = "X-Geo-Country",
            trustedProxyHosts = setOf("localhost"),
        )
        application {
            configureAdMarketRoutes(config)
        }

        val russia = client.get("/api/v1/runtime/ad-market") {
            header("X-Geo-Country", "ru")
        }
        val global = client.get("/api/v1/runtime/ad-market") {
            header("X-Geo-Country", "DE")
        }

        assertEquals("{\"market\":\"RUSSIA\"}", russia.bodyAsText())
        assertEquals("{\"market\":\"GLOBAL\"}", global.bodyAsText())
    }

    @Test
    fun `untrusted source cannot select a market with a forged header`() {
        val resolver = TrustedProxyAdMarketResolver(
            AdMarketRuntimeConfig(
                trustedCountryHeader = "X-Geo-Country",
                trustedProxyHosts = setOf("10.0.0.5"),
            ),
        )

        assertEquals(PublicAdMarket.UNKNOWN, resolver.resolve("203.0.113.20", "RU"))
        assertEquals(PublicAdMarket.UNKNOWN, resolver.resolve("10.0.0.5", "ZZZ"))
    }
}
