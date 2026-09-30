package com.mirkori.inplacex.backend.session.contract

data class PublicDuelParticipant(
    val participantId: PublicParticipantId,
    val slot: PublicParticipantSlot,
    val participantType: PublicParticipantType,
    val secretSubmitted: Boolean,
    val connected: Boolean,
)

data class PublicDuelTurn(
    val turnNumber: Int,
    val actorParticipantId: PublicParticipantId,
    val exactMatches: Int,
    val solved: Boolean,
) {
    init {
        require(turnNumber > 0) { "Public turn number must be positive" }
        require(exactMatches >= 0) { "Public exact match count must not be negative" }
    }
}

data class PublicDuelSessionSnapshot(
    val sessionId: PublicSessionId,
    val revision: Long,
    val eventSequence: Long,
    val phase: PublicDuelPhase,
    val config: PublicGameConfig,
    val participants: List<PublicDuelParticipant>,
    val turns: List<PublicDuelTurn> = emptyList(),
    val currentActorParticipantId: PublicParticipantId? = null,
    val winnerParticipantId: PublicParticipantId? = null,
) {
    init {
        require(revision >= 0) { "Public session revision must not be negative" }
        require(eventSequence >= 0) { "Public event sequence must not be negative" }
        require(participants.size <= MAX_PUBLIC_DUEL_PARTICIPANTS) {
            "A public duel cannot contain more than two participants"
        }
        require(participants.map { it.participantId }.distinct().size == participants.size) {
            "Public participant identifiers must be unique"
        }
        require(participants.map { it.slot }.distinct().size == participants.size) {
            "Public participant slots must be unique"
        }
        require(turns.size <= MAX_PUBLIC_DUEL_TURNS) {
            "Public turn history exceeds the supported bound"
        }
        require(turns.withIndex().all { (index, turn) -> turn.turnNumber == index + 1 }) {
            "Public turns must be contiguous and start at one"
        }
        require(turns.all { it.exactMatches <= config.codeLength }) {
            "Public exact match count exceeds code length"
        }
        require(turns.all { it.solved == (it.exactMatches == config.codeLength) }) {
            "Public solved state does not match the exact-position score"
        }

        val participantIds = participants.mapTo(mutableSetOf()) { it.participantId }
        require(turns.all { it.actorParticipantId in participantIds }) {
            "Public turn actor must belong to the session"
        }
        require(currentActorParticipantId == null || currentActorParticipantId in participantIds) {
            "Current actor must belong to the session"
        }
        require(winnerParticipantId == null || winnerParticipantId in participantIds) {
            "Winner must belong to the session"
        }
        requirePhaseCoherence()
    }

    private fun requirePhaseCoherence() {
        when (phase) {
            PublicDuelPhase.SETUP_WAITING_FOR_PLAYERS,
            PublicDuelPhase.SETUP_SECRET_A,
            PublicDuelPhase.SETUP_SECRET_B,
            -> {
                require(currentActorParticipantId == null) {
                    "Setup snapshot must not expose a current actor"
                }
                require(winnerParticipantId == null) { "Setup snapshot must not expose a winner" }
            }

            PublicDuelPhase.ACTIVE_TURN_A,
            PublicDuelPhase.ACTIVE_TURN_B,
            -> {
                require(currentActorParticipantId != null) {
                    "Active snapshot must expose a current actor"
                }
                require(winnerParticipantId == null) { "Active snapshot must not expose a winner" }
                require(
                    participants.size == MAX_PUBLIC_DUEL_PARTICIPANTS &&
                        participants.all { it.secretSubmitted },
                ) {
                    "Active snapshot requires two participants with submitted secrets"
                }
            }

            PublicDuelPhase.FINISHED -> {
                require(currentActorParticipantId == null) {
                    "Finished snapshot must not expose a current actor"
                }
                require(winnerParticipantId != null) { "Finished snapshot must expose a winner" }
            }

            PublicDuelPhase.ABANDONED -> {
                require(currentActorParticipantId == null) {
                    "Abandoned snapshot must not expose a current actor"
                }
            }
        }
    }

    private companion object {
        const val MAX_PUBLIC_DUEL_PARTICIPANTS: Int = 2
        const val MAX_PUBLIC_DUEL_TURNS: Int = 2_048
    }
}
