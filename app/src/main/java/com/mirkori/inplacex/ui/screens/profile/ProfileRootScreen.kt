package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ProfilePlatformState(
    val title: String,
    val description: String,
    val connected: Boolean,
    val accountId: String
)

@Composable
fun ProfileRootScreen() {
    var nickname by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }

    var googlePlayConnected by rememberSaveable { mutableStateOf(false) }
    var googlePlayId by rememberSaveable { mutableStateOf("") }

    var appStoreConnected by rememberSaveable { mutableStateOf(false) }
    var appStoreId by rememberSaveable { mutableStateOf("") }

    var telegramConnected by rememberSaveable { mutableStateOf(false) }
    var telegramId by rememberSaveable { mutableStateOf("") }

    val platforms = listOf(
        ProfilePlatformState(
            title = "Google Play",
            description = "Android: синхронизация прогресса, achievements и purchases",
            connected = googlePlayConnected,
            accountId = googlePlayId
        ),
        ProfilePlatformState(
            title = "App Store",
            description = "iOS: синхронизация прогресса, achievements и purchases",
            connected = appStoreConnected,
            accountId = appStoreId
        ),
        ProfilePlatformState(
            title = "Telegram",
            description = "Соц-функции: вход, друзья, инвайты, уведомления",
            connected = telegramConnected,
            accountId = telegramId
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Профиль",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Единый профиль для 3 платформ. Здесь храним базовые данные игрока и связи аккаунтов.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Данные игрока", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Никнейм") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = country,
                    onValueChange = { country = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Страна / регион") },
                    singleLine = true
                )
            }
        }

        Text("Подключенные платформы", style = MaterialTheme.typography.titleMedium)

        platforms.forEach { platform ->
            PlatformConnectionCard(
                state = platform,
                onConnectionToggle = { isConnected ->
                    when (platform.title) {
                        "Google Play" -> googlePlayConnected = isConnected
                        "App Store" -> appStoreConnected = isConnected
                        "Telegram" -> telegramConnected = isConnected
                    }
                },
                onAccountIdChange = { newValue ->
                    when (platform.title) {
                        "Google Play" -> googlePlayId = newValue
                        "App Store" -> appStoreId = newValue
                        "Telegram" -> telegramId = newValue
                    }
                }
            )
        }
    }
}

@Composable
private fun PlatformConnectionCard(
    state: ProfilePlatformState,
    onConnectionToggle: (Boolean) -> Unit,
    onAccountIdChange: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Switch(
                    checked = state.connected,
                    onCheckedChange = onConnectionToggle
                )
            }

            Text(
                text = state.description,
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = state.accountId,
                onValueChange = onAccountIdChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.connected,
                label = {
                    Text(
                        if (state.title == "Telegram") {
                            "Username / chat id"
                        } else {
                            "Player id / account token"
                        }
                    )
                },
                singleLine = true
            )
        }
    }
}
