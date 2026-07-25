package com.mirkori.inplacex.backend.session.security

import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
import com.mirkori.inplacex.backend.session.contract.PublicSessionId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HmacSecretFingerprinterTest {
    @Test
    fun `fingerprint is stable only inside the same keyed scope`() {
        val first = HmacSecretFingerprinter(keyMaterial(1))
        val second = HmacSecretFingerprinter(keyMaterial(2))

        val expected = first.fingerprint(sessionA, participantA, secret())
        assertEquals(expected, first.fingerprint(sessionA, participantA, secret()))
        assertNotEquals(expected, second.fingerprint(sessionA, participantA, secret()))
        assertNotEquals(expected, first.fingerprint(sessionB, participantA, secret()))
        assertNotEquals(expected, first.fingerprint(sessionA, participantB, secret()))
        assertTrue(first.matches(expected, sessionA, participantA, secret()))
        assertFalse(first.matches(expected, sessionA, participantA, alternateSecret()))
    }

    @Test
    fun `fingerprint and key representations never expose source material`() {
        val pepper = keyMaterial(3)
        val service = HmacSecretFingerprinter(pepper)
        val fingerprint = service.fingerprint(sessionA, participantA, secret())
        val stored = fingerprint.asStorageValue()
        pepper.fill(0)

        assertTrue(stored.startsWith("hmac-sha256-v1:"))
        assertEquals(fingerprint, service.fingerprint(sessionA, participantA, secret()))
        assertFalse(fingerprint.toString().contains(stored))
        assertEquals("HmacSecretFingerprinter(pepper=[redacted])", service.toString())
    }

    @Test
    fun `hmac domains are separated and weak or non ascii input is rejected`() {
        val key = keyMaterial(7)
        val hmac = HmacSha256Key(key)
        val value = "same-input".toByteArray()
        val secretDigest = hmac.digest("inplacex.session.secret-fingerprint.v1", value)
        val logDigest = hmac.digest("inplacex.session.log.session.v1", value)
        try {
            assertFalse(secretDigest.contentEquals(logDigest))
        } finally {
            secretDigest.fill(0)
            logDigest.fill(0)
            value.fill(0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            HmacSecretFingerprinter(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HmacSecretFingerprinter(key).fingerprint(
                sessionA,
                participantA,
                charArrayOf('1', '2', 'a', '4'),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HmacSecretFingerprinter(key).fingerprint(
                sessionA,
                participantA,
                charArrayOf('1', '2', '\u0663', '4'),
            )
        }
    }

    @Test
    fun `temporary secret bytes are zeroed on success and failure`() {
        lateinit var successBuffer: ByteArray
        val input = secret()
        val copied = withDigitSecretBytes(input) { encoded ->
            successBuffer = encoded
            encoded.copyOf()
        }
        val expected = ByteArray(input.size) { index -> input[index].code.toByte() }
        try {
            assertArrayEquals(expected, copied)
            assertTrue(successBuffer.all { it == 0.toByte() })
            assertArrayEquals(secret(), input)
        } finally {
            expected.fill(0)
            copied.fill(0)
        }

        lateinit var failureBuffer: ByteArray
        assertThrows(IllegalStateException::class.java) {
            withDigitSecretBytes(input) { encoded ->
                failureBuffer = encoded
                throw IllegalStateException("synthetic failure")
            }
        }
        assertTrue(failureBuffer.all { it == 0.toByte() })
    }

    private fun keyMaterial(discriminator: Int): ByteArray =
        ByteArray(32) { index -> (index xor discriminator).toByte() }

    private fun secret(): CharArray = charArrayOf('1', '2', '3', '4')

    private fun alternateSecret(): CharArray = charArrayOf('5', '6', '7', '8')

    private companion object {
        val sessionA: PublicSessionId =
            PublicSessionId.parse("10000000-0000-4000-8000-000000000001")
        val sessionB: PublicSessionId =
            PublicSessionId.parse("10000000-0000-4000-8000-000000000002")
        val participantA: PublicParticipantId =
            PublicParticipantId.parse("20000000-0000-4000-8000-000000000001")
        val participantB: PublicParticipantId =
            PublicParticipantId.parse("20000000-0000-4000-8000-000000000002")
    }
}
