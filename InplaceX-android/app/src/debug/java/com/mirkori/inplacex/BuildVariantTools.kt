package com.mirkori.inplacex

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.data.local.GameProgressRepository
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.MonetizationProductType
import com.mirkori.inplacex.data.local.PlatformLocalRepository
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.inplacex.ui.screens.developer.DeveloperRootScreen
import com.mirkori.inplacex.ui.shell.DebugSecretAdSlot

internal fun variantToolsBottomSlotEnabled(toolsEnabled: Boolean): Boolean = false

internal fun testFriendBotEnabled(): Boolean = true

internal fun googleProfileActionsEnabled(): Boolean = true

internal fun variantPaidProgressState(
    local: GameProgressState,
    server: MonetizationEntitlements,
): GameProgressState {
    val proPlusActive = local.proPlusSubscriptionActive || server.proPlusSubscriptionActive
    return local.copy(
        adFreePurchased = local.adFreePurchased || server.adFreePurchased,
        proSubscriptionActive = local.proSubscriptionActive || server.effectiveProAccessActive || proPlusActive,
        proPlusSubscriptionActive = proPlusActive,
    )
}

internal fun initialProgressState(
    context: Context,
    progressRepository: GameProgressRepository,
): GameProgressState {
    val current = progressRepository.loadState()
    val bootstrapPreferences = context.getSharedPreferences(
        DebugBootstrapPreferences,
        Context.MODE_PRIVATE,
    )
    val grantApplied = bootstrapPreferences.getBoolean(DebugInitialCoinGrantKey, false)
    val topUp = debugInitialCoinTopUp(
        currentCoins = current.coins,
        grantApplied = grantApplied,
    )
    val state = if (topUp > 0) {
        progressRepository.addCoins(topUp)
    } else {
        current
    }
    if (!grantApplied) {
        bootstrapPreferences.edit()
            .putBoolean(DebugInitialCoinGrantKey, true)
            .apply()
    }
    return state
}

internal fun debugInitialCoinTopUp(
    currentCoins: Int,
    grantApplied: Boolean,
): Int = if (!grantApplied && currentCoins < DebugInitialCoinGrantAmount) {
    DebugInitialCoinGrantAmount - currentCoins
} else {
    0
}

@Composable
internal fun VariantBottomAdContent(
    inspectionValue: String?,
    adsDisabled: Boolean,
    toolsEnabled: Boolean,
) {
    DebugSecretAdSlot(
        debugSecret = inspectionValue,
        adsDisabled = adsDisabled,
        developerModeEnabled = toolsEnabled,
    )
}

@Composable
internal fun VariantSettingsToolsAction(onOpen: () -> Unit) = Unit

@Composable
internal fun VariantToolsSurface(
    isOpen: Boolean,
    progressState: GameProgressState,
    progressRepository: GameProgressRepository,
    platformLocalRepository: PlatformLocalRepository,
    onProgressStateChange: (GameProgressState) -> Unit,
    onClose: () -> Unit,
) {
    if (!isOpen) return

    Surface(modifier = Modifier.fillMaxSize()) {
        DeveloperRootScreen(
            progressState = progressState,
            platformSnapshot = platformLocalRepository.loadPlatformSnapshot(),
            onAddCoins = {
                onProgressStateChange(progressRepository.addCoins(DebugCoinGrantAmount))
            },
            onAddHelpers = {
                onProgressStateChange(progressRepository.addAllHelpers(3))
            },
            onClearBoosts = {
                onProgressStateChange(progressRepository.clearBoosts())
            },
            onRefillEnergy = {
                if (
                    progressRepository.buyCampaignEnergy(
                        costCoins = 0,
                        amount = progressState.campaignEnergyMax,
                    )
                ) {
                    onProgressStateChange(progressRepository.loadState())
                }
            },
            onEnableAdFree = {
                onProgressStateChange(
                    progressRepository.activateProduct(MonetizationProductType.REMOVE_ADS),
                )
            },
            onDisableAdFree = {
                onProgressStateChange(
                    progressRepository.deactivateProduct(MonetizationProductType.REMOVE_ADS),
                )
            },
            onEnablePro = {
                onProgressStateChange(
                    progressRepository.activateProduct(MonetizationProductType.PRO_SUBSCRIPTION),
                )
            },
            onDisablePro = {
                onProgressStateChange(
                    progressRepository.deactivateProduct(MonetizationProductType.PRO_SUBSCRIPTION),
                )
            },
            onEnableProPlus = {
                onProgressStateChange(
                    progressRepository.activateProduct(MonetizationProductType.PRO_PLUS_SUBSCRIPTION),
                )
            },
            onDisableProPlus = {
                onProgressStateChange(
                    progressRepository.deactivateProduct(MonetizationProductType.PRO_PLUS_SUBSCRIPTION),
                )
            },
        )
    }
    BackHandler(enabled = isOpen, onBack = onClose)
}

private const val DebugCoinGrantAmount = 10_000
private const val DebugInitialCoinGrantAmount = 10_000
private const val DebugBootstrapPreferences = "debug_bootstrap"
private const val DebugInitialCoinGrantKey = "initial_coin_grant_v1"
