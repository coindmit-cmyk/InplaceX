package com.mirkori.inplacex.backend.session.codec

import com.mirkori.inplacex.backend.session.contract.PUBLIC_SESSION_SCHEMA_VERSION
import com.mirkori.inplacex.backend.session.contract.PublicDuelParticipant
import com.mirkori.inplacex.backend.session.contract.PublicDuelPhase
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionSnapshot
import com.mirkori.inplacex.backend.session.contract.PublicDuelTurn
import com.mirkori.inplacex.backend.session.contract.PublicGameConfig
import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
import com.mirkori.inplacex.backend.session.contract.PublicParticipantSlot
import com.mirkori.inplacex.backend.session.contract.PublicParticipantType
import com.mirkori.inplacex.backend.session.contract.PublicSessionId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PublicSnapshotJson {
    fun encode(snapshot: PublicDuelSessionSnapshot): JsonObject = publicJsonObject(
        "schemaVersion" to JsonPrimitive(PUBLIC_SESSION_SCHEMA_VERSION),
        "sessionId" to JsonPrimitive(snapshot.sessionId.value),
        "revision" to JsonPrimitive(snapshot.revision),
        "eventSeq" to JsonPrimitive(snapshot.eventSequence),
        "phase" to JsonPrimitive(snapshot.phase.name),
        "config" to encodeConfig(snapshot.config),
        "participants" to JsonArray(snapshot.participants.map(::encodeParticipant)),
        "turns" to JsonArray(snapshot.turns.map(::encodeTurn)),
        "currentActorParticipantId" to snapshot.currentActorParticipantId?.value?.let(::JsonPrimitive),
        "winnerParticipantId" to snapshot.winnerParticipantId?.value?.let(::JsonPrimitive),
    )

    fun decode(element: JsonElement): PublicDuelSessionSnapshot {
        val value = element.requireObject()
        value.requireExactFields(
            required = setOf(
                "schemaVersion",
                "sessionId",
                "revision",
                "eventSeq",
                "phase",
                "config",
                "participants",
                "turns",
            ),
            optional = setOf("currentActorParticipantId", "winnerParticipantId"),
        )
        require(value.requiredString("schemaVersion") == PUBLIC_SESSION_SCHEMA_VERSION) {
            "Unsupported public session schema version"
        }
        return PublicDuelSessionSnapshot(
            sessionId = PublicSessionId.parse(value.requiredString("sessionId")),
            revision = value.requiredLong("revision"),
            eventSequence = value.requiredLong("eventSeq"),
            phase = enumValue<PublicDuelPhase>(value.requiredString("phase")),
            config = decodeConfig(value.getValue("config")),
            participants = value.getValue("participants").requireArray().map(::decodeParticipant),
            turns = value.getValue("turns").requireArray().map(::decodeTurn),
            currentActorParticipantId = value.optionalString("currentActorParticipantId")
                ?.let(PublicParticipantId::parse),
            winnerParticipantId = value.optionalString("winnerParticipantId")
                ?.let(PublicParticipantId::parse),
        )
    }

    private fun encodeConfig(config: PublicGameConfig): JsonObject = publicJsonObject(
        "attemptLimit" to JsonPrimitive(config.attemptLimit),
        "codeLength" to JsonPrimitive(config.codeLength),
    )

    private fun decodeConfig(element: JsonElement): PublicGameConfig {
        val value = element.requireObject()
        value.requireExactFields(setOf("attemptLimit", "codeLength"))
        return PublicGameConfig(
            codeLength = value.requiredInt("codeLength"),
            attemptLimit = value.requiredInt("attemptLimit"),
        )
    }

    private fun encodeParticipant(participant: PublicDuelParticipant): JsonObject = publicJsonObject(
        "connected" to JsonPrimitive(participant.connected),
        "participantId" to JsonPrimitive(participant.participantId.value),
        "participantType" to JsonPrimitive(participant.participantType.name),
        "secretSubmitted" to JsonPrimitive(participant.secretSubmitted),
        "slot" to JsonPrimitive(participant.slot.name),
    )

    private fun decodeParticipant(element: JsonElement): PublicDuelParticipant {
        val value = element.requireObject()
        value.requireExactFields(
            setOf("connected", "participantId", "participantType", "secretSubmitted", "slot"),
        )
        return PublicDuelParticipant(
            participantId = PublicParticipantId.parse(value.requiredString("participantId")),
            slot = enumValue<PublicParticipantSlot>(value.requiredString("slot")),
            participantType = enumValue<PublicParticipantType>(
                value.requiredString("participantType"),
            ),
            secretSubmitted = value.requiredBoolean("secretSubmitted"),
            connected = value.requiredBoolean("connected"),
        )
    }

    private fun encodeTurn(turn: PublicDuelTurn): JsonObject = publicJsonObject(
        "actorParticipantId" to JsonPrimitive(turn.actorParticipantId.value),
        "exactMatches" to JsonPrimitive(turn.exactMatches),
        "solved" to JsonPrimitive(turn.solved),
        "turnNumber" to JsonPrimitive(turn.turnNumber),
    )

    private fun decodeTurn(element: JsonElement): PublicDuelTurn {
        val value = element.requireObject()
        value.requireExactFields(setOf("actorParticipantId", "exactMatches", "solved", "turnNumber"))
        return PublicDuelTurn(
            turnNumber = value.requiredInt("turnNumber"),
            actorParticipantId = PublicParticipantId.parse(
                value.requiredString("actorParticipantId"),
            ),
            exactMatches = value.requiredInt("exactMatches"),
            solved = value.requiredBoolean("solved"),
        )
    }
}
