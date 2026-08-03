package com.mirkori.inplacex.backend.online.persistence

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OnlineStateCipherTest {
    @Test
    fun `encrypted state survives cipher recreation and is bound to its session`() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val plaintext = "server-only-secret-state".toByteArray()
        val encrypted = OnlineStateCipher(key).use { cipher -> cipher.encrypt("session-a", plaintext) }
        val restored = OnlineStateCipher(key).use { cipher -> cipher.decrypt("session-a", encrypted) }

        assertArrayEquals(plaintext, restored)
        assertFalse(encrypted.ciphertext.contentEquals(plaintext))
        assertThrows(OnlineStateDecryptionException::class.java) {
            OnlineStateCipher(key).use { cipher -> cipher.decrypt("session-b", encrypted) }
        }
        plaintext.fill(0)
        restored.fill(0)
        key.fill(0)
    }

    @Test
    fun `tampered ciphertext fails authenticated decryption`() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val encrypted = OnlineStateCipher(key).use { cipher ->
            cipher.encrypt("session-a", "private".toByteArray())
        }
        encrypted.ciphertext[encrypted.ciphertext.lastIndex] =
            (encrypted.ciphertext.last().toInt() xor 1).toByte()

        assertThrows(OnlineStateDecryptionException::class.java) {
            OnlineStateCipher(key).use { cipher -> cipher.decrypt("session-a", encrypted) }
        }
        key.fill(0)
    }
}
