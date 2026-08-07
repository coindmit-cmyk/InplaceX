package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.ads.AdMarketSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

class BackendRuntimeConfigTest {
    @Test
    fun `configuration uses supported environment variables without requiring secrets`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_BACKEND_HOST" to "127.0.0.1",
                "INPLACEX_BACKEND_PORT" to "9080",
                "INPLACEX_BACKEND_ENVIRONMENT" to "test",
                "DATABASE_PASSWORD" to "must-not-be-read",
            ),
        )

        assertEquals("127.0.0.1", config.host)
        assertEquals(9080, config.port)
        assertEquals("test", config.environment)
    }

    @Test
    fun `configuration supports platform port and safe defaults`() {
        val platformConfig = BackendRuntimeConfig.fromEnvironment(mapOf("PORT" to "9090"))
        val defaults = BackendRuntimeConfig.fromEnvironment(emptyMap())

        assertEquals(9090, platformConfig.port)
        assertEquals(BackendRuntimeConfig.DefaultHost, defaults.host)
        assertEquals(BackendRuntimeConfig.DefaultPort, defaults.port)
        assertEquals(BackendRuntimeConfig.DefaultEnvironment, defaults.environment)
    }

    @Test
    fun `database credentials are accepted only from the process environment and not rendered`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
            ),
        )

        assertNotNull(config.database)
        val database = requireNotNull(config.database)
        assertEquals("jdbc:postgresql://db/inplacex", database.jdbcUrl)
        assertEquals("inplacex", database.username)
        assertFalse(database.toString().contains("test-password"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `configuration rejects an invalid port`() {
        BackendRuntimeConfig.fromEnvironment(mapOf("INPLACEX_BACKEND_PORT" to "0"))
    }

    @Test
    fun `online game API accepts only public verification material`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encodedPublicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                OnlineRuntimeConfig.IssuerKey to "inplacex-identity",
                OnlineRuntimeConfig.AudienceKey to "inplacex-game-api",
                OnlineRuntimeConfig.PublicKeyKey to encodedPublicKey,
            ),
        )

        assertEquals("RSA", requireNotNull(config.online).verificationKey.algorithm)
        assertEquals(Duration.ofSeconds(5), config.online?.botFallbackDelay)
        assertFalse(config.online.toString().contains(encodedPublicKey))
    }

    @Test
    fun `online game API accepts a bounded bot fallback delay`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                OnlineRuntimeConfig.IssuerKey to "inplacex-identity",
                OnlineRuntimeConfig.AudienceKey to "inplacex-game-api",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
                OnlineRuntimeConfig.BotFallbackSecondsKey to "8",
            ),
        )

        assertEquals(Duration.ofSeconds(8), config.online?.botFallbackDelay)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `online game API rejects an unbounded bot fallback delay`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        BackendRuntimeConfig.fromEnvironment(
            mapOf(
                OnlineRuntimeConfig.IssuerKey to "inplacex-identity",
                OnlineRuntimeConfig.AudienceKey to "inplacex-game-api",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
                OnlineRuntimeConfig.BotFallbackSecondsKey to "600",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial online verification config fails closed`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf(OnlineRuntimeConfig.IssuerKey to "inplacex-identity"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PostgreSQL online runtime requires durable state encryption key`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
                OnlineRuntimeConfig.IssuerKey to "inplacex-identity",
                OnlineRuntimeConfig.AudienceKey to "inplacex-game-api",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
            ),
        )
    }

    @Test
    fun `PostgreSQL online runtime accepts a redacted 256 bit state key`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encodedStateKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
                OnlineRuntimeConfig.IssuerKey to "inplacex-identity",
                OnlineRuntimeConfig.AudienceKey to "inplacex-game-api",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
                OnlineRuntimeConfig.StateEncryptionKey to encodedStateKey,
            ),
        )

        assertNotNull(config.online?.stateEncryptionKey)
        assertFalse(config.toString().contains(encodedStateKey))
    }

    @Test
    fun `ad market accepts only a complete trusted proxy configuration`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry",
                "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1, 10.0.0.5",
            ),
        )

        assertEquals(AdMarketSource.TRUSTED_COUNTRY_HEADER, config.adMarket?.source)
        assertEquals("CF-IPCountry", config.adMarket?.trustedCountryHeader)
        assertEquals(setOf("127.0.0.1", "10.0.0.5"), config.adMarket?.trustedProxyHosts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial ad market configuration fails closed`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf("INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry"),
        )
    }

    @Test
    fun `ad market accepts local IP database with a dedicated proxy header`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_AD_MARKET_REQUIRED" to "true",
                "INPLACEX_AD_MARKET_DB_PATH" to "/var/lib/inplacex/geoip/dbip-country-lite.mmdb",
                "INPLACEX_AD_MARKET_CLIENT_IP_HEADER" to "X-InplaceX-Client-IP",
                "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1, ::1",
            ),
        )

        assertEquals(AdMarketSource.LOCAL_IP_DATABASE, config.adMarket?.source)
        assertEquals("X-InplaceX-Client-IP", config.adMarket?.trustedClientIpHeader)
        assertEquals(setOf("127.0.0.1", "::1"), config.adMarket?.trustedProxyHosts)
        assertEquals(
            Path.of("/var/lib/inplacex/geoip/dbip-country-lite.mmdb"),
            config.adMarket?.databasePath,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `required ad market rejects missing source`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf("INPLACEX_AD_MARKET_REQUIRED" to "true"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ad market rejects ambiguous country and database sources`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry",
                "INPLACEX_AD_MARKET_DB_PATH" to "/tmp/country.mmdb",
                "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1",
            ),
        )
    }
}
