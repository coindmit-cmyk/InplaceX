package com.mirkori.inplacex.ui.screens.game.state

import com.mirkori.inplacex.core.analysis.AcceptedAttemptEvidence
import com.mirkori.inplacex.core.analysis.DeductionResult
import com.mirkori.inplacex.core.analysis.ManualHypothesis
import com.mirkori.inplacex.core.analysis.ProvenFact
import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.model.GameConfig

enum class GameFieldMode {
    RACE,
    DUEL,
}

data class GameFieldMatchParameters(
    val mode: GameFieldMode = GameFieldMode.RACE,
    val codeLength: Int = 6,
    val attemptLimit: Int = 12,
    val allowDuplicates: Boolean = true,
    val forbidAllSameDigitsGuess: Boolean = true,
    val forbidAdjacentDuplicates: Boolean = false,
    val forbidTripleDuplicates: Boolean = false,
    val maxConsecutiveDuplicateDigits: Int? = null,
    val totalTimeLimitSeconds: Int = 0,
    val turnTimeLimitSeconds: Int = 0,
    val hintsEnabled: Boolean = true,
    val boostsEnabled: Boolean = false,
    val autoModeAvailable: Boolean = true,
) {
    init {
        require(codeLength in 4..20)
        require(allowDuplicates || codeLength <= 10)
        require(attemptLimit > 0)
        require(maxConsecutiveDuplicateDigits == null || maxConsecutiveDuplicateDigits in 1..codeLength)
        require(totalTimeLimitSeconds >= 0)
        require(turnTimeLimitSeconds >= 0)
    }
}

internal fun GameFieldMatchParameters.toGameConfig(): GameConfig = GameConfig(
    codeLength = codeLength,
    allowDuplicates = allowDuplicates,
    attemptLimit = attemptLimit,
    forbidAllSameDigitsGuess = forbidAllSameDigitsGuess,
    forbidAdjacentDuplicates = forbidAdjacentDuplicates,
    forbidTripleDuplicates = forbidTripleDuplicates,
    maxConsecutiveDuplicateDigits = maxConsecutiveDuplicateDigits,
    turnTimeLimitSeconds = turnTimeLimitSeconds.takeIf { it > 0 },
)

enum class GameFieldTool {
    NO,
    MAYBE,
    YES,
}

enum class GameFieldHintMode {
    OPEN_POSITION,
    CHECK_DIGIT,
    CHECK_POSITION,
}

enum class GameFieldBoostMode {
    EXTRA_MOVES,
    EXTRA_TIME,
}

enum class GameFieldManualMarkType {
    NO,
    MAYBE,
    YES,
}

data class GameFieldManualMark(
    val position: Int,
    val symbol: Char,
    val type: GameFieldManualMarkType,
)

data class GameFieldInputState(
    val slots: List<Char?>,
) {
    val isComplete: Boolean
        get() = slots.all { it != null }

    fun guessOrNull(provenFacts: Collection<ProvenFact>): String? {
        val exactMatches = provenFacts
            .asSequence()
            .filter { it.isExactMatch }
            .associate { it.position to it.symbol }
        val resolved = slots.mapIndexed { position, symbol -> exactMatches[position] ?: symbol }
        return resolved.takeIf { it.all { symbol -> symbol != null } }
            ?.joinToString(separator = "") { it.toString() }
    }

    companion object {
        fun empty(codeLength: Int): GameFieldInputState =
            GameFieldInputState(List(codeLength) { null })
    }
}

data class GameFieldTimers(
    val elapsedSeconds: Int = 0,
    val turnElapsedSeconds: Int = 0,
    val bonusTimeSeconds: Int = 0,
)

data class GameFieldToolsState(
    val selectedTool: GameFieldTool = GameFieldTool.NO,
    val selectedHint: GameFieldHintMode? = null,
    val autoExcludeEnabled: Boolean = true,
)

data class GameFieldCounters(
    val openPositionHintUses: Int = 0,
    val checkDigitHintUses: Int = 0,
    val checkPositionHintUses: Int = 0,
    val extraMovesBoostUses: Int = 0,
    val extraTimeBoostUses: Int = 0,
    val bonusMoves: Int = 0,
)

data class GameFieldOpponentAttempt(
    val number: Int,
    val exactMatches: Int,
) {
    init {
        require(number > 0)
        require(exactMatches >= 0)
    }
}

data class GameFieldOpponentProgressState(
    val attempts: List<GameFieldOpponentAttempt> = emptyList(),
    val isThinking: Boolean = false,
    val completed: Boolean = false,
    val failed: Boolean = false,
)

data class GameFieldRouteUiState(
    val modeLabel: String? = null,
    val turnLabel: String? = null,
    val secondaryStatusText: String? = null,
    val inputEnabled: Boolean = true,
    val configuredMoveLimit: Int? = null,
    val movesUnlimited: Boolean = false,
    val openPositionHints: Int = 0,
    val checkDigitHints: Int = 0,
    val checkPositionHints: Int = 0,
    val infiniteHintsEnabled: Boolean = false,
    val extraMovesBoosts: Int = 0,
    val extraTimeBoosts: Int = 0,
    val extraMovesPerBoost: Int = 0,
    val extraTimeSecondsPerBoost: Int = 0,
    val pendingRewardedHint: GameFieldHintMode? = null,
    val rewardedHintInFlight: Boolean = false,
    val opponentProgress: GameFieldOpponentProgressState? = null,
)

data class GameFieldMatchState(
    val phase: MatchPhase,
    val attempts: List<MatchAttempt>,
    val attemptsLeft: Int,
    val debugSecret: String,
)

data class GameFieldEvidenceState(
    val acceptedAttempts: List<AcceptedAttemptEvidence>,
    val hypotheses: List<ManualHypothesis>,
    val provenFacts: Set<ProvenFact>,
    val deduction: DeductionResult,
)

sealed interface GameFieldNotice {
    object NoHints : GameFieldNotice

    object WatchAdForHint : GameFieldNotice

    object BonusHintReady : GameFieldNotice

    object BonusNotGranted : GameFieldNotice

    object HintUnavailable : GameFieldNotice

    object NoMoveBoosts : GameFieldNotice

    data class MovesAdded(val count: Int) : GameFieldNotice

    object NoTimeBoosts : GameFieldNotice

    data class TimeAdded(val seconds: Int) : GameFieldNotice

    object NewSecret : GameFieldNotice

    object AutoEnabled : GameFieldNotice

    object AutoDisabled : GameFieldNotice
}

sealed interface GameFieldStatus {
    object Idle : GameFieldStatus

    object InputIncomplete : GameFieldStatus

    data class EngineFeedback(
        val feedback: MatchFeedback?,
    ) : GameFieldStatus

    data class AttemptAccepted(
        val attempt: MatchAttempt,
    ) : GameFieldStatus

    data class HintPositionChecked(
        val digit: Int,
        val position: Int,
        val isMatch: Boolean,
    ) : GameFieldStatus

    data class HintPositionOpened(
        val digit: Int,
        val position: Int,
    ) : GameFieldStatus

    data class HintDigitCount(
        val digit: Int,
        val count: Int,
    ) : GameFieldStatus

    data class Notice(
        val notice: GameFieldNotice,
    ) : GameFieldStatus

    object TimedOut : GameFieldStatus
}

data class GameFieldUiState(
    val parameters: GameFieldMatchParameters,
    val match: GameFieldMatchState,
    val input: GameFieldInputState,
    val evidence: GameFieldEvidenceState,
    val manualMarks: List<GameFieldManualMark>,
    val timers: GameFieldTimers,
    val tools: GameFieldToolsState,
    val counters: GameFieldCounters,
    val status: GameFieldStatus,
    val route: GameFieldRouteUiState = GameFieldRouteUiState(),
)

sealed interface GameFieldEvent {
    data class DigitEntered(val digit: Char) : GameFieldEvent

    object BackspacePressed : GameFieldEvent

    object GuessSubmitted : GameFieldEvent

    object MatchRestarted : GameFieldEvent

    data class ToolSelected(val tool: GameFieldTool) : GameFieldEvent

    data class HintSelected(val hint: GameFieldHintMode?) : GameFieldEvent

    data class AutoExcludeChanged(val enabled: Boolean) : GameFieldEvent

    data class ManualMarkChanged(
        val position: Int,
        val symbol: Char,
        val type: GameFieldManualMarkType?,
    ) : GameFieldEvent

    data class ProvenFactRecorded(val fact: ProvenFact) : GameFieldEvent

    data class HintConsumed(val hint: GameFieldHintMode) : GameFieldEvent

    data class PositionHintRequested(
        val digit: Int,
        val position: Int,
    ) : GameFieldEvent

    data class OpenPositionHintRequested(
        val position: Int,
    ) : GameFieldEvent

    data class DigitHintRequested(
        val digit: Int,
    ) : GameFieldEvent

    data class BoostConsumed(
        val boost: GameFieldBoostMode,
        val amount: Int,
    ) : GameFieldEvent

    data class TimerTicked(val seconds: Int = 1) : GameFieldEvent

    data class NoticeChanged(val notice: GameFieldNotice?) : GameFieldEvent
}
