package com.mirkori.inplacex.backend.app

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64

data class OnlineRuntimeConfig(
    val issuer: String,
    val audience: String,
    val verificationKey: PublicKey,
    val botFallbackDelay: Duration,
) {
    override fun toString(): String =
        "OnlineRuntimeConfig(issuer=$issuer, audience=$audience, verificationKey=[public], " +
            "botFallbackDelaySeconds=${botFallbackDelay.seconds})"

    companion object {
        fun fromEnvironmentOrNull(environment: Map<String, String>): OnlineRuntimeConfig? {
            val supplied = listOf(IssuerKey, AudienceKey, PublicKeyKey).filter(environment::containsKey)
            if (supplied.isEmpty()) return null
            require(supplied.size == 3) {
                "Online verification configuration must provide issuer, audience, and public key together"
            }
            val issuer = environment.getValue(IssuerKey).takeIf(String::isSafePolicyValue)
                ?: throw IllegalArgumentException("$IssuerKey has an invalid format")
            val audience = environment.getValue(AudienceKey).takeIf(String::isSafePolicyValue)
                ?: throw IllegalArgumentException("$AudienceKey has an invalid format")
            val encoded = environment.getValue(PublicKeyKey).takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$PublicKeyKey is required")
            val key = runCatching {
                val bytes = Base64.getDecoder().decode(encoded)
                try {
                    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
                } finally {
                    bytes.fill(0)
                }
            }.getOrElse {
                throw IllegalArgumentException("$PublicKeyKey is not a valid RSA X509 public key")
            }
            val fallbackSeconds = environment[BotFallbackSecondsKey]
                ?.toLongOrNull()
                ?.takeIf { it in MinimumBotFallbackSeconds..MaximumBotFallbackSeconds }
                ?: if (environment.containsKey(BotFallbackSecondsKey)) {
                    throw IllegalArgumentException(
                        "$BotFallbackSecondsKey must be between " +
                            "$MinimumBotFallbackSeconds and $MaximumBotFallbackSeconds",
                    )
                } else {
                    DefaultBotFallbackSeconds
                }
            return OnlineRuntimeConfig(
                issuer = issuer,
                audience = audience,
                verificationKey = key,
                botFallbackDelay = Duration.ofSeconds(fallbackSeconds),
            )
        }

        const val IssuerKey = "INPLACEX_ONLINE_TOKEN_ISSUER"
        const val AudienceKey = "INPLACEX_ONLINE_TOKEN_AUDIENCE"
        const val PublicKeyKey = "INPLACEX_ONLINE_PUBLIC_KEY_X509_BASE64"
        const val BotFallbackSecondsKey = "INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS"
        const val DefaultBotFallbackSeconds = 5L
        const val MinimumBotFallbackSeconds = 1L
        const val MaximumBotFallbackSeconds = 60L
    }
}

private fun String.isSafePolicyValue(): Boolean =
    length in 1..256 && none { it.isISOControl() || it.isWhitespace() }
