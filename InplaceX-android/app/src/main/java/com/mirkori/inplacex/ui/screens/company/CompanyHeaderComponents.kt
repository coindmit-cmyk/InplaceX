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
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
internal fun CompanyScreenHeader(
    strings: LocalizationProvider,
    energy: Int,
    energyMax: Int,
    compact: Boolean,
    chapterRewardLabel: String? = null,
    onChapterReward: (() -> Unit)? = null,
    retentionRewardAvailable: Boolean,
    onRetentionRewards: () -> Unit,
    onHistory: () -> Unit,
    onBuyEnergy: () -> Unit,
) {
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .shadow(7.dp, RoundedCornerShape(17.dp)),
                shape = RoundedCornerShape(17.dp),
                color = InplaceXColors.ToyOrangeTop,
                border = BorderStroke(2.dp, Color(0xFFFFD959)),
                shadowElevation = 3.dp,
            ) {
                Text(
                    text = strings.text("company.title"),
                    color = InplaceXColors.ToyBrown,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
            HeaderIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.CardGiftcard,
                        contentDescription = strings.text("company.retention.title"),
                        tint = if (retentionRewardAvailable) {
                            InplaceXColors.ToyOrangeTop
                        } else {
                            Color.White
                        },
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onRetentionRewards,
                testTag = "company-retention-rewards",
            )
            HeaderIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = strings.text("company.scene.history"),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onHistory,
                testTag = "company-history",
            )
            if (chapterRewardLabel != null && onChapterReward != null) {
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CardGiftcard,
                            contentDescription = chapterRewardLabel,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = onChapterReward,
                    testTag = "company-chapter-reward",
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp),
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .shadow(9.dp, RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                color = InplaceXColors.ToyBlueDeep.copy(alpha = 0.96f),
                border = BorderStroke(2.dp, InplaceXColors.ToyCyan),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Inplace",
                        color = InplaceXColors.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = "X",
                        color = InplaceXColors.ToyOrangeTop,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CardGiftcard,
                            contentDescription = strings.text("company.retention.title"),
                            tint = if (retentionRewardAvailable) {
                                InplaceXColors.ToyOrangeTop
                            } else {
                                Color.White
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = onRetentionRewards,
                    testTag = "company-retention-rewards",
                )
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = strings.text("company.scene.history"),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = onHistory,
                    testTag = "company-history",
                )
            }
        }

        Surface(
            modifier = Modifier
                .shadow(9.dp, RoundedCornerShape(18.dp))
                .fillMaxWidth(if (compact) 0.68f else 0.58f),
            shape = RoundedCornerShape(18.dp),
            color = InplaceXColors.ToyOrangeTop,
            border = BorderStroke(3.dp, Color(0xFFFFD959)),
            shadowElevation = 4.dp,
        ) {
            Text(
                text = strings.text("company.title"),
                color = InplaceXColors.ToyBrown,
                style = if (compact) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = if (compact) 5.dp else 8.dp),
            )
        }
        Text(
            text = strings.text("company.scene.subtitle"),
            color = InplaceXColors.ToyBrown,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(17.dp),
        color = InplaceXColors.ToyBlueDeep,
        border = BorderStroke(2.dp, InplaceXColors.ToyCyan),
        shadowElevation = 5.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
internal fun CompanyChapterNavigator(
    strings: LocalizationProvider,
    chapter: Int,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.testTag("company-previous-chapter"),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronLeft,
                contentDescription = strings.text("company.chapter.previous"),
                tint = InplaceXColors.ToyBrown,
            )
        }
        Text(
            text = strings.text("company.chapter.number").replace("{value}", chapter.toString()),
            color = InplaceXColors.ToyBrown,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(132.dp),
        )
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.testTag("company-next-chapter"),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = strings.text("company.chapter.next"),
                tint = InplaceXColors.ToyBrown,
            )
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
    rewardAvailable: Boolean,
    rewardClaimed: Boolean,
    onRewardClick: () -> Unit,
    compact: Boolean,
) {
    val target = requiredStars.coerceAtLeast(1)
    val progress = (totalStars.toFloat() / target.toFloat()).coerceIn(0f, 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 76.dp else 88.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        CampaignSideBadge(
            modifier = Modifier.width(if (compact) 68.dp else 78.dp),
            background = Brush.verticalGradient(
                listOf(Color(0xFFFF7B00), Color(0xFFD63A00)),
            ),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = strings.text("company.chapter.number").replace("{value}", chapter.toString()),
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .shadow(7.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = InplaceXColors.ToyCream,
            border = BorderStroke(2.dp, InplaceXColors.ToyCreamShadow),
            shadowElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = InplaceXColors.ToyOrange,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = strings.text("company.chapter.progress")
                            .replace("{current}", totalStars.toString())
                            .replace("{required}", requiredStars.toString()),
                        color = InplaceXColors.ToyBrown,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(11.dp)
                        .background(InplaceXColors.ToyCreamShadow, CircleShape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(InplaceXColors.ToyGreenTop, InplaceXColors.ToyGreen),
                                ),
                                CircleShape,
                            ),
                    )
                }
                Text(
                    text = if (nextBlockLocked) {
                        strings.text("company.scene.locked")
                    } else {
                        strings.text("company.scene.unlocked")
                    },
                    color = InplaceXColors.ToyBrown.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        CampaignSideBadge(
            modifier = Modifier.width(if (compact) 68.dp else 78.dp),
            background = Brush.verticalGradient(
                listOf(InplaceXColors.ToyOrangeTop, InplaceXColors.ToyOrange),
            ),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.CardGiftcard,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = strings.text(
                when {
                    rewardClaimed -> "company.chapter.reward.claimed"
                    rewardAvailable -> "company.chapter.reward.available"
                    else -> "company.chapter.reward"
                },
            ),
            onClick = onRewardClick,
            testTag = "company-chapter-reward",
        )
    }
}

@Composable
private fun CampaignSideBadge(
    modifier: Modifier,
    background: Brush,
    icon: @Composable () -> Unit,
    label: String,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .shadow(6.dp, RoundedCornerShape(18.dp))
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics { role = Role.Button }
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.45f)),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .background(background)
                .padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon()
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
