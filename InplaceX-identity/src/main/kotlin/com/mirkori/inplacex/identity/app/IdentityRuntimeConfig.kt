package com.mirkori.inplacex.identity.app

import com.mirkori.inplacex.backend.app.DatabaseRuntimeConfig
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class IdentityRuntimeConfig private constructor(
    val host: String,
    val port: Int,
    val environment: String,
    val database: DatabaseRuntimeConfig,
    val issuer: String,
    val audience: String,
    val privateKey: PrivateKey,
) {
    override fun toString(): String =
        "IdentityRuntimeConfig(host=$host, port=$port, environment=$environment, " +
            "issuer=$issuer, audience=$audience, privateKey=[redacted])"

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): IdentityRuntimeConfig {
            val port = (environment[PortKey] ?: DefaultPort.toString()).toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("$PortKey must be an integer in 1..65535")
            val database = DatabaseRuntimeConfig.fromEnvironmentOrNull(environment)
                ?: throw IllegalArgumentException("Identity process requires database configuration")
            val issuer = environment[IssuerKey]?.takeIf(String::isSafePolicyValue)
                ?: throw IllegalArgumentException("$IssuerKey is required")
            val audience = environment[AudienceKey]?.takeIf(String::isSafePolicyValue)
                ?: throw IllegalArgumentException("$AudienceKey is required")
            val encodedKey = environment[PrivateKeyKey]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$PrivateKeyKey is required")
            val privateKey = runCatching {
                val bytes = Base64.getDecoder().decode(encodedKey)
                try {
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
                } finally {
                    bytes.fill(0)
                }
            }.getOrElse {
                throw IllegalArgumentException("$PrivateKeyKey is not a valid RSA PKCS8 key")
            }

            return IdentityRuntimeConfig(
                host = environment[HostKey]?.takeIf(String::isNotBlank) ?: DefaultHost,
                port = port,
                environment = environment[EnvironmentKey]?.takeIf(String::isNotBlank) ?: "development",
                database = database,
                issuer = issuer,
                audience = audience,
                privateKey = privateKey,
            )
        }

        const val HostKey = "INPLACEX_IDENTITY_HOST"
        const val PortKey = "INPLACEX_IDENTITY_PORT"
        const val EnvironmentKey = "INPLACEX_IDENTITY_ENVIRONMENT"
        const val IssuerKey = "INPLACEX_IDENTITY_ISSUER"
        const val AudienceKey = "INPLACEX_IDENTITY_AUDIENCE"
        const val PrivateKeyKey = "INPLACEX_IDENTITY_PRIVATE_KEY_PKCS8_BASE64"
        const val DefaultHost = "127.0.0.1"
        const val DefaultPort = 8081
    }
}

private fun String.isSafePolicyValue(): Boolean =
    length in 1..256 && none { it.isISOControl() || it.isWhitespace() }
