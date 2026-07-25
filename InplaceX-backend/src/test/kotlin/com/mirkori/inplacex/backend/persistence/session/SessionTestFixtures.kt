package com.mirkori.inplacex.backend.persistence.session

internal fun initialSnapshot(sessionId: String = "session-1"): PublicDuelSessionSnapshot =
    PublicDuelSessionSnapshot(
        sessionId = sessionId,
        revision = 0,
        eventSequence = 0,
        phase = PublicDuelPhase.ACTIVE_TURN_A,
        config = PublicGameConfig(codeLength = 4, attemptLimit = 10),
        participants = listOf(
            PublicDuelParticipant(
                participantId = "player-a",
                slot = PublicParticipantSlot.A,
                participantType = PublicParticipantType.HUMAN,
                secretSubmitted = true,
                connected = true,
            ),
            PublicDuelParticipant(
                participantId = "player-b",
                slot = PublicParticipantSlot.B,
                participantType = PublicParticipantType.HUMAN,
                secretSubmitted = true,
                connected = true,
            ),
        ),
        currentActorParticipantId = "player-a",
    )

internal fun turnCommand(
    current: PublicDuelSessionSnapshot,
    commandId: String,
    guess: String,
    actorId: String = "player-a",
    exactMatches: Int = 1,
): DurableDuelSessionCommand {
    val nextTurn = PublicDuelTurn(
        turnNumber = current.turns.size + 1,
        actorParticipantId = actorId,
        exactMatches = exactMatches,
        solved = exactMatches == current.config.codeLength,
    )
    val nextSnapshot = current.copy(
        revision = current.revision + 1,
        eventSequence = current.eventSequence + 1,
        turns = current.turns + nextTurn,
        currentActorParticipantId = actorId,
    )
    return DurableDuelSessionCommand(
        sessionId = current.sessionId,
        actorId = actorId,
        clientCommandId = commandId,
        expectedRevision = current.revision,
        content = DurableDuelCommandContent.SubmitGuess.validated(guess),
        resultingSnapshot = nextSnapshot,
        event = PublicDuelSessionEvent.TurnResult(
            turnNumber = nextTurn.turnNumber,
            actorParticipantId = actorId,
            exactMatches = nextTurn.exactMatches,
            solved = nextTurn.solved,
        ),
        result = PublicDuelCommandResult.TurnAccepted(
            turnNumber = nextTurn.turnNumber,
            exactMatches = nextTurn.exactMatches,
            solved = nextTurn.solved,
        ),
    )
}
