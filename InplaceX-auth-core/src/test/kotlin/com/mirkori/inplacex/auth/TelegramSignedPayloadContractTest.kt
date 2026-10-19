package com.mirkori.inplacex.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TelegramSignedPayloadContractTest {
    private val botToken = "123456789:local_fixture_token"
    private val authAt = Instant.ofEpochSecond(1_700_000_000)
    private val signedFields = linkedMapOf(
        "username" to "fixture_player",
        "id" to "424242",
        "first_name" to "Mira",
        "auth_date" to "1700000000",
        "hash" to "6375a59f2689a8aec98812e6a7f96e8ab9d55c33c650290262abbb7486e7b6f8",
    )

    @Test
    fun acceptsDocumentedLiteralPayloadAndRejectsTampering() {
        val verifier = TelegramLoginVerifier()
        val verified = verifier.verify(signedFields, botToken, authAt)

        assertNotNull(verified)
        assertEquals("424242", verified!!.externalUserId)
        assertEquals("Mira", verified.displayName)
        assertEquals(authAt, verified.authenticatedAt)

        val changedName = signedFields.toMutableMap().apply { put("first_name", "Mallory") }
        assertNull(verifier.verify(changedName, botToken, authAt))
        assertNull(verifier.verify(signedFields, "987654321:another_local_fixture_token", authAt))

        val unexpectedField = signedFields.toMutableMap().apply { put("unexpected", "value") }
        assertNull(verifier.verify(unexpectedField, botToken, authAt))

        val missingHash = signedFields.toMutableMap().apply { remove("hash") }
        assertNull(verifier.verify(missingHash, botToken, authAt))

        val shortHash = signedFields.toMutableMap().apply { put("hash", getValue("hash").dropLast(1)) }
        assertNull(verifier.verify(shortHash, botToken, authAt))

        val nonHexHash = signedFields.toMutableMap().apply { put("hash", "z".repeat(64)) }
        assertNull(verifier.verify(nonHexHash, botToken, authAt))
    }

    @Test
    fun acceptsConfiguredFreshnessBoundaryAndRejectsOneStepOutside() {
        val verifier = TelegramLoginVerifier(
            maximumAge = Duration.ofSeconds(120),
            futureClockSkew = Duration.ofSeconds(5),
        )

        assertNotNull(verifier.verify(signedFields, botToken, authAt))
        assertNotNull(verifier.verify(signedFields, botToken, authAt.plusSeconds(120)))
        assertNotNull(verifier.verify(signedFields, botToken, authAt.minusSeconds(5)))
        assertNull(verifier.verify(signedFields, botToken, authAt.plusSeconds(121)))
        assertNull(verifier.verify(signedFields, botToken, authAt.minusSeconds(6)))
    }
}
