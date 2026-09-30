package com.mirkori.inplacex.backend.session.security

import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
import com.mirkori.inplacex.backend.session.contract.PublicSessionId
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class SessionLogOperation(val attributeValue: String) {
    READ_SNAPSHOT("read_snapshot"),
    REPLAY_EVENT("replay_event"),
    ENCODE_RESULT("encode_result"),
    REJECT_UNSAFE_FRAME("reject_unsafe_frame"),
}

enum class SessionLogOutcome(val attributeValue: String) {
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    FAILED("failed"),
}

class SafeSessionLogAttributes internal constructor(
    private val attributes: Map<String, String>,
) {
    fun asMap(): Map<String, String> = attributes.toMap()

    override fun toString(): String = "SafeSessionLogAttributes(keys=${attributes.keys.sorted()})"
}

class SafeSessionLogAttributeFactory(referenceKey: ByteArray) {
    private val hmacKey = HmacSha256Key(referenceKey)

    fun sessionRead(
        operation: SessionLogOperation,
        outcome: SessionLogOutcome,
        sessionId: PublicSessionId,
        participantId: PublicParticipantId? = null,
    ): SafeSessionLogAttributes {
        val sessionBytes = sessionId.value.toByteArray(StandardCharsets.UTF_8)
        val participantBytes = participantId?.value?.toByteArray(StandardCharsets.UTF_8)
        return try {
            SafeSessionLogAttributes(
                buildMap {
                    put("operation", operation.attributeValue)
                    put("outcome", outcome.attributeValue)
                    put(
                        "sessionRef",
                        pseudonym(SESSION_REFERENCE_DOMAIN, "s_", sessionBytes),
                    )
                    if (participantBytes != null) {
                        put(
                            "participantRef",
                            pseudonym(
                                PARTICIPANT_REFERENCE_DOMAIN,
                                "p_",
                                sessionBytes,
                                participantBytes,
                            ),
                        )
                    }
                },
            )
        } finally {
            sessionBytes.fill(0)
            participantBytes?.fill(0)
        }
    }

    override fun toString(): String = "SafeSessionLogAttributeFactory(referenceKey=[redacted])"

    private fun pseudonym(domain: String, prefix: String, vararg values: ByteArray): String {
        val digest = hmacKey.digest(domain, *values)
        return try {
            prefix + BASE64_ENCODER.encodeToString(digest).take(PSEUDONYM_LENGTH)
        } finally {
            digest.fill(0)
        }
    }

    private companion object {
        const val SESSION_REFERENCE_DOMAIN: String = "inplacex.session.log.session.v1"
        const val PARTICIPANT_REFERENCE_DOMAIN: String = "inplacex.session.log.participant.v1"
        const val PSEUDONYM_LENGTH: Int = 16
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
