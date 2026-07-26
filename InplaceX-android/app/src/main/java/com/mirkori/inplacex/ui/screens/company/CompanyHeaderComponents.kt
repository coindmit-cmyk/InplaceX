package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
internal fun CompanyScreenHeader(
    strings: LocalizationProvider,
    energy: Int,
    energyMax: Int,
    compact: Boolean,
    onHistory: () -> Unit,
    onBuyEnergy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.text("company.title"),
                style = if (compact) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = InplaceXColors.White,
                fontWeight = FontWeight.Bold,
            )
            if (!compact) {
                Text(
                    text = strings.text("company.scene.subtitle"),
                    style = MaterialTheme.typography.bodySmall,
                    color = InplaceXColors.SurfaceMuted,
                )
            }
        }

        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onHistory)
                .testTag("company-history"),
            shape = RoundedCornerShape(16.dp),
            color = InplaceXColors.NavySurface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, InplaceXColors.Cobalt.copy(alpha = 0.32f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = strings.text("company.scene.history"),
                    tint = InplaceXColors.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                if (!compact) {
                    Text(
                        text = strings.text("company.scene.history"),
                        color = InplaceXColors.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onBuyEnergy)
                .testTag("company-energy"),
            shape = RoundedCornerShape(16.dp),
            color = InplaceXColors.Cobalt.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, InplaceXColors.Cyan.copy(alpha = 0.42f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = strings.text("company.scene.energy"),
                    tint = InplaceXColors.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "$energy/$energyMax",
                    color = InplaceXColors.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun CompanyChapterHero(
    strings: LocalizationProvider,
    chapter: Int,
    totalStars: Int,
    requiredStars: Int,
    nextBlockLocked: Boolean,
    compact: Boolean,
) {
    val target = requiredStars.coerceAtLeast(1)
    val progress = (totalStars.toFloat() / target.toFloat()).coerceIn(0f, 1f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        color = InplaceXColors.MidnightElevated.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, InplaceXColors.Cobalt.copy(alpha = 0.44f)),
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            InplaceXColors.Cobalt.copy(alpha = 0.16f),
                            Color.Transparent,
                            InplaceXColors.Amber.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .padding(if (compact) 14.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.text("company.chapter.number")
                        .replace("{value}", chapter.toString()),
                    style = MaterialTheme.typography.labelLarge,
                    color = InplaceXColors.Cyan,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = strings.text("company.chapter.title"),
                    style = if (compact) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    color = InplaceXColors.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = InplaceXColors.Amber,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = strings.text("company.chapter.progress")
                            .replace("{current}", totalStars.toString())
                            .replace("{required}", requiredStars.toString()),
                        color = InplaceXColors.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(InplaceXColors.NavySurface),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(InplaceXColors.Amber, InplaceXColors.Cyan),
                                ),
                            ),
                    )
                }
                Text(
                    text = if (nextBlockLocked) {
                        strings.text("company.scene.locked")
                    } else {
                        strings.text("company.scene.unlocked")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = InplaceXColors.SurfaceMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    modifier = Modifier.size(if (compact) 52.dp else 64.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = InplaceXColors.Amber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, InplaceXColors.Amber.copy(alpha = 0.5f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = null,
                            tint = InplaceXColors.Amber,
                            modifier = Modifier.size(if (compact) 30.dp else 36.dp),
                        )
                    }
                }
                if (!compact) {
                    Text(
                        text = strings.text("company.chapter.reward"),
                        style = MaterialTheme.typography.labelSmall,
                        color = InplaceXColors.SurfaceMuted,
                    )
                }
            }
        }
    }
}
