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
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
import com.mirkori.inplacex.ui.screens.shared.ContentCard
import com.mirkori.inplacex.ui.screens.shared.PageHeroCard
import com.mirkori.inplacex.ui.screens.shared.PageSecondaryButton
import com.mirkori.inplacex.ui.screens.shared.PageStatusPill
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.LinearProgressIndicator

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
    PageHeroCard(
        title = strings.text("company.title"),
        subtitle = strings.text("company.scene.subtitle"),
        accent = PageColors.Company,
        icon = Icons.Outlined.EmojiEvents,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageSecondaryButton(
                onClick = onRetentionRewards,
                modifier = Modifier.weight(1f).testTag("company-retention-rewards"),
            ) {
                Icon(Icons.Outlined.CardGiftcard, contentDescription = null,
                    tint = if (retentionRewardAvailable) PageColors.Text else PageColors.Primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.text("company.retention.action"))
            }
            PageSecondaryButton(onClick = onHistory, modifier = Modifier.weight(1f).testTag("company-history")) {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.text("company.scene.history"))
            }
        }
        if (chapterRewardLabel != null && onChapterReward != null) {
            PageSecondaryButton(onClick = onChapterReward, modifier = Modifier.fillMaxWidth().testTag("company-chapter-reward")) {
                Text(chapterRewardLabel)
            }
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
    ContentCard {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
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
                tint = PageColors.Text,
            )
        }
        Text(
            text = strings.text("company.chapter.number").replace("{value}", chapter.toString()),
            color = PageColors.Text,
            style = PageType.CardTitle,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.testTag("company-next-chapter"),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = strings.text("company.chapter.next"),
                tint = PageColors.Text,
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
    rewardAvailable: Boolean,
    rewardClaimed: Boolean,
    onRewardClick: () -> Unit,
    compact: Boolean,
) {
    val target = requiredStars.coerceAtLeast(1)
    val progress = (totalStars.toFloat() / target.toFloat()).coerceIn(0f, 1f)

    ContentCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Star, contentDescription = null, tint = PageColors.Text)
            Text(
                strings.text("company.chapter.progress")
                    .replace("{current}", totalStars.toString())
                    .replace("{required}", requiredStars.toString()),
                style = PageType.CardTitle,
                modifier = Modifier.weight(1f),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = PageColors.Company,
            trackColor = PageColors.CreamSecondary,
        )
        Text(
            strings.text(if (nextBlockLocked) "company.scene.locked" else "company.scene.unlocked"),
            style = PageType.Secondary, color = PageColors.TextSecondary,
        )
        PageSecondaryButton(
            onClick = onRewardClick,
            modifier = Modifier.fillMaxWidth().testTag("company-chapter-reward"),
        ) {
            Icon(Icons.Outlined.CardGiftcard, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(strings.text(when {
                rewardClaimed -> "company.chapter.reward.claimed"
                rewardAvailable -> "company.chapter.reward.available"
                else -> "company.chapter.reward"
            }))
        }
    }
}
