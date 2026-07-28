package com.mirkori.inplacex.identity.app

import com.mirkori.inplacex.backend.app.DatabaseRuntimeConfig
import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRuntimeConfigTest {
    @Test
    fun `identity process fails closed without a private key`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentityRuntimeConfig.fromEnvironment(databaseEnvironment())
        }
    }

    @Test
    fun `identity config redacts the private key`() {
        val privateKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .private
        val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
        val config = IdentityRuntimeConfig.fromEnvironment(
            databaseEnvironment() + mapOf(
                IdentityRuntimeConfig.IssuerKey to "inplacex-identity",
                IdentityRuntimeConfig.AudienceKey to "inplacex-game-api",
                IdentityRuntimeConfig.PrivateKeyKey to encoded,
            ),
        )

        assertTrue(config.privateKey.algorithm.equals("RSA", ignoreCase = true))
        assertFalse(config.toString().contains(encoded))
        assertNull(config.googleWebClientId)
    }

    @Test
    fun `identity config accepts google web client id without exposing it`() {
        val privateKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .private
        val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
        val clientId = "1234567890-example.apps.googleusercontent.com"
        val config = IdentityRuntimeConfig.fromEnvironment(
            databaseEnvironment() + mapOf(
                IdentityRuntimeConfig.IssuerKey to "inplacex-identity",
                IdentityRuntimeConfig.AudienceKey to "inplacex-game-api",
                IdentityRuntimeConfig.PrivateKeyKey to encoded,
                IdentityRuntimeConfig.GoogleWebClientIdKey to clientId,
            ),
        )

        assertEquals(clientId, config.googleWebClientId)
        assertFalse(config.toString().contains(clientId))
        assertTrue(config.toString().contains("googleConfigured=true"))
    }

    @Test
    fun `identity config rejects malformed google client id`() {
        val privateKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .private
        val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)

        assertThrows(IllegalArgumentException::class.java) {
            IdentityRuntimeConfig.fromEnvironment(
                databaseEnvironment() + mapOf(
                    IdentityRuntimeConfig.IssuerKey to "inplacex-identity",
                    IdentityRuntimeConfig.AudienceKey to "inplacex-game-api",
                    IdentityRuntimeConfig.PrivateKeyKey to encoded,
                    IdentityRuntimeConfig.GoogleWebClientIdKey to "bad client id",
                ),
            )
        }
    }

    private fun databaseEnvironment(): Map<String, String> = mapOf(
        DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://localhost/inplacex",
        DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
        DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-only",
    )
}
