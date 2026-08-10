package com.mirkori.inplacex.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.ui.navigation.AppSection

@Composable
fun AppBottomSlot(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    socialNotificationCount: Int = 0,
    bottomMode: BottomLayerMode,
    adContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    when (bottomMode) {
        BottomLayerMode.MENU -> AppBottomMenu(
            currentSection = currentSection,
            onSectionChange = onSectionChange,
            socialNotificationCount = socialNotificationCount,
            modifier = modifier
        )

        BottomLayerMode.AD,
        BottomLayerMode.AD_LOADING,
        -> AppBottomAd(
            modifier = modifier,
            content = adContent
        )

        BottomLayerMode.NONE -> Unit
    }
}
