package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.persistence.PostgresDatabase
import com.mirkori.inplacex.backend.health.AlwaysReadyProbe
import com.mirkori.inplacex.backend.health.ReadinessProbe
import com.mirkori.inplacex.backend.health.configureHealthRoutes
import com.mirkori.inplacex.logging.InplaceXLogger
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
    val database = config.database?.let { databaseConfig ->
        PostgresDatabase.connect(databaseConfig).also { it.migrate() }
    }
    database?.let { databaseHandle ->
        environment.monitor.subscribe(ApplicationStopped) {
            databaseHandle.close()
        }
    }
    logger.info(
        tag = "BackendRuntime",
        message = "backend module initialized",
        attributes = mapOf(
            "environment" to config.environment,
            "host" to config.host,
            "port" to config.port.toString(),
            "databaseConfigured" to (database != null).toString(),
        ),
    )
    configureHealthRoutes(readinessProbe)
}
