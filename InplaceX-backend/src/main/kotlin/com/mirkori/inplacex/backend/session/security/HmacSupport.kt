package com.mirkori.inplacex.backend.session.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class HmacSha256Key(keyMaterial: ByteArray) {
    private val key: SecretKeySpec = createKey(keyMaterial)

    fun digest(domain: String, vararg components: ByteArray): ByteArray {
        val domainBytes = domain.toByteArray(StandardCharsets.UTF_8)
        return try {
            val mac = Mac.getInstance(ALGORITHM)
            mac.init(key)
            updateLengthPrefixed(mac, domainBytes)
            components.forEach { updateLengthPrefixed(mac, it) }
            mac.doFinal()
        } finally {
            domainBytes.fill(0)
        }
    }

    override fun toString(): String = "HmacSha256Key([redacted])"

    private fun updateLengthPrefixed(mac: Mac, value: ByteArray) {
        val lengthBytes = ByteArray(Int.SIZE_BYTES)
        try {
            ByteBuffer.wrap(lengthBytes).putInt(value.size)
            mac.update(lengthBytes)
            mac.update(value)
        } finally {
            lengthBytes.fill(0)
        }
    }

    private companion object {
        const val ALGORITHM: String = "HmacSHA256"
        const val MINIMUM_KEY_BYTES: Int = 32

        fun createKey(keyMaterial: ByteArray): SecretKeySpec {
            require(keyMaterial.size >= MINIMUM_KEY_BYTES) {
                "HMAC key material must contain at least 32 bytes"
            }
            val keyCopy = keyMaterial.copyOf()
            return try {
                SecretKeySpec(keyCopy, ALGORITHM)
            } finally {
                keyCopy.fill(0)
            }
        }
    }
}
