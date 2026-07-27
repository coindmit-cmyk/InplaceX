package com.mirkori.inplacex.backend.app

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class OnlineRuntimeConfig(
    val issuer: String,
    val audience: String,
    val verificationKey: PublicKey,
) {
    override fun toString(): String =
        "OnlineRuntimeConfig(issuer=$issuer, audience=$audience, verificationKey=[public])"

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
            return OnlineRuntimeConfig(issuer, audience, key)
        }

        const val IssuerKey = "INPLACEX_ONLINE_TOKEN_ISSUER"
        const val AudienceKey = "INPLACEX_ONLINE_TOKEN_AUDIENCE"
        const val PublicKeyKey = "INPLACEX_ONLINE_PUBLIC_KEY_X509_BASE64"
    }
}

private fun String.isSafePolicyValue(): Boolean =
    length in 1..256 && none { it.isISOControl() || it.isWhitespace() }
