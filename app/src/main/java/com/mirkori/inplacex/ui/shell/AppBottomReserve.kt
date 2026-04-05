package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.ui.navigation.AppSection

@Composable
fun AppBottomReserve(
    currentSection: AppSection,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = reserveText(currentSection),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun reserveText(section: AppSection): String {
    return when (section) {
        AppSection.HOME -> "Нижний резерв: баннер / акция / быстрый вход"
        AppSection.SOCIAL -> "Нижний резерв: реклама / комната / онлайн-событие"
        AppSection.TOURNAMENTS -> "Нижний резерв: турнирный баннер / таймер события"
        AppSection.SHOP -> "Нижний резерв: оффер / премиум / акция магазина"
        AppSection.PROFILE -> "Нижний резерв: premium / статус / сервисная зона"
    }
}
