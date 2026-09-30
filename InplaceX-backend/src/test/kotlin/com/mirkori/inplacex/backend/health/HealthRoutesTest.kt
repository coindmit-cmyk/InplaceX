package com.mirkori.inplacex.backend.health

import com.mirkori.inplacex.backend.app.BackendRuntimeConfig
import com.mirkori.inplacex.backend.app.backendModule
import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.logging.LogLevel
import com.mirkori.inplacex.logging.SensitiveKeyLogSanitizer
import com.mirkori.inplacex.testsupport.RecordingLogSink
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HealthRoutesTest {
    @Test
    fun `health endpoint confirms the process is alive`() = testApplication {
        application {
            backendModule(BackendRuntimeConfig.fromEnvironment(emptyMap()))
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }

    @Test
    fun `readiness reports module availability`() = testApplication {
        application {
            backendModule(
                config = BackendRuntimeConfig.fromEnvironment(emptyMap()),
                readinessProbe = ReadinessProbe { false },
            )
        }

        val response = client.get("/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("{\"status\":\"not_ready\"}", response.bodyAsText())
    }

    @Test
    fun `startup logging is sanitized and does not include unrelated environment secrets`() = testApplication {
        val sink = RecordingLogSink()
        application {
            backendModule(
                config = BackendRuntimeConfig.fromEnvironment(
                    mapOf(
                        "INPLACEX_BACKEND_ENVIRONMENT" to "test",
                        "DATABASE_PASSWORD" to "must-not-be-read",
                    ),
                ),
                logger = InplaceXLogger(sink = sink, minLevel = LogLevel.DEBUG),
            )
        }

        client.get("/health")

        val event = sink.events.single()
        assertEquals("BackendRuntime", event.tag)
        assertEquals("test", event.attributes["environment"])
        assertFalse(event.attributes.values.contains("must-not-be-read"))
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, SensitiveKeyLogSanitizer().sanitizeAttributes(mapOf("accessToken" to "value"))["accessToken"])
    }
}
