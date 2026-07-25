package com.mirkori.inplacex.backend.session.codec

import com.mirkori.inplacex.backend.session.contract.PublicDuelPhase
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionEvent
import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PublicEventJson {
    fun encode(event: PublicDuelSessionEvent): JsonObject = typedFrame(event.type, payload(event))

    fun decode(element: JsonElement): PublicDuelSessionEvent {
        val (type, payload) = decodeTypedFrame(element)
        return when (type) {
            PublicDuelSessionEvent.ParticipantPresenceChanged.TYPE -> {
                payload.requireExactFields(setOf("participantId", "connected"))
                PublicDuelSessionEvent.ParticipantPresenceChanged(
                    participantId = PublicParticipantId.parse(payload.requiredString("participantId")),
                    connected = payload.requiredBoolean("connected"),
                )
            }

            PublicDuelSessionEvent.SecretStatusChanged.TYPE -> {
                payload.requireExactFields(setOf("participantId", "secretSubmitted"))
                PublicDuelSessionEvent.SecretStatusChanged(
                    participantId = PublicParticipantId.parse(payload.requiredString("participantId")),
                    secretSubmitted = payload.requiredBoolean("secretSubmitted"),
                )
            }

            PublicDuelSessionEvent.TurnResult.TYPE -> {
                payload.requireExactFields(
                    setOf("turnNumber", "actorParticipantId", "exactMatches", "solved"),
                )
                PublicDuelSessionEvent.TurnResult(
                    turnNumber = payload.requiredInt("turnNumber"),
                    actorParticipantId = PublicParticipantId.parse(
                        payload.requiredString("actorParticipantId"),
                    ),
                    exactMatches = payload.requiredInt("exactMatches"),
                    solved = payload.requiredBoolean("solved"),
                )
            }

            PublicDuelSessionEvent.PhaseChanged.TYPE -> {
                payload.requireExactFields(
                    required = setOf("phase"),
                    optional = setOf("currentActorParticipantId"),
                )
                PublicDuelSessionEvent.PhaseChanged(
                    phase = enumValue<PublicDuelPhase>(payload.requiredString("phase")),
                    currentActorParticipantId = payload.optionalString("currentActorParticipantId")
                        ?.let(PublicParticipantId::parse),
                )
            }

            PublicDuelSessionEvent.Finished.TYPE -> {
                payload.requireExactFields(
                    required = emptySet(),
                    optional = setOf("winnerParticipantId"),
                )
                PublicDuelSessionEvent.Finished(
                    winnerParticipantId = payload.optionalString("winnerParticipantId")
                        ?.let(PublicParticipantId::parse),
                )
            }

            else -> throw IllegalArgumentException("Unknown public session event type")
        }
    }

    private fun payload(event: PublicDuelSessionEvent): JsonObject = when (event) {
        is PublicDuelSessionEvent.ParticipantPresenceChanged -> publicJsonObject(
            "participantId" to JsonPrimitive(event.participantId.value),
            "connected" to JsonPrimitive(event.connected),
        )

        is PublicDuelSessionEvent.SecretStatusChanged -> publicJsonObject(
            "participantId" to JsonPrimitive(event.participantId.value),
            "secretSubmitted" to JsonPrimitive(event.secretSubmitted),
        )

        is PublicDuelSessionEvent.TurnResult -> publicJsonObject(
            "turnNumber" to JsonPrimitive(event.turnNumber),
            "actorParticipantId" to JsonPrimitive(event.actorParticipantId.value),
            "exactMatches" to JsonPrimitive(event.exactMatches),
            "solved" to JsonPrimitive(event.solved),
        )

        is PublicDuelSessionEvent.PhaseChanged -> publicJsonObject(
            "phase" to JsonPrimitive(event.phase.name),
            "currentActorParticipantId" to event.currentActorParticipantId?.value
                ?.let(::JsonPrimitive),
        )

        is PublicDuelSessionEvent.Finished -> publicJsonObject(
            "winnerParticipantId" to event.winnerParticipantId?.value?.let(::JsonPrimitive),
        )
    }
}
