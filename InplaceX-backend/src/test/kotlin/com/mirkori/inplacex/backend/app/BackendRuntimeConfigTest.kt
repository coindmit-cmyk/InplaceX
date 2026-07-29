package com.mirkori.inplacex.backend.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.KeyPairGenerator
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
}
