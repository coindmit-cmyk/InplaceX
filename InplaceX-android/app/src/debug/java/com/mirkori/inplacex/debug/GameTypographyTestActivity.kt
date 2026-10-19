package com.mirkori.inplacex.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.online.OnlineDuelAttemptState
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.game.state.GameFieldOpponentAttempt
import com.mirkori.inplacex.ui.screens.game.state.GameFieldOpponentProgressState
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.TypeGame
import com.mirkori.inplacex.ui.screens.social.OnlineDuelGameField
import com.mirkori.inplacex.ui.theme.InplaceXTheme

/** Debug-only synthetic game fixture. It uses no persistent or remote state. */
class GameTypographyTestActivity : ComponentActivity() {
    private var capturedSecret: String? = null

    val caseLength: Int
        get() = intent?.getIntExtra(EXTRA_CODE_LENGTH, 6)?.coerceIn(4, 10) ?: 6

    fun isSecretReady(): Boolean = !capturedSecret.isNullOrBlank()

    fun safeNonWinningGuess(): String {
        val first = "0123456789".take(caseLength)
        return if (first == capturedSecret) "1234567890".take(caseLength) else first
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "LocalRace"
        val compact = intent.getBooleanExtra(EXTRA_COMPACT, true)
        val length = caseLength
        setContent {
            CompositionLocalProvider(LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU)) {
                InplaceXTheme {
                    Column(Modifier.fillMaxSize()) {
                        Column(if (compact) Modifier else Modifier.height(0.dp)) {
                            Text("A07 typography fixture", Modifier.testTag("a07-case"))
                            Text("fontScale=${LocalDensity.current.fontScale}", Modifier.testTag("a07-font-scale"))
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            val slotHeight = if (compact) maxHeight.coerceAtMost(600.dp) else maxHeight
                            Column(Modifier.fillMaxWidth().heightIn(max = slotHeight)) {
                                when (mode) {
                                    "LocalRace", "LocalDuel" -> GameFieldScreen(
                                        params = GameFieldParams(
                                            typeGame = if (mode == "LocalRace") TypeGame.RaceMatch else TypeGame.DuelMatch,
                                            lenSecret = length,
                                            timeAll = 600,
                                            timeMove = 120,
                                            limitMoves = 12,
                                            useHints = true,
                                            useBoosts = true,
                                        ),
                                        title = mode,
                                        onBack = {},
                                        onDebugSecretChange = { capturedSecret = it },
                                        fixedSecret = "9876543210".take(length),
                                        openPositionHints = 123,
                                        checkDigitHints = 123,
                                        checkPositionHints = 123,
                                        extraMovesBoosts = 123,
                                        extraTimeBoosts = 123,
                                        extraMovesPerBoost = 3,
                                        extraTimeSecondsPerBoost = 60,
                                        autoModeAvailable = false,
                                        opponentProgress = if (mode == "LocalRace") GameFieldOpponentProgressState(
                                            attempts = listOf(
                                                GameFieldOpponentAttempt(1, 1),
                                                GameFieldOpponentAttempt(2, 2),
                                                GameFieldOpponentAttempt(3, 3),
                                            ),
                                        ) else null,
                                    )

                                    "Company" -> CompanyRootScreen(
                                        progressState = syntheticProgressState(),
                                        campaignProgress = emptyList(),
                                        activeLevelNumber = when (length) { 4 -> 1; 6 -> 41; else -> 301 },
                                        onActiveLevelNumberChange = {},
                                        onDebugSecretChange = { capturedSecret = it },
                                        openPositionHints = 123,
                                        checkDigitHints = 123,
                                        checkPositionHints = 123,
                                        extraMovesBoosts = 123,
                                        extraTimeBoosts = 123,
                                        autoModeAvailable = false,
                                    )

                                    "OnlineDuel", "OnlineRace" -> {
                                        val now = System.currentTimeMillis()
                                        val start = now - 119_000L
                                        OnlineDuelGameField(
                                            snapshot = OnlineDuelSnapshotState(
                                                sessionId = "a07-synthetic",
                                                revision = 1L,
                                                phase = "active",
                                                playerSecretConfigured = true,
                                                currentTurn = "player",
                                                winner = null,
                                                playStyle = if (mode == "OnlineRace") RemoteFriendPlayStyle.RACE else RemoteFriendPlayStyle.TURN_BASED,
                                                codeLength = length,
                                                attemptLimit = 12,
                                                allowDuplicates = true,
                                                startedAtEpochMs = start,
                                                deadlineAtEpochMs = start + 600_000L,
                                                serverTimeEpochMs = now,
                                                attempts = listOf(
                                                    OnlineDuelAttemptState("player", 1, 1, "0123456789".take(length)),
                                                    OnlineDuelAttemptState("player", 2, 2, "1234567890".take(length)),
                                                ),
                                            ),
                                            knownPlayerGuesses = mapOf(
                                                1 to "0123456789".take(length),
                                                2 to "1234567890".take(length),
                                            ),
                                            submitting = false,
                                            onSubmitGuess = {},
                                            onBack = {},
                                            autoModeAvailable = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syntheticProgressState() = GameProgressState(
        playerDisplayName = "A07",
        googlePlaySignedIn = false,
        openPositionHints = 123,
        checkDigitHints = 123,
        checkPositionHints = 123,
        extraMovesBoosts = 123,
        extraTimeBoosts = 123,
        coins = 0,
        campaignEnergy = 0,
        campaignEnergyMax = 0,
        campaignEnergyRefillMinutes = 0,
        matchesPlayed = 0,
        matchesWon = 0,
        highestUnlockedCampaignLevel = 301,
        totalCampaignRating = 0,
        pveStats = ModeStats(0, 0),
        pvpStats = ModeStats(0, 0),
        companyStats = ModeStats(0, 0),
        adFreePurchased = false,
        proSubscriptionActive = false,
        proPlusSubscriptionActive = false,
        temporaryProExpiresAtMs = 0L,
        campaignTutorialCompleted = true,
    )

    private companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CODE_LENGTH = "codeLength"
        const val EXTRA_COMPACT = "compact"
    }
}
