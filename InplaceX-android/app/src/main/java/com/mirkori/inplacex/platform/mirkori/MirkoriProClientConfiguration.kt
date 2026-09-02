package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.PlatformProMembershipSnapshotVerifier
import com.mirkori.platform.sdk.Rs256PlatformProSnapshotSignatureVerifier
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class MirkoriProClientConfiguration(
    val distributionId: String,
    val snapshotVerifier: PlatformProMembershipSnapshotVerifier,
) {
    companion object {
        fun parseOrNull(
            enabled: Boolean,
            distributionId: String,
            encodedPublicKeys: String,
        ): MirkoriProClientConfiguration? {
            if (!enabled) return null
            require(distributionId.matches(ResourceIdPattern))
            val publicKeys = parsePublicKeys(encodedPublicKeys)
            return MirkoriProClientConfiguration(
                distributionId = distributionId,
                snapshotVerifier = PlatformProMembershipSnapshotVerifier(
                    Rs256PlatformProSnapshotSignatureVerifier(publicKeys),
                ),
            )
        }

        private fun parsePublicKeys(value: String): Map<String, PublicKey> {
            require(value.isNotBlank() && value.length <= MaximumEncodedKeysLength)
            val entries = value.split(';')
            require(entries.size in 1..MaximumKeyCount && entries.none(String::isBlank))
            val keys = LinkedHashMap<String, PublicKey>(entries.size)
            entries.forEach { entry ->
                require(entry == entry.trim())
                val separator = entry.indexOf('=')
                require(separator in 1 until entry.lastIndex)
                val keyId = entry.substring(0, separator)
                val encodedKey = entry.substring(separator + 1)
                require(keyId.matches(KeyIdPattern) && keys[keyId] == null)
                val bytes = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrElse {
                    throw IllegalArgumentException("Invalid Mirkori Pro public-key encoding", it)
                }
                require(bytes.size in MinimumPublicKeyBytes..MaximumPublicKeyBytes)
                require(Base64.getEncoder().encodeToString(bytes) == encodedKey)
                val publicKey = runCatching {
                    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
                }.getOrElse {
                    throw IllegalArgumentException("Invalid Mirkori Pro RSA public key", it)
                }
                require(publicKey is RSAPublicKey && publicKey.modulus.bitLength() >= MinimumRsaBits)
                keys[keyId] = publicKey
            }
            return keys
        }

        private val ResourceIdPattern = Regex("[a-z0-9][a-z0-9._-]{1,63}")
        private val KeyIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
        private const val MaximumKeyCount = 8
        private const val MaximumEncodedKeysLength = 32_768
        private const val MinimumPublicKeyBytes = 128
        private const val MaximumPublicKeyBytes = 4_096
        private const val MinimumRsaBits = 2_048
    }
}
