package com.mirkori.inplacex.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.mirkori.inplacex.withServerPaidEntitlements
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.online.OnlineDuelAttemptState
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.TypeGame
import com.mirkori.inplacex.ui.screens.social.OnlineDuelGameField
import com.mirkori.inplacex.ui.theme.InplaceXTheme

/** DEBUG ONLY: synthetic, in-memory A05 entitlement fixture; never exported. */
class AutoTableAccessTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var mode by remember { mutableStateOf(A05Mode.LocalRace) }
            var access by remember { mutableStateOf(A05Access.Free) }
            var fakeNow by remember { mutableLongStateOf(A05_NOW) }
            val local = a05Progress(access)
            val effective = if (access == A05Access.ServerPro) {
                local.withServerPaidEntitlements(
                    MonetizationEntitlements(false, true, false),
                )
            } else local
            val allowed = effective.autoTableAssistEnabledAt(fakeNow)
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU),
            ) {
                InplaceXTheme {
                    Column {
                        Text(
                            "A05 DEBUG SYNTHETIC — Mode: ${mode.name} Access: ${access.name}",
                            modifier = Modifier.testTag("a05-case"),
                        )
                        Row {
                            Button(
                                onClick = { mode = A05Mode.entries[(mode.ordinal + 1) % A05Mode.entries.size] },
                                modifier = Modifier.testTag("a05-mode"),
                            ) { Text("Mode: ${mode.name}") }
                            Button(
                                onClick = {
                                    access = A05Access.entries[(access.ordinal + 1) % A05Access.entries.size]
                                    fakeNow = A05_NOW
                                },
                                modifier = Modifier.testTag("a05-access"),
                            ) { Text("Access: ${access.name}") }
                        }
                        Row {
                            Button(
                                onClick = { access = A05Access.Free; fakeNow = A05_NOW },
                                modifier = Modifier.testTag("a05-revoke"),
                            ) { Text("Revoke") }
                            Button(
                                onClick = { fakeNow = A05_EXPIRY },
                                modifier = Modifier.testTag("a05-expire"),
                            ) { Text("Expire fake clock") }
                        }
                        key(mode) {
                            when (mode) {
                                A05Mode.LocalRace, A05Mode.LocalDuel -> GameFieldScreen(
                                    params = GameFieldParams(
                                        typeGame = if (mode == A05Mode.LocalRace) TypeGame.RaceMatch else TypeGame.DuelMatch,
                                        useHints = false,
                                        useBoosts = false,
                                        lenSecret = 4,
                                    ),
                                    title = mode.name,
                                    fixedSecret = "4556",
                                    autoModeAvailable = allowed,
                                    onBack = {},
                                )
                                A05Mode.Company -> CompanyRootScreen(
                                    progressState = effective,
                                    campaignProgress = emptyList(),
                                    activeLevelNumber = 1,
                                    onActiveLevelNumberChange = {},
                                    autoModeAvailable = allowed,
                                )
                                A05Mode.OnlineDuel, A05Mode.OnlineRace -> OnlineDuelGameField(
                                    snapshot = a05Snapshot(mode),
                                    knownPlayerGuesses = mapOf(1 to "0550", 2 to "0120"),
                                    submitting = false,
                                    onSubmitGuess = {},
                                    onBack = {},
                                    autoModeAvailable = allowed,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val A05_NOW = 1_700_000_000_000L
private const val A05_EXPIRY = A05_NOW + 60_000L

private enum class A05Mode { LocalRace, LocalDuel, Company, OnlineDuel, OnlineRace }
private enum class A05Access { Free, Pro, ProPlus, Temporary, Expired, AdFree, ServerPro }

private fun a05Progress(access: A05Access): GameProgressState = GameProgressState(
    playerDisplayName = "A05 synthetic",
    googlePlaySignedIn = false,
    openPositionHints = 0,
    checkDigitHints = 0,
    checkPositionHints = 0,
    extraMovesBoosts = 0,
    extraTimeBoosts = 0,
    coins = 0,
    campaignEnergy = 1,
    campaignEnergyMax = 1,
    campaignEnergyRefillMinutes = 30,
    matchesPlayed = 0,
    matchesWon = 0,
    highestUnlockedCampaignLevel = 1,
    totalCampaignRating = 0,
    pveStats = ModeStats(0, 0),
    pvpStats = ModeStats(0, 0),
    companyStats = ModeStats(0, 0),
    adFreePurchased = access == A05Access.AdFree,
    proSubscriptionActive = access == A05Access.Pro,
    proPlusSubscriptionActive = access == A05Access.ProPlus,
    temporaryProExpiresAtMs = when (access) {
        A05Access.Temporary -> A05_EXPIRY
        A05Access.Expired -> A05_NOW
        else -> 0L
    },
    campaignTutorialCompleted = true,
)

private fun a05Snapshot(mode: A05Mode): OnlineDuelSnapshotState = OnlineDuelSnapshotState(
    sessionId = if (mode == A05Mode.OnlineRace) {
        "00000000-0000-4000-8000-000000000002"
    } else {
        "00000000-0000-4000-8000-000000000001"
    },
    revision = 2,
    phase = "active",
    currentTurn = "player",
    winner = null,
    playStyle = if (mode == A05Mode.OnlineRace) RemoteFriendPlayStyle.RACE else RemoteFriendPlayStyle.TURN_BASED,
    codeLength = 4,
    attemptLimit = 12,
    allowDuplicates = true,
    attempts = listOf(
        OnlineDuelAttemptState(actor = "player", exactMatches = 2, number = 1, ownGuess = "0550"),
        OnlineDuelAttemptState(actor = "player", exactMatches = 0, number = 2, ownGuess = "0120"),
    ),
    playerSecretConfigured = true,
)
