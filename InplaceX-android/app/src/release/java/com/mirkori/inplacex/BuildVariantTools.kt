package com.mirkori.inplacex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.mirkori.inplacex.data.local.GameProgressRepository
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.PlatformLocalRepository
import com.mirkori.inplacex.platform.localization.LocalAppStrings

internal fun variantToolsBottomSlotEnabled(toolsEnabled: Boolean): Boolean = false

internal fun initialProgressState(
    progressRepository: GameProgressRepository,
): GameProgressState = progressRepository.loadState()

@Composable
internal fun VariantBottomAdContent(
    inspectionValue: String?,
    adsDisabled: Boolean,
    toolsEnabled: Boolean,
) {
    if (adsDisabled) return

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = LocalAppStrings.current.text("game.ad_slot"),
            textAlign = TextAlign.Center,
        )
    }
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
) = Unit
