package com.mirkori.inplacex.backend.session.contract

sealed interface PublicDuelSessionResult {
    val type: String

    data class SecretAccepted(
        val participantId: PublicParticipantId,
        val secretSubmitted: Boolean = true,
    ) : PublicDuelSessionResult {
        override val type: String = TYPE

        init {
            require(secretSubmitted) { "Public secret receipt may only record submitted=true" }
        }

        companion object {
            const val TYPE: String = "duel.secretAccepted"
        }
    }

    data class TurnAccepted(
        val turnNumber: Int,
        val exactMatches: Int,
        val solved: Boolean,
    ) : PublicDuelSessionResult {
        override val type: String = TYPE

        init {
            require(turnNumber > 0) { "Accepted turn number must be positive" }
            require(exactMatches >= 0) { "Accepted exact match count must not be negative" }
        }

        companion object {
            const val TYPE: String = "duel.turnAccepted"
        }
    }

    data class PresenceAccepted(
        val participantId: PublicParticipantId,
        val connected: Boolean,
    ) : PublicDuelSessionResult {
        override val type: String = TYPE

        companion object {
            const val TYPE: String = "session.presenceAccepted"
        }
    }

    data class PhaseAccepted(
        val phase: PublicDuelPhase,
    ) : PublicDuelSessionResult {
        override val type: String = TYPE

        companion object {
            const val TYPE: String = "duel.phaseAccepted"
        }
    }
}
