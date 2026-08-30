package com.mirkori.inplacex.ui.screens.shared

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.R
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PlayerAvatarPreset(
    val key: String,
    val labelKey: String,
    val atlasColumn: Int? = null,
    val atlasRow: Int? = null,
    val drawableRes: Int? = null,
)

val PlayerAvatarPresets = listOf(
    PlayerAvatarPreset("rocket", "profile.mirkori.avatar.preset.explorer", atlasColumn = 0, atlasRow = 0),
    PlayerAvatarPreset("robot", "profile.mirkori.avatar.preset.robot", atlasColumn = 2, atlasRow = 1),
    PlayerAvatarPreset("star", "profile.mirkori.avatar.preset.dreamer", atlasColumn = 1, atlasRow = 0),
    PlayerAvatarPreset("gamepad", "profile.mirkori.avatar.preset.champion", atlasColumn = 2, atlasRow = 0),
    PlayerAvatarPreset("heart", "profile.mirkori.avatar.preset.friend", atlasColumn = 3, atlasRow = 0),
    PlayerAvatarPreset("bolt", "profile.mirkori.avatar.preset.hero", drawableRes = R.drawable.avatar_explorer_v7),
)

@Composable
fun PlayerAvatar(
    displayName: String,
    avatarUrl: String?,
    localAvatarPath: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val preset = PlayerAvatarPresets.firstOrNull { avatarUrl?.endsWith("/${it.key}") == true }
    val localAvatar by produceState<ImageBitmap?>(initialValue = null, key1 = localAvatarPath) {
        value = localAvatarPath?.let { versionedPath ->
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(versionedPath.substringBefore('?'))?.asImageBitmap()
            }
        }
    }
    Surface(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = avatarColor(preset?.key),
        contentColor = Color.White,
        border = BorderStroke(2.dp, Color(0xFFFFCA57)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                localAvatar != null -> Image(
                    bitmap = requireNotNull(localAvatar),
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize(),
                )
                preset != null -> PresetAvatarImage(preset, displayName)
                avatarUrl.isNullOrBlank() -> Image(
                    painter = painterResource(R.drawable.avatar_explorer_v7),
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Text(
                    text = playerInitials(displayName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PresetAvatarImage(preset: PlayerAvatarPreset, displayName: String) {
    val drawable = preset.drawableRes
    if (drawable != null) {
        Image(
            painter = painterResource(drawable),
            contentDescription = displayName,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val atlas = ImageBitmap.imageResource(R.drawable.friends_art_atlas_v8)
    val painter = remember(atlas, preset) {
        val cellWidth = atlas.width / 4f
        val cellHeight = atlas.height / 2f
        val inset = .06f
        BitmapPainter(
            image = atlas,
            srcOffset = IntOffset(
                (cellWidth * (requireNotNull(preset.atlasColumn) + inset)).roundToInt(),
                (cellHeight * (requireNotNull(preset.atlasRow) + inset)).roundToInt(),
            ),
            srcSize = IntSize(
                (cellWidth * (1 - 2 * inset)).roundToInt(),
                (cellHeight * (1 - 2 * inset)).roundToInt(),
            ),
        )
    }
    Image(
        painter = painter,
        contentDescription = displayName,
        modifier = Modifier.fillMaxSize(),
    )
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
