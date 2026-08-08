package com.mirkori.inplacex.backend.health

import com.mirkori.inplacex.backend.app.BackendRuntimeConfig
import com.mirkori.inplacex.backend.app.BackendReleaseIdentity
import com.mirkori.inplacex.backend.app.backendModule
import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.logging.LogLevel
import com.mirkori.inplacex.logging.SensitiveKeyLogSanitizer
import com.mirkori.inplacex.testsupport.RecordingLogSink
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `release metadata exposes only the immutable public deployment identity`() = testApplication {
        val identity = BackendReleaseIdentity(
            releaseId = "inplacex-backend-20260807-1",
            gitSha = "a".repeat(40),
            imageDigest = "sha256:${"b".repeat(64)}",
        )
        application {
            backendModule(
                config = BackendRuntimeConfig.fromEnvironment(emptyMap()).copy(releaseIdentity = identity),
            )
        }

        val response = client.get("/meta/release")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(
            """{"releaseId":"${identity.releaseId}","gitSha":"${identity.gitSha}","imageDigest":"${identity.imageDigest}"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `readiness metrics expose counters without failure details`() = testApplication {
        val probe = RecordingReadinessProbe()
        application {
            configureHealthRoutes(probe)
        }

        client.get("/ready")
        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        assertTrue(body.contains("inplacex_backend_readiness_checks_total 1"))
        assertTrue(body.contains("inplacex_backend_readiness_failures_total 1"))
        assertTrue(body.contains("inplacex_backend_readiness 0"))
        assertFalse(body.contains("database-password"))
        assertFalse(body.contains("SQLException"))
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

private class RecordingReadinessProbe : ReadinessProbe, ReadinessMetricsSource {
    private var checks = 0L

    override fun isReady(): Boolean {
        checks += 1
        return false
    }

    override fun readinessMetrics(): ReadinessMetrics = ReadinessMetrics(
        checks = checks,
        failures = checks,
        transitions = if (checks == 0L) 0 else 1,
        ready = false,
    )
}
