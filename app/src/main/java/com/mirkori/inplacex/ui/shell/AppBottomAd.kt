package com.mirkori.inplacex.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AppBottomAd(
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null
) {
    AppBottomReserve(
        text = "Рекламный слот игрового экрана",
        modifier = modifier,
        content = content
    )
}
