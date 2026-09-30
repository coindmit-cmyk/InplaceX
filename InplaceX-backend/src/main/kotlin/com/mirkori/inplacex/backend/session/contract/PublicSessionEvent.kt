package com.mirkori.inplacex.backend.session.contract

sealed interface PublicDuelSessionEvent {
    val type: String

    data class ParticipantPresenceChanged(
        val participantId: PublicParticipantId,
        val connected: Boolean,
    ) : PublicDuelSessionEvent {
        override val type: String = TYPE

        companion object {
            const val TYPE: String = "session.participantPresenceChanged"
        }
    }

    data class SecretStatusChanged(
        val participantId: PublicParticipantId,
        val secretSubmitted: Boolean,
    ) : PublicDuelSessionEvent {
        override val type: String = TYPE

        init {
            require(secretSubmitted) { "Public secret status may only record submitted=true" }
        }

        companion object {
            const val TYPE: String = "duel.secretStatusChanged"
        }
    }

    data class TurnResult(
        val turnNumber: Int,
        val actorParticipantId: PublicParticipantId,
        val exactMatches: Int,
        val solved: Boolean,
    ) : PublicDuelSessionEvent {
        override val type: String = TYPE

        init {
            require(turnNumber > 0) { "Public turn number must be positive" }
            require(exactMatches >= 0) { "Public exact match count must not be negative" }
        }

        companion object {
            const val TYPE: String = "duel.turnResult"
        }
    }

    data class PhaseChanged(
        val phase: PublicDuelPhase,
        val currentActorParticipantId: PublicParticipantId? = null,
    ) : PublicDuelSessionEvent {
        override val type: String = TYPE

        companion object {
            const val TYPE: String = "duel.phaseChanged"
        }
    }

    data class Finished(
        val winnerParticipantId: PublicParticipantId? = null,
    ) : PublicDuelSessionEvent {
        override val type: String = TYPE

        companion object {
            const val TYPE: String = "duel.finished"
        }
    }
}
