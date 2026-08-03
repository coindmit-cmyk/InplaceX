package com.mirkori.inplacex.backend.online.persistence

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedOnlineState(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

class OnlineStateDecryptionException(cause: Throwable) :
    IllegalStateException("Durable online state failed authenticated decryption", cause)

/** Authenticated encryption boundary for recoverable server-only duel state. */
class OnlineStateCipher(
    keyMaterial: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val keyBytes = keyMaterial.copyOf()
    @Volatile
    private var closed = false

    init {
        require(keyBytes.size == KeyBytes) { "Online state encryption key must be exactly 256 bits" }
    }

    fun encrypt(sessionId: String, plaintext: ByteArray): EncryptedOnlineState {
        ensureOpen()
        require(sessionId.isNotBlank())
        val iv = ByteArray(IvBytes).also(secureRandom::nextBytes)
        return EncryptedOnlineState(
            iv = iv,
            ciphertext = cipher(Cipher.ENCRYPT_MODE, sessionId, iv).doFinal(plaintext),
        )
    }

    fun decrypt(sessionId: String, encrypted: EncryptedOnlineState): ByteArray {
        ensureOpen()
        require(sessionId.isNotBlank())
        require(encrypted.iv.size == IvBytes) { "Online state IV has an invalid size" }
        return try {
            cipher(Cipher.DECRYPT_MODE, sessionId, encrypted.iv).doFinal(encrypted.ciphertext)
        } catch (error: GeneralSecurityException) {
            throw OnlineStateDecryptionException(error)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            keyBytes.fill(0)
        }
    }

    private fun cipher(mode: Int, sessionId: String, iv: ByteArray): Cipher =
        Cipher.getInstance(Transformation).apply {
            init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TagBits, iv))
            updateAAD("$AadPrefix:$sessionId".toByteArray(Charsets.UTF_8))
        }

    private fun ensureOpen() {
        check(!closed) { "Online state cipher is closed" }
    }

    private companion object {
        const val KeyBytes = 32
        const val IvBytes = 12
        const val TagBits = 128
        const val Transformation = "AES/GCM/NoPadding"
        const val AadPrefix = "inplacex-online-state:v1"
    }
}
