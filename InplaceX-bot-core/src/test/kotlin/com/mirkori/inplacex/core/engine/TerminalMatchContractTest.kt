package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalMatchContractTest {

    @Test
    fun winningMatchRemainsWonUntilExplicitRestart() {
        val engine = GameEngine(GameConfig(codeLength = 4, attemptLimit = 2))

        engine.start(secretOverride = "1234")
        val won = engine.submit("1234")

        assertEquals(MatchPhase.WON, won.phase)
        assertEquals(listOf(MatchAttempt("1234", 4, 1, true)), won.attempts)
        assertEquals(1, won.attemptsLeft)
        assertEquals("1234", won.debugSecret)

        val repeatedSnapshot = engine.snapshot()
        assertEquals(MatchPhase.WON, repeatedSnapshot.phase)
        assertEquals(listOf(MatchAttempt("1234", 4, 1, true)), repeatedSnapshot.attempts)
        assertEquals(1, repeatedSnapshot.attemptsLeft)
        assertEquals("1234", repeatedSnapshot.debugSecret)

        val afterSubmit = engine.submit("5678")
        assertEquals(MatchPhase.WON, afterSubmit.phase)
        assertEquals(listOf(MatchAttempt("1234", 4, 1, true)), afterSubmit.attempts)
        assertEquals(1, afterSubmit.attemptsLeft)
        assertEquals("1234", afterSubmit.debugSecret)

        val restarted = engine.start(secretOverride = "5678")
        assertEquals(MatchPhase.ACTIVE, restarted.phase)
        assertEquals(emptyList<MatchAttempt>(), restarted.attempts)
        assertEquals(2, restarted.attemptsLeft)
        assertEquals("5678", restarted.debugSecret)
    }

    @Test
    fun losingMatchRemainsLostUntilExplicitRestart() {
        val engine = GameEngine(GameConfig(codeLength = 4, attemptLimit = 2))

        engine.start(secretOverride = "1234")
        val first = engine.submit("5678")
        assertEquals(MatchPhase.ACTIVE, first.phase)
        assertEquals(listOf(MatchAttempt("5678", 0, 1, false)), first.attempts)
        assertEquals(1, first.attemptsLeft)
        assertEquals("1234", first.debugSecret)

        val lost = engine.submit("9012")
        val terminalAttempts = listOf(
            MatchAttempt("5678", 0, 1, false),
            MatchAttempt("9012", 0, 2, false),
        )
        assertEquals(MatchPhase.LOST, lost.phase)
        assertEquals(terminalAttempts, lost.attempts)
        assertEquals(0, lost.attemptsLeft)
        assertEquals("1234", lost.debugSecret)

        val repeatedSnapshot = engine.snapshot()
        assertEquals(MatchPhase.LOST, repeatedSnapshot.phase)
        assertEquals(terminalAttempts, repeatedSnapshot.attempts)
        assertEquals(0, repeatedSnapshot.attemptsLeft)
        assertEquals("1234", repeatedSnapshot.debugSecret)

        val afterCorrectSubmit = engine.submit("1234")
        assertEquals(MatchPhase.LOST, afterCorrectSubmit.phase)
        assertEquals(terminalAttempts, afterCorrectSubmit.attempts)
        assertEquals(0, afterCorrectSubmit.attemptsLeft)
        assertEquals("1234", afterCorrectSubmit.debugSecret)

        val restarted = engine.start(secretOverride = "5678")
        assertEquals(MatchPhase.ACTIVE, restarted.phase)
        assertEquals(emptyList<MatchAttempt>(), restarted.attempts)
        assertEquals(2, restarted.attemptsLeft)
        assertEquals("5678", restarted.debugSecret)
    }

    @Test
    fun wonMatchRejectsExtraMovesAndLateFailure() {
        val engine = GameEngine(GameConfig(codeLength = 4, attemptLimit = 2))
        engine.start(secretOverride = "1234")
        engine.submit("1234")
        val checkpoint = engine.checkpoint()

        val afterGrant = engine.grantExtraMoves(3)
        assertEquals(checkpoint, engine.checkpoint())
        assertEquals(MatchPhase.WON, afterGrant.phase)
        assertEquals(listOf(MatchAttempt("1234", 4, 1, true)), afterGrant.attempts)
        assertEquals(1, afterGrant.attemptsLeft)
        assertEquals("1234", afterGrant.debugSecret)

        val afterFailure = engine.fail("late failure")
        assertEquals(checkpoint, engine.checkpoint())
        assertEquals(MatchPhase.WON, afterFailure.phase)
        assertEquals(listOf(MatchAttempt("1234", 4, 1, true)), afterFailure.attempts)
        assertEquals(1, afterFailure.attemptsLeft)
        assertEquals("1234", afterFailure.debugSecret)
    }

    @Test
    fun lostMatchRejectsExtraMovesAndLateFailure() {
        val engine = GameEngine(GameConfig(codeLength = 4, attemptLimit = 2))
        engine.start(secretOverride = "1234")
        engine.submit("5678")
        engine.submit("9012")
        val checkpoint = engine.checkpoint()
        val terminalAttempts = listOf(
            MatchAttempt("5678", 0, 1, false),
            MatchAttempt("9012", 0, 2, false),
        )

        val afterGrant = engine.grantExtraMoves(3)
        assertEquals(checkpoint, engine.checkpoint())
        assertEquals(MatchPhase.LOST, afterGrant.phase)
        assertEquals(terminalAttempts, afterGrant.attempts)
        assertEquals(0, afterGrant.attemptsLeft)
        assertEquals("1234", afterGrant.debugSecret)

        val afterFailure = engine.fail("late failure")
        assertEquals(checkpoint, engine.checkpoint())
        assertEquals(MatchPhase.LOST, afterFailure.phase)
        assertEquals(terminalAttempts, afterFailure.attempts)
        assertEquals(0, afterFailure.attemptsLeft)
        assertEquals("1234", afterFailure.debugSecret)
    }
    @Test
    fun submittingBeforeStartDoesNotStartOrConsumeAttempts() {
        val engine = GameEngine(GameConfig(codeLength = 4, attemptLimit = 3))
        val initial = engine.snapshot()
        val checkpoint = engine.checkpoint()

        assertEquals(MatchPhase.NOT_STARTED, initial.phase)
        assertEquals(emptyList<MatchAttempt>(), initial.attempts)
        assertEquals(3, initial.attemptsLeft)
        assertEquals("", initial.debugSecret)

        val beforeStartSubmit = engine.submit("1234")
        assertEquals(MatchPhase.NOT_STARTED, beforeStartSubmit.phase)
        assertEquals(emptyList<MatchAttempt>(), beforeStartSubmit.attempts)
        assertEquals(3, beforeStartSubmit.attemptsLeft)
        assertEquals("", beforeStartSubmit.debugSecret)
        assertEquals(checkpoint, engine.checkpoint())

        val started = engine.start(secretOverride = "1234")
        assertEquals(MatchPhase.ACTIVE, started.phase)
        assertEquals(emptyList<MatchAttempt>(), started.attempts)
        assertEquals(3, started.attemptsLeft)
        assertEquals("1234", started.debugSecret)

        val won = engine.submit("1234")
        assertEquals(MatchPhase.WON, won.phase)
        assertEquals(listOf(MatchAttempt("1234", 4, 1, true)), won.attempts)
        assertEquals(2, won.attemptsLeft)
        assertEquals("1234", won.debugSecret)
    }
}
