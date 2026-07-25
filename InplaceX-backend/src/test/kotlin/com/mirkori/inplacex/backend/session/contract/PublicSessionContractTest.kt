package com.mirkori.inplacex.backend.session.contract

import com.mirkori.inplacex.backend.session.codec.PublicSessionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSessionContractTest {
    @Test
    fun `snapshot is viewer neutral and contains outcomes instead of raw attempts`() {
        val snapshot = activeSnapshot()

        assertTrue(snapshot.participants.all { it.secretSubmitted })
        assertEquals(1, snapshot.turns.single().exactMatches)
        assertFalse(PublicDuelTurn::class.java.declaredFields.any { it.name == "guess" })
        assertFalse(
            PublicDuelSessionSnapshot::class.java.declaredFields.any {
                it.name.contains("secret", ignoreCase = true)
            },
        )
    }

    @Test
    fun `snapshot rejects incoherent authority and score states`() {
        assertThrows(IllegalArgumentException::class.java) {
            activeSnapshot().copy(currentActorParticipantId = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            activeSnapshot().copy(
                turns = listOf(
                    PublicDuelTurn(2, participantA, exactMatches = 1, solved = false),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            activeSnapshot().copy(
                turns = listOf(
                    PublicDuelTurn(1, participantA, exactMatches = 1, solved = true),
                ),
            )
        }
    }

    @Test
    fun `public identifiers accept only canonical non nil UUIDs`() {
        assertEquals(sessionIdText, PublicSessionId.parse(sessionIdText).value)
        assertThrows(IllegalArgumentException::class.java) { PublicSessionId.parse("1234") }
        assertThrows(IllegalArgumentException::class.java) {
            PublicSessionId.parse("00000000-0000-0000-0000-000000000000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicSessionId.parse("a0000000-0000-4000-8000-000000000001".uppercase())
        }
    }

    @Test
    fun `slice exposes no intent actor command or outcome decoder API`() {
        listOf(
            "com.mirkori.inplacex.backend.session.contract.PublicSessionIntent",
            "com.mirkori.inplacex.backend.session.contract.PublicCommandId",
            "com.mirkori.inplacex.backend.session.security.AuthenticatedSessionActor",
            "com.mirkori.inplacex.backend.session.security.AuthenticatedSessionCommand",
            "com.mirkori.inplacex.backend.session.security.ServerEstablishedActorFactory",
        ).forEach { forbiddenClass ->
            assertThrows(ClassNotFoundException::class.java) {
                Class.forName(forbiddenClass)
            }
        }

        val methodNames = PublicSessionCodec::class.java.methods.mapTo(mutableSetOf()) { it.name }
        assertFalse("encodeIntent" in methodNames)
        assertFalse("decodeIntent" in methodNames)
        assertFalse("decodeResult" in methodNames)
    }

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
        val participantA: PublicParticipantId =
            PublicParticipantId.parse("20000000-0000-4000-8000-000000000002")
        val participantB: PublicParticipantId =
            PublicParticipantId.parse("30000000-0000-4000-8000-000000000003")
    }
}
