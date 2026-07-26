package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun SocialRootScreen() {
    val strings = LocalAppStrings.current

    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true,
    ) {
        SceneCard(
            accentColor = InplaceXColors.ToyBlue.copy(alpha = 0.96f),
            contentColor = Color.White,
        ) {
            Text(
                text = strings.text("social.title"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = strings.text("social.hero.subtitle"),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.88f),
            )
            SocialAvailabilityBanner()
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 560.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneActionTile(
                        title = strings.text("social.friends"),
                        subtitle = strings.text("social.friends.empty"),
                        leadingIcon = Icons.Outlined.Group,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        accentBrush = Brush.verticalGradient(
                            listOf(InplaceXColors.ToyPurpleTop, InplaceXColors.ToyPurple),
                        ),
                        onClick = {},
                    )
                    SceneActionTile(
                        title = strings.text("social.invites"),
                        subtitle = strings.text("social.invites.empty"),
                        leadingIcon = Icons.Outlined.MailOutline,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        accentBrush = Brush.verticalGradient(
                            listOf(InplaceXColors.ToyOrangeTop, InplaceXColors.ToyOrange),
                        ),
                        onClick = {},
                    )
                    SceneActionTile(
                        title = strings.text("social.online.title"),
                        subtitle = strings.text("social.online.description"),
                        leadingIcon = Icons.Outlined.EmojiEvents,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        enabled = false,
                        accentBrush = Brush.verticalGradient(
                            listOf(InplaceXColors.ToyGreenTop, InplaceXColors.ToyGreen),
                        ),
                        onClick = {},
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SceneActionTile(
                        title = strings.text("social.friends"),
                        subtitle = strings.text("social.friends.empty"),
                        leadingIcon = Icons.Outlined.Group,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        accentBrush = Brush.verticalGradient(
                            listOf(InplaceXColors.ToyPurpleTop, InplaceXColors.ToyPurple),
                        ),
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    )
                    SceneActionTile(
                        title = strings.text("social.invites"),
                        subtitle = strings.text("social.invites.empty"),
                        leadingIcon = Icons.Outlined.MailOutline,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        accentBrush = Brush.verticalGradient(
                            listOf(InplaceXColors.ToyOrangeTop, InplaceXColors.ToyOrange),
                        ),
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    )
                    SceneActionTile(
                        title = strings.text("social.online.title"),
                        subtitle = strings.text("social.online.description"),
                        leadingIcon = Icons.Outlined.EmojiEvents,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        enabled = false,
                        accentBrush = Brush.verticalGradient(
                            listOf(InplaceXColors.ToyGreenTop, InplaceXColors.ToyGreen),
                        ),
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    )
                }
            }
        }

        SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.96f)) {
            Text(
                text = strings.text("social.online.title"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = strings.text("social.online.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.text("social.online.safety"),
                style = MaterialTheme.typography.bodySmall,
                color = InplaceXColors.InkMuted,
            )
        }
    }
}

@Composable
private fun SocialAvailabilityBanner() {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        color = InplaceXColors.SurfaceMuted,
        border = BorderStroke(1.dp, InplaceXColors.Cyan.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.WifiOff,
                contentDescription = null,
                tint = InplaceXColors.Cobalt,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = strings.text("social.status.preparing"),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = strings.text("social.status.preparing.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SocialEmptyCard(
    title: String,
    message: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    SceneCard(
        modifier = modifier,
        accentColor = InplaceXColors.ToyCream.copy(alpha = 0.94f),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = InplaceXColors.SurfaceMuted,
            contentColor = InplaceXColors.Cobalt,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
