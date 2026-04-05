package com.mirkori.inplacex.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class BottomReserveMode {
    MENU,
    AD_PLACEHOLDER,
    PREMIUM_PLACEHOLDER
}

@Composable
fun ScreenBottomReserve(
    mode: BottomReserveMode,
    modifier: Modifier = Modifier,
    onMenuHomeClick: (() -> Unit)? = null,
    onMenuPlayClick: (() -> Unit)? = null,
    onMenuProfileClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp
    ) {
        when (mode) {
            BottomReserveMode.MENU -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { onMenuHomeClick?.invoke() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Главная")
                    }
                    FilledTonalButton(
                        onClick = { onMenuPlayClick?.invoke() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Играть")
                    }
                    FilledTonalButton(
                        onClick = { onMenuProfileClick?.invoke() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Профиль")
                    }
                }
            }

            BottomReserveMode.AD_PLACEHOLDER -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Нижний резерв экрана\nпод рекламу / системный блок",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            BottomReserveMode.PREMIUM_PLACEHOLDER -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Premium-заглушка\nздесь может быть чистый нижний блок",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
