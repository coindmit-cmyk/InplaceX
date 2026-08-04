package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.persistence.PostgresDatabase
import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.health.AlwaysReadyProbe
import com.mirkori.inplacex.backend.health.ReadinessProbe
import com.mirkori.inplacex.backend.health.configureHealthRoutes
import com.mirkori.inplacex.backend.online.AuthoritativeOnlineDuelService
import com.mirkori.inplacex.backend.online.configureOnlineRoutes
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.logging.InplaceXLogger
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Duration

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
        val sessionRepository = database?.let { databaseHandle ->
            JdbcOnlineSessionRepository(
                dataSource = databaseHandle.dataSource,
                cipher = requireNotNull(onlineConfig.stateEncryptionKey).createCipher(),
            )
        }
        val lobbyRepository = database?.let { databaseHandle ->
            JdbcOnlineLobbyRepository(databaseHandle.dataSource, requireNotNull(sessionRepository))
        }
        AuthoritativeOnlineDuelService(
            botFallbackDelay = onlineConfig.botFallbackDelay,
            sweepInterval = Duration.ofSeconds(30),
            logger = logger,
            sessionRepository = sessionRepository,
            lobbyRepository = lobbyRepository,
        ).also { service ->
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
            "onlinePersistenceConfigured" to (database != null && onlineService != null).toString(),
        ),
    )
    configureHealthRoutes(readinessProbe)
}
