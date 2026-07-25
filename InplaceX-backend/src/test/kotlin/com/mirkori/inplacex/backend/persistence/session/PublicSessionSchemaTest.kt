package com.mirkori.inplacex.backend.persistence.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicSessionSchemaTest {
    @Test
    fun snapshotAndEventRoundTripThroughCanonicalClosedSchema() {
        val snapshot = initialSnapshot()
        val snapshotJson = PublicSessionJson.encodeSnapshot(snapshot)
        val event = PublicDuelSessionEvent.SecretStatusChanged("player-a", secretSubmitted = true)
        val eventFrame = PublicSessionJson.encodeEventFrame(event)

        assertEquals(snapshot, PublicSessionJson.decodeSnapshot(snapshotJson))
        assertEquals(event, PublicSessionJson.decodeEventFrame(eventFrame))
        assertEquals(snapshotJson, PublicSessionJson.encodeSnapshot(PublicSessionJson.decodeSnapshot(snapshotJson)))
        assertFalse(snapshotJson.contains("\"guess\""))
        assertFalse(snapshotJson.contains("\"token\""))
    }

    @Test
    fun rejectsInvalidUnknownAndForbiddenPublicFrames() {
        val invalidFrames = listOf(
            """{"type":"duel.finished","payload":""",
            """{"type":"duel.unknown","payload":{}}""",
            """{"type":"duel.finished","payload":{"unknown":"value"}}""",
            """{"type":"duel.finished","payload":{"secret":"1234"}}""",
            """{"type":"duel.finished","payload":{"opponent_secret":"1234"}}""",
            """{"type":"duel.finished","payload":{"secretHash":"value"}}""",
            """{"type":"duel.finished","payload":{"secretCiphertext":"value"}}""",
            """{"type":"duel.finished","payload":{"seed":"value"}}""",
            """{"type":"duel.finished","payload":{"keyMaterial":"value"}}""",
            """{"type":"duel.finished","payload":{"accessToken":"value"}}""",
            """{"type":"duel.finished","payload":{"refreshToken":"value"}}""",
            """{"type":"duel.finished","payload":{"idToken":"value"}}""",
            """{"type":"duel.finished","payload":{"purchaseToken":"value"}}""",
            """{"type":"duel.finished","payload":{"integrityToken":"value"}}""",
            """{"type":"duel.finished","payload":{"providerPayload":"value"}}""",
            """{"type":"duel.finished","payload":{"guess":"1234"}}""",
            """{"type":"duel.finished","payload":{"rawRequestBody":"value"}}""",
            """{"type":"duel.finished","payload":{"meta":{"token":"value"}}}""",
            """{"type":"duel.finished","payload":{"winnerParticipantId":"1234"}}""",
            """{"type":"duel.finished","payload":{"winnerParticipantId":"accessTokenValue"}}""",
            """{"type":"duel.finished","payload":{"winnerParticipantId":"eyJheader.payload.signature"}}""",
            """{"type":"duel.secretStatusChanged","payload":{"participantId":"player-a","secretSubmitted":"true"}}""",
            """{"type":"duel.turnResult","payload":{"turnNumber":"1","actorParticipantId":"player-a","exactMatches":1,"solved":false}}""",
            "{\"type\":\"duel.finished\",\"payload\":{\"\\u0073ecret\":\"1234\"}}",
        )

        invalidFrames.forEach { frame ->
            assertThrows(IllegalArgumentException::class.java) {
                PublicSessionJson.decodeEventFrame(frame)
            }
        }
    }

    @Test
    fun enforcesExact64KiBBoundaryForSnapshotAndEventInputs() {
        val snapshotJson = PublicSessionJson.encodeSnapshot(initialSnapshot())
        val eventJson = PublicSessionJson.encodeEventFrame(
            PublicDuelSessionEvent.SecretStatusChanged("player-a", true),
        )

        val snapshotAtLimit = padToFrameLimit(snapshotJson)
        val eventAtLimit = padToFrameLimit(eventJson)
        assertEquals(initialSnapshot(), PublicSessionJson.decodeSnapshot(snapshotAtLimit))
        assertEquals(
            PublicDuelSessionEvent.SecretStatusChanged("player-a", true),
            PublicSessionJson.decodeEventFrame(eventAtLimit),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PublicSessionJson.decodeSnapshot("$snapshotAtLimit ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicSessionJson.decodeEventFrame("$eventAtLimit ")
        }
    }

    @Test
    fun secretSubmittedStatusIsAllowedButSecretValueNeverIs() {
        val allowed = PublicSessionJson.encodeEventFrame(
            PublicDuelSessionEvent.SecretStatusChanged("player-a", true),
        )

        assertEquals(
            PublicDuelSessionEvent.SecretStatusChanged("player-a", true),
            PublicSessionJson.decodeEventFrame(allowed),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PublicSessionJson.decodeSnapshot(
                PublicSessionJson.encodeSnapshot(initialSnapshot()).replaceFirst(
                    "\"turns\":[]",
                    "\"turns\":[],\"secretValue\":\"1234\"",
                ),
            )
        }
    }

    private fun padToFrameLimit(json: String): String {
        val byteCount = json.toByteArray(Charsets.UTF_8).size
        return json + " ".repeat(MAX_PUBLIC_SESSION_FRAME_BYTES - byteCount)
    }
}
