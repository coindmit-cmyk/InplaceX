package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ProfilePlatformState(
    val title: String,
    val description: String,
    val connected: Boolean,
    val accountId: String
)

private const val IS_DEVELOPER_BUILD = false

@Composable
fun ProfileRootScreen() {
    var nickname by rememberSaveable { mutableStateOf("Игрок_7065") }
    var inGameSince by rememberSaveable { mutableStateOf("08 / 2025") }
    var totalScore by rememberSaveable { mutableStateOf("1430") }

    var googlePlayConnected by rememberSaveable { mutableStateOf(false) }
    var googlePlayId by rememberSaveable { mutableStateOf("") }

    var appStoreConnected by rememberSaveable { mutableStateOf(false) }
    var appStoreId by rememberSaveable { mutableStateOf("") }

    var telegramConnected by rememberSaveable { mutableStateOf(false) }
    var telegramId by rememberSaveable { mutableStateOf("") }

    var showDeveloperBindings by rememberSaveable { mutableStateOf(false) }

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
        ProfileHeaderCard(
            nickname = nickname,
            inGameSince = inGameSince,
            totalScore = totalScore,
            onNicknameChange = { nickname = it },
            onInGameSinceChange = { inGameSince = it },
            onTotalScoreChange = { totalScore = it }
        )

        SaveProgressCard()

        AchievementsCard()

        StatisticsCard()

        Text("Подключение сервисов", style = MaterialTheme.typography.titleMedium)

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

        if (IS_DEVELOPER_BUILD) {
            DeveloperBindingsCard(
                showBindings = showDeveloperBindings,
                onToggleShow = { showDeveloperBindings = !showDeveloperBindings },
                googlePlayId = googlePlayId,
                appStoreId = appStoreId,
                telegramId = telegramId
            )
        }
    }
}

@Composable
private fun ProfileHeaderCard(
    nickname: String,
    inGameSince: String,
    totalScore: String,
    onNicknameChange: (String) -> Unit,
    onInGameSinceChange: (String) -> Unit,
    onTotalScoreChange: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", style = MaterialTheme.typography.headlineMedium)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "В игре с $inGameSince",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Всего очков: $totalScore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ник") },
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inGameSince,
                    onValueChange = onInGameSinceChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("В игре с") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = totalScore,
                    onValueChange = onTotalScoreChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Всего очков") },
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun SaveProgressCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Ваши данные не потеряются и вы сможете играть на нескольких устройствах",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { }) {
                Text("Сохранить прогресс")
            }
        }
    }
}

@Composable
private fun AchievementsCard() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Достижения", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AchievementSlot()
                AchievementSlot()
                AchievementSlot()
            }
        }
    }
}

@Composable
private fun RowScope.AchievementSlot() {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(84.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("+")
    }
}

@Composable
private fun StatisticsCard() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Статистика", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatCard(title = "Сложная", value = "Любимая сложность")
                MiniStatCard(title = "251", value = "Игр проведено")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatCard(title = "58%", value = "Процент побед")
                MiniStatCard(title = "Улучшить", value = "Повысить ранг")
            }
        }
    }
}

@Composable
private fun RowScope.MiniStatCard(
    title: String,
    value: String
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun DeveloperBindingsCard(
    showBindings: Boolean,
    onToggleShow: () -> Unit,
    googlePlayId: String,
    appStoreId: String,
    telegramId: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Developer zone",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(onClick = onToggleShow)
            )
            Text(
                text = if (showBindings) {
                    "Скрыть данные привязок"
                } else {
                    "Показать данные привязок"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(onClick = onToggleShow)
            )

            if (showBindings) {
                Text("Google Play ID: ${googlePlayId.ifBlank { "не задан" }}")
                Text("App Store ID: ${appStoreId.ifBlank { "не задан" }}")
                Text("Telegram ID: ${telegramId.ifBlank { "не задан" }}")
            }
        }
    }
}
