package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.SceneBackdrop
import com.mirkori.inplacex.ui.screens.shared.SceneCard

@Composable
fun SocialRootScreen() {
    val strings = LocalAppStrings.current

    SceneBackdrop(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        topColor = Color(0xFFD9F0FF),
        bottomColor = Color(0xFFF9FCFF),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SceneCard {
                Text(
                    text = strings.text("social.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "This section will handle friends, invites and online presence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SceneActionTile(
                    title = strings.text("social.friends"),
                    subtitle = "Invite players and manage your list",
                    modifier = Modifier.weight(1f),
                    accentBrush = Brush.verticalGradient(listOf(Color(0xFF7BCFFF), Color(0xFF4B8BFF))),
                    onClick = {}
                )
                SceneActionTile(
                    title = strings.text("social.online"),
                    subtitle = "Prepare online rooms and live activity",
                    modifier = Modifier.weight(1f),
                    accentBrush = Brush.verticalGradient(listOf(Color(0xFF7ADDB2), Color(0xFF2BA67B))),
                    onClick = {}
                )
            }

            SceneCard {
                Text(
                    text = "Next online steps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Google Play sign-in")
                Text("Cloud progress sync")
                Text("Friends, invites and PvP rooms")
            }
        }
    }
}
