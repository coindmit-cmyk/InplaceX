package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.persistence.PostgresDatabase
import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.health.AlwaysReadyProbe
import com.mirkori.inplacex.backend.health.ReadinessProbe
import com.mirkori.inplacex.backend.health.configureHealthRoutes
import com.mirkori.inplacex.backend.online.AuthoritativeOnlineDuelService
import com.mirkori.inplacex.backend.online.configureOnlineRoutes
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
    val onlineService = config.online?.let { onlineConfig ->
        AuthoritativeOnlineDuelService().also { service ->
            environment.monitor.subscribe(ApplicationStopped) {
                service.close()
            }
            configureOnlineRoutes(
                verifier = JwtAccessTokenVerifier(
                    verificationKey = onlineConfig.verificationKey,
                    policy = JwtVerificationPolicy(
                        issuer = onlineConfig.issuer,
                        audience = onlineConfig.audience,
                    ),
                ),
                service = service,
            )
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
            "onlineConfigured" to (onlineService != null).toString(),
        ),
    )
    configureHealthRoutes(readinessProbe)
}
