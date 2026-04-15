package com.mirkori.inplacex.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.ui.navigation.AppSection

@Composable
fun AppBottomSlot(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    bottomMode: BottomLayerMode,
    modifier: Modifier = Modifier
) {
    when (bottomMode) {
        BottomLayerMode.MENU -> AppBottomMenu(
            currentSection = currentSection,
            onSectionChange = onSectionChange,
            modifier = modifier
        )

        BottomLayerMode.AD -> AppBottomAd(modifier = modifier)

        BottomLayerMode.NONE -> Unit
    }
}
