package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.match.MatchHintResult
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthoritativeHintContractTest {

    @Test
    fun positionHintsReportTheFixedSecretFacts() {
        val engine = GameEngine(GameConfig(codeLength = 4, allowDuplicates = true))
        engine.start(secretOverride = "1234")

        assertEquals(MatchHintResult.PositionOpened(position = 0, digit = 1), engine.openPosition(0).result)
        assertEquals(MatchHintResult.PositionOpened(position = 1, digit = 2), engine.openPosition(1).result)
        assertEquals(MatchHintResult.PositionOpened(position = 2, digit = 3), engine.openPosition(2).result)
        assertEquals(MatchHintResult.PositionOpened(position = 3, digit = 4), engine.openPosition(3).result)
        assertEquals(
            MatchHintResult.PositionChecked(digit = 3, position = 2, isMatch = true),
            engine.checkPosition(digit = 3, position = 2).result,
        )
        assertEquals(
            MatchHintResult.PositionChecked(digit = 9, position = 2, isMatch = false),
            engine.checkPosition(digit = 9, position = 2).result,
        )
    }

    @Test
    fun digitCountHintsReportTheFixedDuplicateSecretFacts() {
        val engine = GameEngine(GameConfig(codeLength = 4, allowDuplicates = true))
        engine.start(secretOverride = "1123")

        assertEquals(MatchHintResult.DigitCountChecked(digit = 1, count = 2), engine.checkDigitCount(1).result)
        assertEquals(MatchHintResult.DigitCountChecked(digit = 2, count = 1), engine.checkDigitCount(2).result)
        assertEquals(MatchHintResult.DigitCountChecked(digit = 3, count = 1), engine.checkDigitCount(3).result)
        assertEquals(MatchHintResult.DigitCountChecked(digit = 0, count = 0), engine.checkDigitCount(0).result)
        assertEquals(MatchHintResult.DigitCountChecked(digit = 9, count = 0), engine.checkDigitCount(9).result)
    }
}
