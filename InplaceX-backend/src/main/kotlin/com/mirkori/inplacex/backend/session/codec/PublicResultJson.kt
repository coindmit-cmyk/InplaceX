package com.mirkori.inplacex.backend.session.codec

import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PublicResultJson {
    fun encode(result: PublicDuelSessionResult): JsonObject = typedFrame(
        result.type,
        when (result) {
            is PublicDuelSessionResult.SecretAccepted -> publicJsonObject(
                "participantId" to JsonPrimitive(result.participantId.value),
                "secretSubmitted" to JsonPrimitive(result.secretSubmitted),
            )

            is PublicDuelSessionResult.TurnAccepted -> publicJsonObject(
                "turnNumber" to JsonPrimitive(result.turnNumber),
                "exactMatches" to JsonPrimitive(result.exactMatches),
                "solved" to JsonPrimitive(result.solved),
            )

            is PublicDuelSessionResult.PresenceAccepted -> publicJsonObject(
                "participantId" to JsonPrimitive(result.participantId.value),
                "connected" to JsonPrimitive(result.connected),
            )

            is PublicDuelSessionResult.PhaseAccepted -> publicJsonObject(
                "phase" to JsonPrimitive(result.phase.name),
            )
        },
    )
}
