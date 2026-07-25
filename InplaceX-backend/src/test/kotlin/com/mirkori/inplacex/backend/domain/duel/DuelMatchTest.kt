package com.mirkori.inplacex.backend.domain.duel

import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuelMatchTest {

    @Test
    fun setupRequiresOrderedSecretsAndStartsWithFirstParticipant() {
        val match = duel()

        assertEquals(DuelParticipant.FIRST, match.snapshot().awaitingSecretFrom)
        assertRejected(DuelCommandRejection.SECRET_NOT_EXPECTED) {
            match.setSecret(DuelParticipant.SECOND, "5678")
        }

        val waitingForSecond = match.setSecret(DuelParticipant.FIRST, "1234")
        assertEquals(DuelPhase.SETUP, waitingForSecond.phase)
        assertEquals(DuelParticipant.SECOND, waitingForSecond.awaitingSecretFrom)

        val active = match.setSecret(DuelParticipant.SECOND, "5678")
        assertEquals(DuelPhase.ACTIVE, active.phase)
        assertEquals(DuelParticipant.FIRST, active.currentTurn)
        assertNull(active.winner)
    }

    @Test
    fun publicSnapshotContainsOnlyReadinessAttemptsAndScoresNotSecrets() {
        val match = readyDuel()

        val snapshot = match.snapshot()

        assertTrue(snapshot.participants.all { it.secretConfigured })
        assertFalse(snapshot.toString().contains("1234"))
        assertFalse(snapshot.toString().contains("5678"))
    }

    @Test
    fun guessesScoreAgainstOpponentAndAlternateTurnsUntilWin() {
        val match = readyDuel()

        val firstTurn = match.submitGuess(DuelParticipant.FIRST, "5670")
        assertEquals(3, firstTurn.attempts.single().exactMatches)
        assertEquals(DuelParticipant.SECOND, firstTurn.currentTurn)

        val winningTurn = match.submitGuess(DuelParticipant.SECOND, "1234")
        assertEquals(DuelPhase.FINISHED, winningTurn.phase)
        assertEquals(DuelParticipant.SECOND, winningTurn.winner)
        assertNull(winningTurn.currentTurn)
    }

    @Test
    fun attemptLimitAwardsWinToOpponent() {
        val match = readyDuel(attemptLimit = 1)

        val finished = match.submitGuess(DuelParticipant.FIRST, "5670")

        assertEquals(DuelPhase.FINISHED, finished.phase)
        assertEquals(DuelParticipant.SECOND, finished.winner)
        assertEquals(0, finished.participants.first { it.participant == DuelParticipant.FIRST }.attemptsLeft)
    }

    @Test
    fun rejectedCommandsAreTypedAndLeaveAggregateUnchanged() {
        val match = duel()

        assertRejected(
            rejection = DuelCommandRejection.INVALID_SECRET,
            validationReason = GuessValidationReason.ALL_SAME_DIGITS,
        ) {
            match.setSecret(DuelParticipant.FIRST, "1111")
        }
        assertEquals(DuelParticipant.FIRST, match.snapshot().awaitingSecretFrom)

        assertRejected(DuelCommandRejection.MATCH_NOT_ACTIVE) {
            match.submitGuess(DuelParticipant.FIRST, "5678")
        }

        ready(match)
        assertRejected(DuelCommandRejection.NOT_CURRENT_TURN) {
            match.submitGuess(DuelParticipant.SECOND, "1234")
        }
        assertRejected(
            rejection = DuelCommandRejection.INVALID_GUESS,
            validationReason = GuessValidationReason.NON_DIGIT,
        ) {
            match.submitGuess(DuelParticipant.FIRST, "56a8")
        }
        assertTrue(match.snapshot().attempts.isEmpty())
    }

    private fun duel(attemptLimit: Int = 12): DuelMatch = DuelMatch.create(
        GameConfig(
            codeLength = 4,
            allowDuplicates = true,
            attemptLimit = attemptLimit,
            forbidAllSameDigitsGuess = true,
        ),
    )

    private fun readyDuel(attemptLimit: Int = 12): DuelMatch = duel(attemptLimit).also(::ready)

    private fun ready(match: DuelMatch) {
        match.setSecret(DuelParticipant.FIRST, "1234")
        match.setSecret(DuelParticipant.SECOND, "5678")
    }

    private fun assertRejected(
        rejection: DuelCommandRejection,
        validationReason: GuessValidationReason? = null,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: DuelCommandRejectedException) {
            assertEquals(rejection, error.rejection)
            assertEquals(validationReason, error.validationReason)
            return
        }
        throw AssertionError("Expected rejection $rejection")
    }
}
