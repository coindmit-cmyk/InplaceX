package com.mirkori.inplacex.ui.screens.game.state

import androidx.lifecycle.SavedStateHandle
import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchCheckpoint
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.analysis.ProvenFact
import com.mirkori.inplacex.core.analysis.ProvenFactKind

internal data class RestoredGameFieldState(
    val checkpoint: MatchCheckpoint,
    val input: GameFieldInputState,
    val manualMarks: List<GameFieldManualMark>,
    val provenFacts: Set<ProvenFact>,
    val timers: GameFieldTimers,
    val tools: GameFieldToolsState,
    val counters: GameFieldCounters,
)

/** Хранит только долговечные данные матча и UI без разовых наград, диалогов и callback-ов. */
internal class GameFieldSavedStateStore(
    private val savedStateHandle: SavedStateHandle,
) {
    fun save(state: GameFieldUiState, checkpoint: MatchCheckpoint) {
        savedStateHandle[VERSION] = STATE_VERSION
        savedStateHandle[PARAMETERS] = arrayListOf(
            state.parameters.mode.name,
            state.parameters.codeLength.toString(),
            state.parameters.attemptLimit.toString(),
            state.parameters.allowDuplicates.toString(),
            state.parameters.forbidAllSameDigitsGuess.toString(),
            state.parameters.forbidAdjacentDuplicates.toString(),
            state.parameters.forbidTripleDuplicates.toString(),
            state.parameters.maxConsecutiveDuplicateDigits?.toString().orEmpty(),
            state.parameters.totalTimeLimitSeconds.toString(),
            state.parameters.turnTimeLimitSeconds.toString(),
            state.parameters.hintsEnabled.toString(),
            state.parameters.boostsEnabled.toString(),
            state.parameters.autoModeAvailable.toString(),
        )
        savedStateHandle[CHECKPOINT] = arrayListOf(
            checkpoint.secret,
            checkpoint.phase.name,
            checkpoint.extraAttemptBudget.toString(),
        )
        savedStateHandle[ATTEMPTS] = ArrayList(checkpoint.attempts.map { it.encode() })
        savedStateHandle[INPUT] = ArrayList(state.input.slots.map { it?.toString().orEmpty() })
        savedStateHandle[MANUAL_MARKS] = ArrayList(state.manualMarks.map { it.encode() })
        savedStateHandle[PROVEN_FACTS] = ArrayList(state.evidence.provenFacts.map { it.encode() })
        savedStateHandle[TIMERS] = arrayListOf(
            state.timers.elapsedSeconds.toString(),
            state.timers.turnElapsedSeconds.toString(),
            state.timers.bonusTimeSeconds.toString(),
        )
        savedStateHandle[TOOLS] = arrayListOf(
            state.tools.selectedTool.name,
            state.tools.selectedHint?.name.orEmpty(),
            state.tools.autoExcludeEnabled.toString(),
        )
        savedStateHandle[COUNTERS] = arrayListOf(
            state.counters.openPositionHintUses.toString(),
            state.counters.checkDigitHintUses.toString(),
            state.counters.checkPositionHintUses.toString(),
            state.counters.extraMovesBoostUses.toString(),
            state.counters.extraTimeBoostUses.toString(),
            state.counters.bonusMoves.toString(),
        )
    }

    fun restore(parameters: GameFieldMatchParameters): RestoredGameFieldState? {
        val stateVersion = savedStateHandle.get<Int>(VERSION) ?: return null
        if (stateVersion !in MIN_SUPPORTED_STATE_VERSION..STATE_VERSION ||
            restoredParameters(stateVersion) != parameters
        ) {
            return null
        }

        return runCatching {
            val checkpointParts = values(CHECKPOINT, 3)
            val checkpoint = MatchCheckpoint(
                secret = checkpointParts[0],
                phase = MatchPhase.valueOf(checkpointParts[1]),
                attempts = values(ATTEMPTS).map(::decodeAttempt),
                extraAttemptBudget = checkpointParts[2].toInt(),
            )
            val input = GameFieldInputState(values(INPUT, parameters.codeLength).map { it.singleOrNull() })
            val timers = values(TIMERS, 3).let {
                GameFieldTimers(it[0].toInt(), it[1].toInt(), it[2].toInt())
            }
            val tools = values(TOOLS, 3).let {
                GameFieldToolsState(
                    selectedTool = GameFieldTool.valueOf(it[0]),
                    selectedHint = it[1].takeIf(String::isNotEmpty)?.let(GameFieldHintMode::valueOf),
                    autoExcludeEnabled = it[2].toBooleanStrict(),
                )
            }
            val counters = values(COUNTERS, 6).let {
                GameFieldCounters(
                    openPositionHintUses = it[0].toInt(),
                    checkDigitHintUses = it[1].toInt(),
                    checkPositionHintUses = it[2].toInt(),
                    extraMovesBoostUses = it[3].toInt(),
                    extraTimeBoostUses = it[4].toInt(),
                    bonusMoves = it[5].toInt(),
                )
            }
            RestoredGameFieldState(
                checkpoint = checkpoint,
                input = input,
                manualMarks = values(MANUAL_MARKS).map(::decodeManualMark),
                provenFacts = values(PROVEN_FACTS).map(::decodeProvenFact).toSet(),
                timers = timers,
                tools = tools,
                counters = counters,
            ).also(::validate)
        }.getOrNull()
    }

    private fun restoredParameters(stateVersion: Int): GameFieldMatchParameters? = runCatching {
        val expectedSize = if (stateVersion == 1) LEGACY_PARAMETER_COUNT else PARAMETER_COUNT
        values(PARAMETERS, expectedSize).let {
            GameFieldMatchParameters(
                mode = GameFieldMode.valueOf(it[0]),
                codeLength = it[1].toInt(),
                attemptLimit = it[2].toInt(),
                allowDuplicates = it[3].toBooleanStrict(),
                forbidAllSameDigitsGuess = if (stateVersion == 1) true else it[4].toBooleanStrict(),
                forbidAdjacentDuplicates = if (stateVersion == 1) false else it[5].toBooleanStrict(),
                forbidTripleDuplicates = if (stateVersion == 1) false else it[6].toBooleanStrict(),
                maxConsecutiveDuplicateDigits = if (stateVersion == 1) {
                    null
                } else {
                    it[7].takeIf(String::isNotEmpty)?.toInt()
                },
                totalTimeLimitSeconds = it[if (stateVersion == 1) 4 else 8].toInt(),
                turnTimeLimitSeconds = it[if (stateVersion == 1) 5 else 9].toInt(),
                hintsEnabled = it[if (stateVersion == 1) 6 else 10].toBooleanStrict(),
                boostsEnabled = it[if (stateVersion == 1) 7 else 11].toBooleanStrict(),
                autoModeAvailable = it[if (stateVersion == 1) 8 else 12].toBooleanStrict(),
            )
        }
    }.getOrNull()

    private fun validate(state: RestoredGameFieldState): RestoredGameFieldState {
        require(state.timers.elapsedSeconds >= 0)
        require(state.timers.turnElapsedSeconds >= 0)
        require(state.timers.bonusTimeSeconds >= 0)
        require(state.counters.bonusMoves >= 0)
        return state
    }

    private fun values(key: String, expectedSize: Int? = null): List<String> {
        val values = savedStateHandle.get<ArrayList<String>>(key)?.toList() ?: emptyList()
        require(expectedSize == null || values.size == expectedSize)
        return values
    }

    private fun MatchAttempt.encode(): String = listOf(guess, score, number, isWin).joinToString(SEPARATOR)

    private fun GameFieldManualMark.encode(): String = listOf(position, symbol, type.name).joinToString(SEPARATOR)

    private fun ProvenFact.encode(): String = listOf(position, symbol, kind.name).joinToString(SEPARATOR)

    private fun decodeAttempt(value: String): MatchAttempt {
        val parts = value.split(SEPARATOR)
        require(parts.size == 4)
        return MatchAttempt(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toBooleanStrict())
    }

    private fun decodeManualMark(value: String): GameFieldManualMark {
        val parts = value.split(SEPARATOR)
        require(parts.size == 3)
        return GameFieldManualMark(parts[0].toInt(), parts[1].single(), GameFieldManualMarkType.valueOf(parts[2]))
    }

    private fun decodeProvenFact(value: String): ProvenFact {
        val parts = value.split(SEPARATOR)
        require(parts.size == 3)
        return ProvenFact(parts[0].toInt(), parts[1].single(), ProvenFactKind.valueOf(parts[2]))
    }

    private companion object {
        const val STATE_VERSION = 2
        const val MIN_SUPPORTED_STATE_VERSION = 1
        const val LEGACY_PARAMETER_COUNT = 9
        const val PARAMETER_COUNT = 13
        const val SEPARATOR = "|"
        const val VERSION = "game_field_state_version"
        const val PARAMETERS = "game_field_parameters"
        const val CHECKPOINT = "game_field_checkpoint"
        const val ATTEMPTS = "game_field_attempts"
        const val INPUT = "game_field_input"
        const val MANUAL_MARKS = "game_field_manual_marks"
        const val PROVEN_FACTS = "game_field_proven_facts"
        const val TIMERS = "game_field_timers"
        const val TOOLS = "game_field_tools"
        const val COUNTERS = "game_field_counters"
    }
}
