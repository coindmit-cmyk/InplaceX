package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceDifficultyCalibrationTest {
    @Test
    fun allRaceDifficultiesSolveTheSameSeededSixDigitSample() {
        BotDifficulty.entries.forEach { difficulty ->
            val moves = (1L..96L).map { seed ->
                val config = GameConfig(codeLength = 6, allowDuplicates = true, attemptLimit = 999, seed = seed * 41L)
                val run = BotSolver.solveSecret(
                    secret = SecretGenerator.generate(config),
                    config = config,
                    difficulty = difficulty,
                    seed = seed * 43L,
                    maxMoves = 999,
                )
                assertTrue("Unsolved race: difficulty=$difficulty seed=$seed", run.won)
                run.moves
            }.sorted()
            println("Race calibration $difficulty n=${moves.size} min=${moves.first()} p25=${moves[24]} median=${moves[48]} p75=${moves[72]} max=${moves.last()} mean=${moves.average()}")
        }
    }
}
