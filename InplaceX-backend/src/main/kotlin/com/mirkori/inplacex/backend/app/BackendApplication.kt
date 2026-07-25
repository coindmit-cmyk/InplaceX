package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.health.AlwaysReadyProbe
import com.mirkori.inplacex.backend.health.ReadinessProbe
import com.mirkori.inplacex.backend.health.configureHealthRoutes
import com.mirkori.inplacex.logging.InplaceXLogger
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = BackendRuntimeConfig.fromEnvironment()
    embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
    ) {
        backendModule(config)
    }.start(wait = true)
}

fun Application.backendModule(
    config: BackendRuntimeConfig = BackendRuntimeConfig.fromEnvironment(),
    logger: InplaceXLogger = InplaceXLogger(),
    readinessProbe: ReadinessProbe = AlwaysReadyProbe,
) {
    logger.info(
        tag = "BackendRuntime",
        message = "backend module initialized",
        attributes = mapOf(
            "environment" to config.environment,
            "host" to config.host,
            "port" to config.port.toString(),
        ),
    )
    configureHealthRoutes(readinessProbe)
}
