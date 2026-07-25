package com.mirkori.inplacex.backend.domain.duel

import com.mirkori.inplacex.backend.session.domain.MutableDuelCommand
import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.model.GameConfig

enum class DuelParticipant {
    FIRST,
    SECOND,
    ;

    fun opponent(): DuelParticipant = if (this == FIRST) SECOND else FIRST
}

enum class DuelPhase {
    SETUP,
    ACTIVE,
    FINISHED,
}

enum class DuelCommandRejection {
    INVALID_SECRET,
    INVALID_GUESS,
    SECRET_NOT_EXPECTED,
    MATCH_NOT_ACTIVE,
    NOT_CURRENT_TURN,
    MATCH_FINISHED,
}

class DuelCommandRejectedException(
    val rejection: DuelCommandRejection,
    val validationReason: GuessValidationReason? = null,
) : IllegalStateException(
    buildString {
        append(rejection.name)
        validationReason?.let { append(":${it.name}") }
    },
)

data class DuelAttempt(
    val attacker: DuelParticipant,
    val exactMatches: Int,
    val number: Int,
)

data class DuelParticipantSnapshot(
    val participant: DuelParticipant,
    val secretConfigured: Boolean,
    val attemptsUsed: Int,
    val attemptsLeft: Int,
)

/**
 * Состояние дуэли для внешнего представления: поле секрета намеренно отсутствует.
 */
data class DuelSnapshot(
    val config: GameConfig,
    val phase: DuelPhase,
    val awaitingSecretFrom: DuelParticipant?,
    val currentTurn: DuelParticipant?,
    val winner: DuelParticipant?,
    val attempts: List<DuelAttempt>,
    val participants: List<DuelParticipantSnapshot>,
)

/**
 * Авторитетный in-memory агрегат для дуэли с двумя секретами.
 * Аутентификация и хранение остаются ответственностью transport и persistence слоёв.
 */
class DuelMatch private constructor(
    val config: GameConfig,
) : AutoCloseable {
    private val secrets = mutableMapOf<DuelParticipant, CharArray>()
    private val configuredParticipants = mutableSetOf<DuelParticipant>()
    private val attempts = mutableListOf<DuelAttempt>()
    private var phase = DuelPhase.SETUP
    private var currentTurn: DuelParticipant? = null
    private var winner: DuelParticipant? = null
    private var closed = false

    @Synchronized
    fun setSecret(
        participant: DuelParticipant,
        command: MutableDuelCommand.Secret,
    ): DuelSnapshot = consumeCommand(command) { secret ->
        ensureOpen()
        rejectFinishedMatch()
        if (phase != DuelPhase.SETUP) {
            reject(DuelCommandRejection.SECRET_NOT_EXPECTED)
        }
        val expectedParticipant = expectedSecretParticipant()
        if (participant != expectedParticipant) {
            reject(DuelCommandRejection.SECRET_NOT_EXPECTED)
        }

        val validationReason = GuessValidator.validateOrReason(secret, config)
        if (validationReason != null) {
            reject(DuelCommandRejection.INVALID_SECRET, validationReason)
        }

        val retainedSecret = secret.copyOf()
        var retained = false
        try {
            secrets[participant] = retainedSecret
            retained = true
            configuredParticipants += participant
            if (configuredParticipants.size == DuelParticipant.entries.size) {
                phase = DuelPhase.ACTIVE
                currentTurn = DuelParticipant.FIRST
            }
            snapshot()
        } finally {
            if (!retained) {
                retainedSecret.fill(CLEARED_DIGIT)
            }
        }
    }

    @Synchronized
    fun submitGuess(
        attacker: DuelParticipant,
        command: MutableDuelCommand.Guess,
    ): DuelSnapshot = consumeCommand(command) { guess ->
        ensureOpen()
        when (phase) {
            DuelPhase.SETUP -> reject(DuelCommandRejection.MATCH_NOT_ACTIVE)
            DuelPhase.FINISHED -> reject(DuelCommandRejection.MATCH_FINISHED)
            DuelPhase.ACTIVE -> Unit
        }
        if (currentTurn != attacker) {
            reject(DuelCommandRejection.NOT_CURRENT_TURN)
        }

        val validationReason = GuessValidator.validateOrReason(guess, config)
        if (validationReason != null) {
            reject(DuelCommandRejection.INVALID_GUESS, validationReason)
        }

        val exactMatches = ScoreCalculator.countExactMatches(
            secret = requireNotNull(secrets[attacker.opponent()]),
            guess = guess,
        )
        attempts += DuelAttempt(
            attacker = attacker,
            exactMatches = exactMatches,
            number = attempts.size + 1,
        )

        when {
            exactMatches == config.codeLength -> finish(attacker)
            attemptsFor(attacker) >= config.attemptLimit -> finish(attacker.opponent())
            else -> currentTurn = attacker.opponent()
        }
        snapshot()
    }

    @Synchronized
    fun snapshot(): DuelSnapshot {
        ensureOpen()
        return try {
            DuelSnapshot(
                config = config,
                phase = phase,
                awaitingSecretFrom = if (phase == DuelPhase.SETUP) expectedSecretParticipant() else null,
                currentTurn = currentTurn,
                winner = winner,
                attempts = attempts.toList(),
                participants = DuelParticipant.entries.map { participant ->
                    val attemptsUsed = attemptsFor(participant)
                    DuelParticipantSnapshot(
                        participant = participant,
                        secretConfigured = participant in configuredParticipants,
                        attemptsUsed = attemptsUsed,
                        attemptsLeft = (config.attemptLimit - attemptsUsed).coerceAtLeast(0),
                    )
                },
            )
        } catch (failure: Throwable) {
            failClosed()
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            wipeSecrets()
        }
    }

    private fun expectedSecretParticipant(): DuelParticipant {
        return if (DuelParticipant.FIRST !in configuredParticipants) {
            DuelParticipant.FIRST
        } else {
            DuelParticipant.SECOND
        }
    }

    private fun attemptsFor(participant: DuelParticipant): Int = attempts.count { it.attacker == participant }

    private fun finish(winningParticipant: DuelParticipant) {
        phase = DuelPhase.FINISHED
        currentTurn = null
        winner = winningParticipant
        wipeSecrets()
    }

    private fun rejectFinishedMatch() {
        if (phase == DuelPhase.FINISHED) {
            reject(DuelCommandRejection.MATCH_FINISHED)
        }
    }

    private fun ensureOpen() {
        check(!closed) { "Duel match is closed" }
    }

    private fun <T> consumeCommand(
        command: MutableDuelCommand,
        operation: (CharArray) -> T,
    ): T {
        return try {
            command.consume(operation)
        } catch (rejection: DuelCommandRejectedException) {
            throw rejection
        } catch (failure: Throwable) {
            failClosed()
            throw failure
        }
    }

    private fun failClosed() {
        closed = true
        wipeSecrets()
    }

    private fun wipeSecrets() {
        secrets.values.forEach { secret -> secret.fill(CLEARED_DIGIT) }
        secrets.clear()
    }

    private fun reject(
        rejection: DuelCommandRejection,
        validationReason: GuessValidationReason? = null,
    ): Nothing = throw DuelCommandRejectedException(rejection, validationReason)

    companion object {
        fun create(config: GameConfig): DuelMatch = DuelMatch(config)
    }
}

private const val CLEARED_DIGIT: Char = '\u0000'
