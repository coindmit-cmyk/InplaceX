package com.mirkori.inplacex.backend.health

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun interface ReadinessProbe {
    fun isReady(): Boolean
}

object AlwaysReadyProbe : ReadinessProbe {
    override fun isReady(): Boolean = true
}

fun Application.configureHealthRoutes(readinessProbe: ReadinessProbe) {
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
    }
}
