package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.requiredSize
import com.mirkori.inplacex.R
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType

@Composable
internal fun CompanyScreenHeader(
    strings: LocalizationProvider,
    compact: Boolean,
    retentionRewardAvailable: Boolean,
    onRetentionRewards: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("company-hero"),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color(0xFF75B8C9)),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF12677B), PageColors.ChromeDark),
                    ),
                )
                .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onHistory,
                modifier = Modifier
                    .size(if (compact) 48.dp else 52.dp)
                    .semantics { contentDescription = strings.text("company.scene.history") }
                    .testTag("company-history"),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.art_company_badge_v10),
                        contentDescription = null,
                        modifier = Modifier.requiredSize(if (compact) 58.dp else 64.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp, end = 2.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = strings.text("company.title"),
                    style = if (compact) PageType.CardTitle else PageType.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.text("company.scene.subtitle"),
                    style = if (compact) {
                        PageType.Secondary.copy(fontSize = 11.sp, lineHeight = 14.sp)
                    } else {
                        PageType.Secondary
                    },
                    color = Color.White.copy(alpha = 0.94f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HeaderAction(
                icon = Icons.Outlined.CardGiftcard,
                description = strings.text("company.retention.action"),
                tint = if (retentionRewardAvailable) Color(0xFFFFD36B) else Color.White,
                onClick = onRetentionRewards,
                modifier = Modifier.testTag("company-retention-rewards"),
            )
        }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun CompanyChapterCard(
    strings: LocalizationProvider,
    chapter: Int,
    totalStars: Int,
    requiredStars: Int,
    nextBlockLocked: Boolean,
    rewardAvailable: Boolean,
    rewardClaimed: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewardClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val target = requiredStars.coerceAtLeast(1)
    val progress = (totalStars.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    val rewardLabel = strings.text(
        when {
            rewardClaimed -> "company.chapter.reward.claimed"
            rewardAvailable -> "company.chapter.reward.available"
            else -> "company.chapter.reward"
        },
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("company-chapter-card"),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = PageColors.Cream,
            contentColor = PageColors.Text,
            border = BorderStroke(1.dp, PageColors.Border),
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = if (compact) 72.dp else 90.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = if (compact) 10.dp else 20.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                if (canGoPrevious) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("company-previous-chapter"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronLeft,
                            contentDescription = strings.text("company.chapter.previous"),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = chapterHeading(
                            chapter = chapter,
                            chapterNumber = sentenceCase(
                                strings.text("company.chapter.number")
                                    .replace("{value}", chapter.toString()),
                            ),
                            firstChapterTitle = strings.text("company.chapter.first_title"),
                        ),
                        style = PageType.Body.copy(
                            fontSize = if (compact) 15.sp else 17.sp,
                            lineHeight = if (compact) 18.sp else 21.sp,
                        ),
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                stateDescription = strings.text(
                                    if (nextBlockLocked) {
                                        "company.scene.locked"
                                    } else {
                                        "company.scene.unlocked"
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .weight(1f)
                                .size(width = 1.dp, height = 8.dp),
                            color = PageColors.Success,
                            trackColor = PageColors.CreamSecondary,
                        )
                        Text(
                            text = strings.text("company.chapter.progress")
                                .replace("{current}", totalStars.toString())
                                .replace("{required}", requiredStars.toString()),
                            style = PageType.Secondary.copy(
                                fontSize = if (compact) 9.sp else 10.sp,
                                lineHeight = if (compact) 11.sp else 12.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                if (canGoNext) {
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("company-next-chapter"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = strings.text("company.chapter.next"),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                }
            }
        }

        Surface(
            onClick = onRewardClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-5).dp, y = if (compact) 9.dp else 14.dp)
                .requiredSize(
                    width = if (compact) 62.dp else 80.dp,
                    height = if (compact) 84.dp else 100.dp,
                )
                .testTag("company-chapter-reward"),
            shape = RoundedCornerShape(14.dp),
            color = PageColors.CreamSecondary,
            contentColor = PageColors.Shop,
            border = BorderStroke(
                1.dp,
                if (rewardAvailable && !rewardClaimed) Color(0xFFE19A19) else PageColors.Border,
            ),
        ) {
            Column(
                modifier = Modifier
                    .offset(y = (-6).dp)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Image(
                    painter = painterResource(R.drawable.art_chapter_chest_v10),
                    contentDescription = rewardLabel,
                    modifier = Modifier.size(if (compact) 48.dp else 64.dp),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    text = rewardLabel,
                    style = PageType.Secondary.copy(
                        fontSize = if (compact) 9.sp else 10.sp,
                        lineHeight = if (compact) 10.sp else 11.sp,
                    ),
                    color = PageColors.Text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun sentenceCase(value: String): String = value
    .lowercase()
    .replaceFirstChar { character -> character.titlecase() }

private fun chapterHeading(chapter: Int, chapterNumber: String, firstChapterTitle: String): String =
    if (chapter == 1) "$chapterNumber. $firstChapterTitle" else chapterNumber
