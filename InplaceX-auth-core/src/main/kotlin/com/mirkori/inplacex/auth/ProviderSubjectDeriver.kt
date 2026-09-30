package com.mirkori.inplacex.auth

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ProviderSubjectDeriver {
    fun derive(
        provider: AuthProvider,
        externalId: String,
        key: ByteArray,
    ): ProviderIdentity {
        require(key.size >= MinimumKeyBytes)
        require(externalId.length in 1..MaximumExternalIdCharacters)
        require(externalId.none(Char::isISOControl))
        val payload = "${provider.wireName}\n$externalId".toByteArray(StandardCharsets.UTF_8)
        val digest = hmacSha256(key, payload)
        val subject = "v1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return ProviderIdentity(provider = provider, subject = subject)
    }

    internal fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray {
        val mac = Mac.getInstance(HmacAlgorithm)
        mac.init(SecretKeySpec(key, HmacAlgorithm))
        return mac.doFinal(value)
    }

    private const val HmacAlgorithm = "HmacSHA256"
    private const val MinimumKeyBytes = 32
    private const val MaximumExternalIdCharacters = 512
}
