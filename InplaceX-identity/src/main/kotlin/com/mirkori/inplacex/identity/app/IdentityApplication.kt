package com.mirkori.inplacex.identity.app

import com.mirkori.inplacex.backend.health.AlwaysReadyProbe
import com.mirkori.inplacex.backend.health.configureHealthRoutes
import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.identity.CredentialPolicy
import com.mirkori.inplacex.backend.identity.GoogleApiIdentityVerifier
import com.mirkori.inplacex.backend.identity.GuestIdentityService
import com.mirkori.inplacex.backend.identity.JdbcGuestIdentityRepository
import com.mirkori.inplacex.backend.identity.Rs256AccessTokenIssuer
import com.mirkori.inplacex.backend.identity.configureIdentityRoutes
import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
import com.mirkori.inplacex.backend.persistence.PostgresDatabase
import com.mirkori.inplacex.logging.InplaceXLogger
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPublicKeySpec

fun main() {
    val config = IdentityRuntimeConfig.fromEnvironment()
    embeddedServer(Netty, host = config.host, port = config.port) {
        identityModule(config)
    }.start(wait = true)
}

fun Application.identityModule(
    config: IdentityRuntimeConfig,
    logger: InplaceXLogger = InplaceXLogger(),
) {
    val database = PostgresDatabase.connect(config.database).also { it.migrate() }
    environment.monitor.subscribe(ApplicationStopped) { database.close() }
    val policy = CredentialPolicy(config.issuer, config.audience)
    val accessTokenVerifier = JwtAccessTokenVerifier(
        verificationKey = derivePublicKey(config.privateKey),
        policy = JwtVerificationPolicy(
            issuer = config.issuer,
            audience = config.audience,
        ),
    )
    val service = GuestIdentityService(
        identities = JdbcGuestIdentityRepository(database.dataSource),
        saves = JdbcSaveRepository(database.dataSource),
        policy = policy,
        accessTokenIssuer = Rs256AccessTokenIssuer(config.privateKey, policy),
        googleIdentityVerifier = config.googleWebClientId?.let(::GoogleApiIdentityVerifier),
        logger = logger,
    )
    logger.info(
        tag = "IdentityRuntime",
        message = "identity module initialized",
        attributes = mapOf(
            "environment" to config.environment,
            "host" to config.host,
            "port" to config.port.toString(),
            "googleConfigured" to (config.googleWebClientId != null).toString(),
        ),
    )
    configureHealthRoutes(AlwaysReadyProbe)
    configureIdentityRoutes(service, accessTokenVerifier)
}

private fun derivePublicKey(privateKey: java.security.PrivateKey): java.security.PublicKey {
    val rsa = privateKey as? RSAPrivateCrtKey
        ?: throw IllegalArgumentException("Identity RSA key must include CRT public parameters")
    return KeyFactory.getInstance("RSA").generatePublic(
        RSAPublicKeySpec(rsa.modulus, rsa.publicExponent),
    )
}
