package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.ads.configureAdMarketRoutes
import com.mirkori.inplacex.backend.persistence.PostgresDatabase
import com.mirkori.inplacex.backend.persistence.JdbcPlayerRepository
import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.health.AlwaysReadyProbe
import com.mirkori.inplacex.backend.health.ReadinessProbe
import com.mirkori.inplacex.backend.health.ReadinessMetrics
import com.mirkori.inplacex.backend.health.ReadinessMetricsSource
import com.mirkori.inplacex.backend.health.configureHealthRoutes
import com.mirkori.inplacex.backend.online.AuthoritativeOnlineDuelService
import com.mirkori.inplacex.backend.online.configureOnlineRoutes
import com.mirkori.inplacex.backend.online.OnlinePlayerProvisioner
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.InMemoryOnlineSessionEventSequence
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionEventSequence
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.logging.InplaceXLogger
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

fun main() {
    val environment = System.getenv()
    val config = BackendRuntimeConfig.fromEnvironment(environment)
    val activationGuard = if (config.isProduction) {
        RuntimeActivationGuard.fromEnvironment(
            environment = environment,
            identity = requireNotNull(config.releaseIdentity),
        ).also {
            it.requireAuthorized()
            it.startMonitor()
        }
    } else {
        null
    }
    try {
        embeddedServer(
            factory = Netty,
            host = config.host,
            port = config.port,
        ) {
            backendModule(config)
        }.start(wait = true)
    } finally {
        activationGuard?.close()
    }
}

fun Application.backendModule(
    config: BackendRuntimeConfig = BackendRuntimeConfig.fromEnvironment(),
    logger: InplaceXLogger = InplaceXLogger(),
    readinessProbe: ReadinessProbe? = null,
) {
    val drainController = RuntimeDrainController.fromEnvironment(
        environment = System.getenv(),
        production = config.isProduction,
    )
    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.path().startsWith("/api/v1/")) return@intercept
        val lease = drainController.tryAcquireOnlineRequest()
        if (lease == null) {
            call.respondText(
                text = "{\"error\":\"service_draining\"}",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.ServiceUnavailable,
            )
            finish()
            return@intercept
        }
        lease.use { proceed() }
    }
    val database = config.database?.let { databaseConfig ->
        PostgresDatabase.connect(databaseConfig).also { it.migrate() }
    }
    database?.let { databaseHandle ->
        environment.monitor.subscribe(ApplicationStopped) {
            databaseHandle.close()
        }
    }
    val onlineService = config.online?.let { onlineConfig ->
        val sessionEvents = database?.let { JdbcOnlineSessionEventSequence(it.dataSource) }
            ?: InMemoryOnlineSessionEventSequence()
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
            sessionEvents = sessionEvents,
        ).also { service ->
            environment.monitor.subscribe(ApplicationStopped) {
                service.close()
            }
            configureOnlineRoutes(
                verifier = JwtAccessTokenVerifier(
                    verificationKey = onlineConfig.verificationKey,
                    policy = JwtVerificationPolicy.platformGame(
                        issuer = onlineConfig.issuer,
                        audience = onlineConfig.audience,
                        gameId = onlineConfig.gameId,
                    ),
                ),
                service = service,
                eventSequences = sessionEvents,
                playerProvisioner = database?.let { databaseHandle ->
                    val players = JdbcPlayerRepository(databaseHandle.dataSource)
                    OnlinePlayerProvisioner(players::ensurePlatformPlayer)
                } ?: OnlinePlayerProvisioner { },
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
            "adMarketConfigured" to (config.adMarket != null).toString(),
            "adMarketSource" to (config.adMarket?.source?.name ?: "NONE"),
        ),
    )
    configureHealthRoutes(
        readinessProbe ?: database?.let { databaseHandle ->
            TransitionLoggingDatabaseReadinessProbe(databaseHandle, logger)
        }
        ?: AlwaysReadyProbe,
        config.releaseIdentity,
        drainController,
    )
    configureAdMarketRoutes(config.adMarket)
}

private class TransitionLoggingDatabaseReadinessProbe(
    private val database: PostgresDatabase,
    private val logger: InplaceXLogger,
) : ReadinessProbe, ReadinessMetricsSource {
    private val previousReady = AtomicReference<Boolean?>(null)
    private val checks = AtomicLong()
    private val failures = AtomicLong()
    private val transitions = AtomicLong()

    override fun isReady(): Boolean {
        checks.incrementAndGet()
        var failureType = "none"
        val ready = database.isReady { error -> failureType = error::class.java.simpleName.take(64) }
        if (!ready) failures.incrementAndGet()
        val previous = previousReady.getAndSet(ready)
        if (previous != ready) {
            transitions.incrementAndGet()
            if (ready) {
                logger.info(
                    tag = "BackendReadiness",
                    message = "database readiness recovered",
                    attributes = mapOf("outcome" to "ready"),
                )
            } else {
                logger.warn(
                    tag = "BackendReadiness",
                    message = "database readiness failed",
                    attributes = mapOf("outcome" to "not_ready", "failureType" to failureType),
                )
            }
        }
        return ready
    }

    override fun readinessMetrics(): ReadinessMetrics = ReadinessMetrics(
        checks = checks.get(),
        failures = failures.get(),
        transitions = transitions.get(),
        ready = previousReady.get() == true,
    )
}
