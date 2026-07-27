package com.mirkori.inplacex.identity.app

import com.mirkori.inplacex.backend.app.DatabaseRuntimeConfig
import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertFalse
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
    }

    private fun databaseEnvironment(): Map<String, String> = mapOf(
        DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://localhost/inplacex",
        DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
        DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-only",
    )
}
