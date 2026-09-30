package com.mirkori.inplacex.backend.session.codec

import com.mirkori.inplacex.backend.session.contract.PublicDuelParticipant
import com.mirkori.inplacex.backend.session.contract.PublicDuelPhase
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionEvent
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionResult
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionSnapshot
import com.mirkori.inplacex.backend.session.contract.PublicDuelTurn
import com.mirkori.inplacex.backend.session.contract.PublicGameConfig
import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
import com.mirkori.inplacex.backend.session.contract.PublicParticipantSlot
import com.mirkori.inplacex.backend.session.contract.PublicParticipantType
import com.mirkori.inplacex.backend.session.contract.PublicSessionId
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSessionCodecTest {
    @Test
    fun `snapshot and all event variants round trip canonically`() {
        val snapshot = activeSnapshot()
        val snapshotJson = PublicSessionCodec.encodeSnapshot(snapshot)
        assertEquals(snapshot, PublicSessionCodec.decodeSnapshot(snapshotJson))
        assertEquals(
            snapshotJson,
            PublicSessionCodec.encodeSnapshot(PublicSessionCodec.decodeSnapshot(snapshotJson)),
        )

        events().forEach { event ->
            val encoded = PublicSessionCodec.encodeEvent(event)
            assertEquals(event, PublicSessionCodec.decodeEvent(encoded))
            assertEquals(encoded, PublicSessionCodec.encodeEvent(PublicSessionCodec.decodeEvent(encoded)))
        }
    }

    @Test
    fun `closed results and nested objects have deterministic canonical encoding`() {
        val encodedEvent = PublicSessionCodec.encodeEvent(
            PublicDuelSessionEvent.ParticipantPresenceChanged(participantA, connected = true),
        )
        assertEquals(
            """{"payload":{"connected":true,"participantId":"$participantAText"},"schemaVersion":"1.0","type":"session.participantPresenceChanged"}""",
            encodedEvent,
        )

        val results = listOf(
            PublicDuelSessionResult.SecretAccepted(participantA),
            PublicDuelSessionResult.TurnAccepted(1, exactMatches = 1, solved = false),
            PublicDuelSessionResult.PresenceAccepted(participantA, connected = true),
            PublicDuelSessionResult.PhaseAccepted(PublicDuelPhase.ACTIVE_TURN_B),
        )
        results.forEach { result ->
            assertEquals(
                PublicSessionCodec.encodeResult(result),
                PublicSessionCodec.encodeResult(result),
            )
        }
        assertEquals(
            """{"payload":{"connected":true,"participantId":"$participantAText"},"schemaVersion":"1.0","type":"session.presenceAccepted"}""",
            PublicSessionCodec.encodeResult(results[2]),
        )
    }

    @Test
    fun `frame limit accepts exactly 64 KiB and rejects one extra byte`() {
        val event = PublicDuelSessionEvent.ParticipantPresenceChanged(participantA, connected = true)
        val encoded = PublicSessionCodec.encodeEvent(event)
        val padding = MAX_PUBLIC_SESSION_FRAME_BYTES -
            encoded.toByteArray(StandardCharsets.UTF_8).size
        val exactLimit = encoded + " ".repeat(padding)

        assertEquals(MAX_PUBLIC_SESSION_FRAME_BYTES, exactLimit.toByteArray().size)
        assertEquals(event, PublicSessionCodec.decodeEvent(exactLimit))
        assertThrows(IllegalArgumentException::class.java) {
            PublicSessionCodec.decodeEvent("$exactLimit ")
        }

        val oversizedSnapshot = activeSnapshot().copy(
            turns = (1..2_048).map { turnNumber ->
                PublicDuelTurn(
                    turnNumber = turnNumber,
                    actorParticipantId = if (turnNumber % 2 == 0) participantB else participantA,
                    exactMatches = 1,
                    solved = false,
                )
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            PublicSessionCodec.encodeSnapshot(oversizedSnapshot)
        }
    }

    @Test
    fun `malformed unknown duplicate and unicode escaped duplicate frames are rejected`() {
        listOf(
            "",
            "{",
            "[]",
            """{"payload":{},"schemaVersion":"1.0","type":"duel.unknown"}""",
            """{"payload":{"connected":true,"participantId":"$participantAText","extra":false},"schemaVersion":"1.0","type":"session.participantPresenceChanged"}""",
            """{"payload":{"connected":true,"participantId":"$participantAText"},"schemaVersion":"2.0","type":"session.participantPresenceChanged"}""",
            """{"payload":{"connected":true,"connected":false,"participantId":"$participantAText"},"schemaVersion":"1.0","type":"session.participantPresenceChanged"}""",
            """{"payload":{"connected":true,"conn\u0065cted":false,"participantId":"$participantAText"},"schemaVersion":"1.0","type":"session.participantPresenceChanged"}""",
        ).forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                PublicSessionCodec.decodeEvent(malformed)
            }
        }
    }

    @Test
    fun `public decoders reject lexical invalid literals and numbers`() {
        val presence = PublicSessionCodec.encodeEvent(
            PublicDuelSessionEvent.ParticipantPresenceChanged(participantA, connected = true),
        )
        listOf("truE", "FALSE", "True", "falsee", "nulL", "truefalse").forEach { invalidLiteral ->
            assertLexicallyRejected(presence.replace("\"connected\":true", "\"connected\":$invalidLiteral"))
        }

        val turn = PublicSessionCodec.encodeEvent(
            PublicDuelSessionEvent.TurnResult(
                turnNumber = 1,
                actorParticipantId = participantA,
                exactMatches = 1,
                solved = false,
            ),
        )
        listOf(
            "01",
            "00",
            "-01",
            "1.",
            "+1",
            ".1",
            "-.1",
            "1e",
            "1e+",
            "1e-",
            "1.e2",
            "0x1",
            "--1",
            "NaN",
            "Infinity",
        ).forEach { invalidNumber ->
            assertLexicallyRejected(
                turn.replace("\"exactMatches\":1", "\"exactMatches\":$invalidNumber"),
            )
        }
    }

    @Test
    fun `scanner accepts exact literals and RFC compatible number forms`() {
        listOf(
            "true",
            "false",
            "null",
            "0",
            "-0",
            "19",
            "-19",
            "0.125",
            "-1.25",
            "1e3",
            "-2E-3",
            "4.2e+1",
        ).forEach { primitive ->
            CanonicalPublicJson.parseFrame("""{"value":$primitive}""")
        }

        val turn = PublicSessionCodec.encodeEvent(
            PublicDuelSessionEvent.TurnResult(
                turnNumber = 1,
                actorParticipantId = participantA,
                exactMatches = 1,
                solved = false,
            ),
        )
        val decoded = PublicSessionCodec.decodeEvent(
            turn.replace("\"exactMatches\":1", "\"exactMatches\":-0"),
        )
        assertEquals(
            PublicDuelSessionEvent.TurnResult(1, participantA, exactMatches = 0, solved = false),
            decoded,
        )
    }

    @Test
    fun `deep sub limit input fails with controlled depth exception`() {
        val depth = 10_000
        val deeplyNested = "[".repeat(depth) + "0" + "]".repeat(depth)
        assertTrue(deeplyNested.toByteArray().size < MAX_PUBLIC_SESSION_FRAME_BYTES)

        val error = assertThrows(IllegalArgumentException::class.java) {
            PublicSessionCodec.decodeEvent(deeplyNested)
        }
        assertTrue(error.message.orEmpty().contains("maximum depth"))
        assertFalse(StackOverflowError::class.java.isInstance(error))
    }

    @Test
    fun `forbidden names and values are rejected before typed mapping`() {
        val safe = PublicSessionCodec.encodeEvent(
            PublicDuelSessionEvent.ParticipantPresenceChanged(participantA, connected = true),
        )
        listOf(
            "secretValue",
            "hashValue",
            "cipherValue",
            "seedValue",
            "keyMaterialValue",
            "privateKeyValue",
            "cookieValue",
            "accessTokenValue",
            "refreshTokenValue",
            "idTokenValue",
            "purchaseTokenValue",
            "integrityValue",
            "providerPayloadValue",
            "rawPayloadValue",
            "rawRequestBodyValue",
            "installationIdValue",
            "deviceIdentifierValue",
            "player@example.invalid",
            "Bearer abc",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
            "ToKeNValue",
            "guessValue",
            """secr\u0065tValue""",
            "1234",
            "12-34",
            "a".repeat(64),
        ).forEach { forbidden ->
            assertThrows(IllegalArgumentException::class.java) {
                PublicSessionCodec.decodeEvent(safe.replace(participantAText, forbidden))
            }
        }

        listOf(
            "secretValue",
            "hashValue",
            "cipherValue",
            "seedValue",
            "keyMaterial",
            "privateKey",
            "cookieValue",
            "accessToken",
            "refreshToken",
            "idToken",
            "purchaseToken",
            "integrityValue",
            "providerPayload",
            "rawPayload",
            "rawRequestBody",
            "installationId",
            "deviceIdentifier",
            "email",
        ).forEach { forbiddenName ->
            val attack =
                """{"payload":{"connected":true,"participantId":"$participantAText","$forbiddenName":"safe"},"schemaVersion":"1.0","type":"session.participantPresenceChanged"}"""
            val error = assertThrows(IllegalArgumentException::class.java) {
                PublicSessionCodec.decodeEvent(attack)
            }
            assertTrue(error.message.orEmpty().contains("forbidden field"))
        }

        val escapedNestedKey =
            """{"payload":{"connected":true,"participantId":"$participantAText","nested":{"secr\u0065tValue":"safe"}},"schemaVersion":"1.0","type":"session.participantPresenceChanged"}"""
        val nestedError = assertThrows(IllegalArgumentException::class.java) {
            PublicSessionCodec.decodeEvent(escapedNestedKey)
        }
        assertTrue(nestedError.message.orEmpty().contains("forbidden field"))
    }

    @Test
    fun `security checks cover every string bearing snapshot field`() {
        val snapshot = PublicSessionCodec.encodeSnapshot(activeSnapshot())
        mapOf(
            sessionIdText to "secretValue",
            participantAText to "hashValue",
            participantBText to "1234",
            "ACTIVE_TURN_B" to "cipherValue",
            "HUMAN" to "cookieValue",
            "\"A\"" to "\"integrityValue\"",
            "\"1.0\"" to "\"Bearer token\"",
        ).forEach { (safeValue, forbiddenValue) ->
            val attack = snapshot.replaceFirst(safeValue, forbiddenValue)
            assertTrue(attack != snapshot)
            assertThrows(IllegalArgumentException::class.java) {
                PublicSessionCodec.decodeSnapshot(attack)
            }
        }
    }

    private fun assertLexicallyRejected(json: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PublicSessionCodec.decodeEvent(json)
        }
        assertEquals("Invalid public session JSON frame", error.message)
    }

    private fun events(): List<PublicDuelSessionEvent> = listOf(
        PublicDuelSessionEvent.ParticipantPresenceChanged(participantA, connected = false),
        PublicDuelSessionEvent.SecretStatusChanged(participantA, secretSubmitted = true),
        PublicDuelSessionEvent.TurnResult(1, participantA, exactMatches = 1, solved = false),
        PublicDuelSessionEvent.PhaseChanged(PublicDuelPhase.ACTIVE_TURN_B, participantB),
        PublicDuelSessionEvent.Finished(participantA),
    )

    private fun activeSnapshot(): PublicDuelSessionSnapshot = PublicDuelSessionSnapshot(
        sessionId = PublicSessionId.parse(sessionIdText),
        revision = 1,
        eventSequence = 1,
        phase = PublicDuelPhase.ACTIVE_TURN_B,
        config = PublicGameConfig(codeLength = 4, attemptLimit = 10),
        participants = listOf(
            PublicDuelParticipant(
                participantId = participantA,
                slot = PublicParticipantSlot.A,
                participantType = PublicParticipantType.HUMAN,
                secretSubmitted = true,
                connected = true,
            ),
            PublicDuelParticipant(
                participantId = participantB,
                slot = PublicParticipantSlot.B,
                participantType = PublicParticipantType.HUMAN,
                secretSubmitted = true,
                connected = true,
            ),
        ),
        turns = listOf(PublicDuelTurn(1, participantA, exactMatches = 1, solved = false)),
        currentActorParticipantId = participantB,
    )

    private companion object {
        const val sessionIdText: String = "10000000-0000-4000-8000-000000000001"
        const val participantAText: String = "20000000-0000-4000-8000-000000000002"
        const val participantBText: String = "30000000-0000-4000-8000-000000000003"
        val participantA: PublicParticipantId = PublicParticipantId.parse(participantAText)
        val participantB: PublicParticipantId = PublicParticipantId.parse(participantBText)
    }
}
