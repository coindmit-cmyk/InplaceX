package com.mirkori.inplacex.backend.ads

import com.mirkori.inplacex.backend.app.BackendRuntimeConfig
import com.mirkori.inplacex.backend.app.backendModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetAddress
import java.nio.file.Path
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
        val config = trustedCountryConfig(setOf("localhost", "127.0.0.1"))
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
    fun `untrusted source cannot select a market with a forged country header`() {
        val resolver = TrustedProxyAdMarketResolver(
            trustedCountryConfig(setOf("10.0.0.5")),
        )

        assertEquals(PublicAdMarket.UNKNOWN, resolver.resolve("203.0.113.20", "RU"))
        assertEquals(PublicAdMarket.UNKNOWN, resolver.resolve("10.0.0.5", "ZZZ"))
    }

    @Test
    fun `local database resolves direct numeric addresses without a proxy header`() {
        val resolver = LocalDatabaseAdMarketResolver(
            config = databaseConfig(),
            lookup = CountryByIpLookup { address ->
                when (address.hostAddress) {
                    "198.51.100.20" -> "RU"
                    "203.0.113.20" -> "DE"
                    else -> null
                }
            },
        )

        assertEquals(PublicAdMarket.RUSSIA, resolver.resolve("198.51.100.20", null))
        assertEquals(PublicAdMarket.GLOBAL, resolver.resolve("203.0.113.20", null))
        assertEquals(PublicAdMarket.UNKNOWN, resolver.resolve("not-an-ip.example", null))
    }

    @Test
    fun `local database accepts client IP only from a trusted proxy`() {
        val resolver = LocalDatabaseAdMarketResolver(
            config = databaseConfig(setOf("127.0.0.1")),
            lookup = CountryByIpLookup { address ->
                if (address == InetAddress.getByName("198.51.100.20")) "RU" else "DE"
            },
        )

        assertEquals(
            PublicAdMarket.RUSSIA,
            resolver.resolve("127.0.0.1", "198.51.100.20"),
        )
        assertEquals(
            PublicAdMarket.GLOBAL,
            resolver.resolve("203.0.113.20", "198.51.100.20"),
        )
        assertEquals(
            PublicAdMarket.UNKNOWN,
            resolver.resolve("127.0.0.1", "198.51.100.20, 203.0.113.20"),
        )
    }

    @Test
    fun `local database route preserves bounded response and exposes attribution`() = testApplication {
        val config = databaseConfig(setOf("localhost", "127.0.0.1"))
        application {
            configureAdMarketRoutes(
                config = config,
                resolver = LocalDatabaseAdMarketResolver(
                    config = config,
                    lookup = CountryByIpLookup { "RU" },
                ),
            )
        }

        val response = client.get("/api/v1/runtime/ad-market") {
            header(AdMarketRuntimeConfig.DefaultClientIpHeader, "198.51.100.20")
        }

        assertEquals("{\"market\":\"RUSSIA\"}", response.bodyAsText())
        assertEquals("""<https://db-ip.com>; rel="via"""", response.headers[HttpHeaders.Link])
    }

    private fun trustedCountryConfig(proxyHosts: Set<String>) = AdMarketRuntimeConfig(
        source = AdMarketSource.TRUSTED_COUNTRY_HEADER,
        trustedProxyHosts = proxyHosts,
        trustedHeader = "X-Geo-Country",
    )

    private fun databaseConfig(proxyHosts: Set<String> = emptySet()) = AdMarketRuntimeConfig(
        source = AdMarketSource.LOCAL_IP_DATABASE,
        trustedProxyHosts = proxyHosts,
        trustedHeader = AdMarketRuntimeConfig.DefaultClientIpHeader,
        databasePath = Path.of("unused-in-fake-test.mmdb"),
    )
}
