package com.mirkori.platform.sdk

import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

fun interface PlatformReleaseDecisionVerifier {
    fun verify(
        keyId: String,
        algorithm: String,
        encodedPayload: String,
        encodedSignature: String,
    ): Boolean
}

class Rs256PlatformReleaseDecisionVerifier(
    publicKeys: Map<String, PublicKey>,
) : PlatformReleaseDecisionVerifier {
    private val publicKeys = publicKeys.toMap()
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    init {
        require(this.publicKeys.isNotEmpty())
        require(this.publicKeys.keys.all { it.matches(KeyIdPattern) })
        require(this.publicKeys.values.all { it.algorithm.equals("RSA", ignoreCase = true) })
    }

    override fun verify(
        keyId: String,
        algorithm: String,
        encodedPayload: String,
        encodedSignature: String,
    ): Boolean {
        if (algorithm != "RS256" || !keyId.matches(KeyIdPattern)) return false
        if (!encodedPayload.matches(Base64UrlPattern) || !encodedSignature.matches(Base64UrlPattern)) return false
        val publicKey = publicKeys[keyId] ?: return false
        val signatureBytes = runCatching { decoder.decode(encodedSignature) }.getOrNull() ?: return false
        if (encoder.encodeToString(signatureBytes) != encodedSignature || signatureBytes.size !in 128..1024) {
            signatureBytes.fill(0)
            return false
        }
        return try {
            Signature.getInstance(SignatureAlgorithm).run {
                initVerify(publicKey)
                update(encodedPayload.toByteArray(StandardCharsets.US_ASCII))
                verify(signatureBytes)
            }
        } catch (_: Exception) {
            false
        } finally {
            signatureBytes.fill(0)
        }
    }

    override fun toString(): String = "Rs256PlatformReleaseDecisionVerifier(keyIds=${publicKeys.keys.sorted()}, [redacted])"

    private companion object {
        const val SignatureAlgorithm = "SHA256withRSA"
        val KeyIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
        val Base64UrlPattern = Regex("[A-Za-z0-9_-]{2,65535}")
    }
}
