package com.mirkori.inplacex.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.ui.navigation.AppSection

@Composable
fun AppBottomSlot(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    isInGame: Boolean,
    isPremium: Boolean,
    modifier: Modifier = Modifier
) {
    when {
        isPremium -> {
            AppBottomPremium(modifier = modifier)
        }

        isInGame -> {
            AppBottomAd(modifier = modifier)
        }

        else -> {
            AppBottomMenu(
                currentSection = currentSection,
                onSectionChange = onSectionChange,
                modifier = modifier
            )
        }
    }
}
