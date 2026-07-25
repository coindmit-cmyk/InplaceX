package com.mirkori.inplacex.backend.app

import org.junit.Assert.assertEquals
import org.junit.Test

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

    @Test(expected = IllegalArgumentException::class)
    fun `configuration rejects an invalid port`() {
        BackendRuntimeConfig.fromEnvironment(mapOf("INPLACEX_BACKEND_PORT" to "0"))
    }
}
