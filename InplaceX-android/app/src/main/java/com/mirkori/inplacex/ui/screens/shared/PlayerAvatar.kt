package com.mirkori.inplacex.ui.screens.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.mirkori.inplacex.ui.theme.InplaceXColors

data class PlayerAvatarPreset(
    val key: String,
    val symbol: String,
)

val PlayerAvatarPresets = listOf(
    PlayerAvatarPreset("rocket", "▲"),
    PlayerAvatarPreset("robot", "AI"),
    PlayerAvatarPreset("star", "★"),
    PlayerAvatarPreset("gamepad", "+"),
    PlayerAvatarPreset("heart", "♥"),
    PlayerAvatarPreset("bolt", "ϟ"),
)

@Composable
fun PlayerAvatar(
    displayName: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val preset = PlayerAvatarPresets.firstOrNull { avatarUrl?.endsWith("/${it.key}") == true }
    Surface(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = avatarColor(preset?.key),
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = preset?.symbol ?: playerInitials(displayName),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun avatarColor(key: String?): Color = when (key) {
    "rocket" -> InplaceXColors.Cobalt
    "robot" -> InplaceXColors.ToyPurple
    "star" -> InplaceXColors.ToyOrange
    "gamepad" -> InplaceXColors.Cyan
    "heart" -> InplaceXColors.Coral
    "bolt" -> InplaceXColors.Mint
    else -> InplaceXColors.Cobalt
}

private fun playerInitials(displayName: String): String = displayName
    .trim()
    .split(Regex("\\s+|_+"))
    .filter(String::isNotBlank)
    .let { parts ->
        when {
            parts.isEmpty() -> "IX"
            parts.size == 1 -> parts.first().take(2).uppercase()
            else -> "${parts.first().first()}${parts.last().first()}".uppercase()
        }
    }
