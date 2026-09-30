package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.match.MatchCheckpoint
import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {
    private val config = GameConfig(
        codeLength = 4,
        allowDuplicates = true,
        attemptLimit = 3,
    )

    @Test
    fun `snapshot exposes typed validation feedback`() {
        val engine = GameEngine(config)
        engine.start(secretOverride = "1234")

        val snapshot = engine.submit("1111")

        assertEquals(
            MatchFeedback.ValidationRejected(GuessValidationReason.ALL_SAME_DIGITS),
            snapshot.feedback,
        )
        assertEquals("All digits cannot be the same", snapshot.message)
        assertTrue(snapshot.attempts.isEmpty())
    }

    @Test
    fun `invalid fixed secret is replaced before the first guess`() {
        val engine = GameEngine(config.copy(seed = 17L))

        val started = engine.start(secretOverride = "")
        val submitted = engine.submit("1234")

        assertEquals(config.codeLength, started.debugSecret.length)
        assertTrue(started.debugSecret.all(Char::isDigit))
        assertEquals(1, submitted.attempts.size)
    }

    @Test
    fun `fixed secret must satisfy the same maximum run rule as a guess`() {
        val strictConfig = config.copy(
            maxConsecutiveDuplicateDigits = 3,
            seed = 17L,
        )
        val engine = GameEngine(strictConfig)

        val started = engine.start(secretOverride = "1111")

        assertFalse(started.debugSecret == "1111")
        assertTrue(GuessValidator.validate(started.debugSecret, strictConfig))
    }

    @Test
    fun `extra moves feedback uses repaired text and typed amount`() {
        val engine = GameEngine(config)
        engine.start(secretOverride = "1234")

        val snapshot = engine.grantExtraMoves(2)

        assertEquals(MatchFeedback.ExtraMovesGranted(amount = 2), snapshot.feedback)
        assertEquals("Добавлено ходов: 2", snapshot.message)
        assertFalse(snapshot.message.orEmpty().contains("Р"))
    }

    @Test
    fun `valid checkpoint restores secret phase attempts and extra moves`() {
        val source = GameEngine(config)
        source.start(secretOverride = "1234")
        source.submit("1235")
        source.grantExtraMoves(2)
        val checkpoint = source.checkpoint()

        val restored = GameEngine(config.copy(seed = 99L))

        assertTrue(restored.restoreCheckpoint(checkpoint))
        assertEquals(source.snapshot(), restored.snapshot())
        assertEquals("1234", restored.snapshot().debugSecret)
        assertEquals(MatchPhase.ACTIVE, restored.snapshot().phase)
        assertEquals(4, restored.snapshot().attemptsLeft)
    }

    @Test
    fun `invalid checkpoint fails closed and keeps current state`() {
        val engine = GameEngine(config)
        engine.start(secretOverride = "1234")
        engine.submit("1235")
        val before = engine.snapshot()

        val invalid = MatchCheckpoint(
            secret = "12x4",
            phase = MatchPhase.ACTIVE,
            attempts = before.attempts,
            extraAttemptBudget = 0,
        )

        assertFalse(engine.restoreCheckpoint(invalid))
        assertEquals(before, engine.snapshot())
    }

    @Test
    fun `checkpoint secret must satisfy the exact active rules`() {
        val strictConfig = config.copy(maxConsecutiveDuplicateDigits = 3)
        val engine = GameEngine(strictConfig)
        engine.start(secretOverride = "1234")
        val before = engine.snapshot()

        val invalid = MatchCheckpoint(
            secret = "1111",
            phase = MatchPhase.ACTIVE,
            attempts = emptyList(),
            extraAttemptBudget = 0,
        )

        assertFalse(engine.restoreCheckpoint(invalid))
        assertEquals(before, engine.snapshot())
    }

    @Test
    fun `won checkpoint rejects attempts after an earlier win`() {
        val engine = GameEngine(config)
        engine.start(secretOverride = "1234")
        val before = engine.snapshot()
        val invalid = MatchCheckpoint(
            secret = "1234",
            phase = MatchPhase.WON,
            attempts = listOf(
                MatchAttempt(guess = "1234", score = 4, number = 1, isWin = true),
                MatchAttempt(guess = "1235", score = 3, number = 2, isWin = false),
            ),
            extraAttemptBudget = 0,
        )

        assertFalse(engine.restoreCheckpoint(invalid))
        assertEquals(before, engine.snapshot())
    }

    @Test
    fun `not started checkpoint is valid without a secret`() {
        val source = GameEngine(config)
        val restored = GameEngine(config.copy(seed = 7L))

        assertTrue(restored.restoreCheckpoint(source.checkpoint()))
        assertEquals(MatchPhase.NOT_STARTED, restored.snapshot().phase)
        assertEquals("", restored.snapshot().debugSecret)
    }

    @Test
    fun `failed match exposes terminal typed feedback`() {
        val engine = GameEngine(config)
        engine.start(secretOverride = "1234")

        val snapshot = engine.fail("Timed out")

        assertEquals(MatchFeedback.MatchFinished(MatchPhase.LOST), snapshot.feedback)
    }
}
