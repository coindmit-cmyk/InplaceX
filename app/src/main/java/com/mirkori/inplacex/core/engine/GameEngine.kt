package com.mirkori.inplacex.core.engine

import kotlin.random.Random

data class GameConfig(
    val codeLength: Int = 6,
    val allowDuplicates: Boolean = true,
    val attemptLimit: Int = 12,
    val forbidAllSameDigitsGuess: Boolean = true,
    val seed: Long? = null,
) {
    init {
        require(codeLength in 4..20) { "codeLength must be in 4..20" }
        require(attemptLimit > 0) { "attemptLimit must be > 0" }
    }
}

enum class GamePhase {
    NOT_STARTED,
    ACTIVE,
    WON,
    LOST,
}

data class Attempt(
    val guess: String,
    val score: Int,
    val number: Int,
    val isWin: Boolean,
)

data class GameSnapshot(
    val phase: GamePhase,
    val attempts: List<Attempt>,
    val attemptsLeft: Int,
    val debugSecret: String,
    val message: String? = null,
)

class GameEngine(private val config: GameConfig) {

    private val random = config.seed?.let(::Random) ?: Random.Default
    private var secret = ""
    private val history = mutableListOf<Attempt>()
    private var phase = GamePhase.NOT_STARTED

    fun start(): GameSnapshot {
        secret = generateSecret()
        history.clear()
        phase = GamePhase.ACTIVE
        return snapshot()
    }

    fun submit(rawGuess: String): GameSnapshot {
        if (phase != GamePhase.ACTIVE) {
            return snapshot("Игра уже завершена")
        }

        val guess = rawGuess.trim()
        val error = validate(guess)
        if (error != null) {
            return snapshot(error)
        }

        val score = guess.indices.count { guess[it] == secret[it] }
        val isWin = score == config.codeLength

        history += Attempt(
            guess = guess,
            score = score,
            number = history.size + 1,
            isWin = isWin,
        )

        phase = when {
            isWin -> GamePhase.WON
            history.size >= config.attemptLimit -> GamePhase.LOST
            else -> GamePhase.ACTIVE
        }

        return snapshot(
            when (phase) {
                GamePhase.WON -> "Победа"
                GamePhase.LOST -> "Попытки закончились"
                else -> null
            }
        )
    }

    private fun validate(guess: String): String? {
        if (guess.length != config.codeLength) return "Нужно ввести ${config.codeLength} цифр"
        if (guess.any { !it.isDigit() }) return "Можно вводить только цифры"

        if (!config.allowDuplicates && guess.toSet().size != guess.length) {
            return "Повторы запрещены"
        }

        if (config.forbidAllSameDigitsGuess && guess.toSet().size == 1) {
            return "Одинаковые цифры запрещены"
        }

        return null
    }

    private fun generateSecret(): String {
        return if (config.allowDuplicates) {
            buildString {
                repeat(config.codeLength) {
                    append(random.nextInt(0, 10))
                }
            }
        } else {
            (0..9).shuffled(random).take(config.codeLength).joinToString("")
        }
    }

    fun snapshot(message: String? = null): GameSnapshot {
        return GameSnapshot(
            phase = phase,
            attempts = history.toList(),
            attemptsLeft = (config.attemptLimit - history.size).coerceAtLeast(0),
            debugSecret = secret,
            message = message,
        )
    }
}
