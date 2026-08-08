package com.mirkori.inplacex.backend.health

import com.mirkori.inplacex.backend.app.BackendReleaseIdentity
import com.mirkori.inplacex.backend.app.RuntimeDrainController
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun interface ReadinessProbe {
    fun isReady(): Boolean
}

interface ReadinessMetricsSource {
    fun readinessMetrics(): ReadinessMetrics
}

data class ReadinessMetrics(
    val checks: Long,
    val failures: Long,
    val transitions: Long,
    val ready: Boolean,
)

object AlwaysReadyProbe : ReadinessProbe {
    override fun isReady(): Boolean = true
}

fun Application.configureHealthRoutes(
    readinessProbe: ReadinessProbe,
    releaseIdentity: BackendReleaseIdentity? = null,
    drainController: RuntimeDrainController = RuntimeDrainController.disabled(),
) {
    routing {
        get("/health") {
            call.respondText(
                text = "{\"status\":\"ok\"}",
                contentType = ContentType.Application.Json,
            )
        }
        get("/ready") {
            val ready = readinessProbe.isReady()
            call.respondText(
                text = if (ready) "{\"status\":\"ready\"}" else "{\"status\":\"not_ready\"}",
                contentType = ContentType.Application.Json,
                status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
        (readinessProbe as? ReadinessMetricsSource)?.let { metricsSource ->
            get("/metrics") {
                val metrics = metricsSource.readinessMetrics()
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondText(
                    text = buildString {
                        appendLine("# TYPE inplacex_backend_readiness_checks_total counter")
                        appendLine("inplacex_backend_readiness_checks_total ${metrics.checks}")
                        appendLine("# TYPE inplacex_backend_readiness_failures_total counter")
                        appendLine("inplacex_backend_readiness_failures_total ${metrics.failures}")
                        appendLine("# TYPE inplacex_backend_readiness_transitions_total counter")
                        appendLine("inplacex_backend_readiness_transitions_total ${metrics.transitions}")
                        appendLine("# TYPE inplacex_backend_readiness gauge")
                        appendLine("inplacex_backend_readiness ${if (metrics.ready) 1 else 0}")
                    },
                    contentType = ContentType.parse("text/plain; version=0.0.4"),
                )
            }
        }
        releaseIdentity?.let { identity ->
            get("/meta/release") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respondText(
                    text = """{"releaseId":"${identity.releaseId}","gitSha":"${identity.gitSha}","imageDigest":"${identity.imageDigest}"}""",
                    contentType = ContentType.Application.Json,
                )
            }
        }
        get("/admin/drain/status") {
            val snapshot = drainController.snapshot()
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(
                text = "{\"draining\":${snapshot.draining},\"activeRequests\":${snapshot.activeRequests}}",
                contentType = ContentType.Application.Json,
            )
        }
    }
}
