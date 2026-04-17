package com.mirkori.inplacex.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AppBottomPremium(
    modifier: Modifier = Modifier
) {
    AppBottomReserve(
        text = "Premium: без рекламы",
        modifier = modifier
    )
}
