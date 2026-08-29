package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.R
import com.mirkori.inplacex.core.campaign.CampaignDifficultyTier
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.PageType
import kotlin.math.roundToInt

/** Artwork stays decorative; route state and every hit target remain native Compose. */
@Composable
internal fun CompanyMapRoute(
    strings: LocalizationProvider,
    items: List<CampaignLevelListItem>,
    selectedLevel: Int,
    accessibleMaxLevel: Int,
    highestUnlockedLevel: Int,
    viewportHeight: Dp,
    compact: Boolean,
    onSelect: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val step = if (compact) 58.dp else 66.dp
    val topPadding = if (compact) 42.dp else 48.dp
    val bottomPadding = if (compact) 50.dp else 54.dp
    val routeAnchors = if (compact) {
        listOf(0, 82, 141, 191, 233)
    } else {
        listOf(0, 100, 170, 230, 280)
    }
    fun routeOffset(index: Int): Dp {
        val anchored = routeAnchors.getOrNull(index)
        return if (anchored != null) {
            anchored.dp
        } else {
            routeAnchors.last().dp + step * (index - routeAnchors.lastIndex)
        }
    }
    val contentHeight = maxOf(
        viewportHeight,
        topPadding + bottomPadding + routeOffset((items.size - 1).coerceAtLeast(0)),
    )
    val selectedIndex = items.indexOfFirst { it.definition.levelNumber == selectedLevel }
        .coerceAtLeast(0)
    val xPositions = listOf(.23f, .34f, .44f, .68f, .38f, .25f, .55f, .72f, .48f, .30f)

    LaunchedEffect(selectedLevel, viewportHeight, contentHeight, scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            val selectedY = contentHeight - bottomPadding - routeOffset(selectedIndex)
            val desiredOffset = selectedY - viewportHeight * 0.54f
            val targetPx = with(density) { desiredOffset.toPx().roundToInt() }
                .coerceIn(0, scrollState.maxValue)
            scrollState.scrollTo(targetPx)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(viewportHeight)
            .clip(RoundedCornerShape(18.dp))
            .testTag("company-forest-map"),
    ) {
        val mapWidth = maxWidth
        Image(
            painter = painterResource(R.drawable.campaign_forest_v10),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeight),
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    if (items.isNotEmpty()) {
                        val route = Path()
                        items.indices.forEach { index ->
                            val x = size.width * xPositions[index % xPositions.size]
                            val y = size.height - bottomPadding.toPx() - routeOffset(index).toPx()
                            if (index == 0) {
                                route.moveTo(x, y)
                            } else {
                                val previousX = size.width * xPositions[(index - 1) % xPositions.size]
                                val previousY = size.height - bottomPadding.toPx() -
                                    routeOffset(index - 1).toPx()
                                val middleY = (previousY + y) / 2f
                                route.cubicTo(previousX, middleY, x, middleY, x, y)
                            }
                        }
                        drawPath(
                            path = route,
                            color = Color(0xFF6B4A20),
                            style = Stroke(15.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawPath(
                            path = route,
                            color = Color(0xFFE5C277),
                            style = Stroke(11.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawPath(
                            path = route,
                            color = Color(0xFFFFE4A8),
                            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                items.forEachIndexed { index, item ->
                    val number = item.definition.levelNumber
                    val completed = item.progress.bestBackendRating > 0
                    val locked = !completed &&
                        (number > accessibleMaxLevel || number > highestUnlockedLevel)
                    val showLockIcon = locked && number > highestUnlockedLevel + 2
                    val isSelected = number == selectedLevel
                    val stateLabel = strings.text(
                        when {
                            locked -> "company.state.locked"
                            completed -> "company.state.completed"
                            else -> "company.scene.unlocked"
                        },
                    )
                    val nodeSize = if (isSelected) 52.dp else 44.dp
                    val centerY = contentHeight - bottomPadding - routeOffset(index)

                    Column(
                        modifier = Modifier
                            .offset(
                                x = mapWidth * xPositions[index % xPositions.size] - 32.dp,
                                y = centerY - nodeSize / 2,
                            )
                            .width(64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            onClick = { onSelect(number) },
                            modifier = Modifier
                                .size(nodeSize)
                                .testTag("company-level-$number")
                                .semantics {
                                    selected = isSelected
                                    stateDescription = stateLabel
                                    role = Role.Button
                                    contentDescription = strings.text("company.rules.title")
                                        .replace("{value}", number.toString())
                                },
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(
                                if (isSelected) 3.dp else 2.dp,
                                if (isSelected || completed) {
                                    Color(0xFFFFD35A)
                                } else {
                                    Color(0xFFF3E5BB)
                                },
                            ),
                            shadowElevation = if (isSelected) 8.dp else 3.dp,
                        ) {
                            Box(
                                modifier = Modifier.background(
                                    Brush.verticalGradient(
                                        if (locked) {
                                            listOf(Color(0xFF969381), Color(0xFF55574B))
                                        } else {
                                            listOf(Color(0xFF91C834), Color(0xFF386814))
                                        },
                                    ),
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (showLockIcon) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(if (isSelected) 25.dp else 21.dp)
                                            .testTag("company-level-lock-$number"),
                                    )
                                } else {
                                    Text(
                                        text = number.toString(),
                                        color = Color.White,
                                        fontSize = if (isSelected) 23.sp else 20.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.testTag("company-level-label-$number"),
                                    )
                                }
                            }
                        }
                        if (completed) {
                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                repeat(starsForRating(item.progress.bestBackendRating)) {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFD35A),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        items.firstOrNull { it.definition.levelNumber == selectedLevel }?.let { selectedItem ->
            val difficulty = strings.text(mapDifficultyKey(selectedItem.definition.difficultyTier))
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .width(if (compact) 72.dp else 92.dp)
                    .height(if (compact) 72.dp else 96.dp)
                    .semantics { contentDescription = difficulty },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xD92C551D),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color(0xFF9DD45A)),
                shadowElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (!compact) {
                        Text(
                            text = strings.text("company.difficulty.label"),
                            style = PageType.Secondary,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = difficulty,
                        style = PageType.Body.copy(fontSize = if (compact) 14.sp else 16.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC9FF76),
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.Outlined.Eco,
                        contentDescription = null,
                        modifier = Modifier.size(if (compact) 20.dp else 26.dp),
                        tint = Color(0xFF9BE34B),
                    )
                }
            }
        }
    }
}

private fun mapDifficultyKey(tier: CampaignDifficultyTier): String = when (tier) {
    CampaignDifficultyTier.EASY -> "company.difficulty.easy"
    CampaignDifficultyTier.MEDIUM -> "company.difficulty.medium"
    CampaignDifficultyTier.HARD -> "company.difficulty.hard"
    CampaignDifficultyTier.HARDCORE -> "company.difficulty.hardcore"
}
