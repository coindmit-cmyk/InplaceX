package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.mirkori.inplacex.R
import com.mirkori.inplacex.platform.localization.LocalizationProvider

/** Decoration has no game knowledge. Unlock state is supplied by the campaign policy. */
@Composable
internal fun CompanyMapRoute(strings: LocalizationProvider, items: List<CampaignLevelListItem>,
    selectedLevel: Int, accessibleMaxLevel: Int, highestUnlockedLevel: Int, onSelect: (Int) -> Unit,
) {
    val step = 86.dp
    val positions = listOf(.30f, .52f, .67f, .45f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(step * items.size + 32.dp)
        .clip(RoundedCornerShape(18.dp)).testTag("company-forest-map")) {
        Image(painterResource(R.drawable.campaign_forest_v7), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.matchParentSize()) {
            val path = Path()
            items.indices.forEach { index ->
                val x = size.width * positions[index % positions.size]
                val y = 44.dp.toPx() + step.toPx() * index
                if (index == 0) path.moveTo(x, y) else {
                    val previousX = size.width * positions[(index - 1) % positions.size]
                    val middleY = y - step.toPx() / 2
                    path.cubicTo(previousX, middleY, x, middleY, x, y)
                }
            }
            drawPath(path, Color(0xFF695025), style = Stroke(19.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, Color(0xFFE6C784), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, Color(0xFFF9DE9F), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
        }
        items.forEachIndexed { index, item ->
            val number = item.definition.levelNumber
            val completed = item.progress.bestBackendRating > 0
            val locked = !completed && (number > accessibleMaxLevel || number > highestUnlockedLevel)
            val selected = number == selectedLevel
            val stateLabel = strings.text(when {
                locked -> "company.state.locked"
                completed -> "company.state.completed"
                else -> "company.scene.unlocked"
            })
            Column(Modifier.offset(x = maxWidth * positions[index % positions.size] - 30.dp,
                y = step * index + 14.dp).width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(onClick = { onSelect(number) },
                    modifier = Modifier.size(60.dp).testTag("company-level-$number").semantics {
                        this.selected = selected
                        stateDescription = stateLabel
                        contentDescription = strings.text("company.rules.title").replace("{value}", number.toString())
                    }, shape = CircleShape, color = Color.Transparent,
                    border = BorderStroke(if (selected) 3.dp else 2.dp,
                        if (selected || completed) Color(0xFFFFD35A) else Color(0xFFF3E5BB)),
                    shadowElevation = if (selected) 7.dp else 3.dp,
                ) {
                    Box(Modifier.background(Brush.verticalGradient(if (locked)
                        listOf(Color(0xFF969381), Color(0xFF55574B)) else
                        listOf(Color(0xFF91C834), Color(0xFF386814)))), contentAlignment = Alignment.Center) {
                        Text(number.toString(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        if (locked) Icon(Icons.Outlined.Lock, null, tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(14.dp))
                    }
                }
                if (completed) Row {
                    repeat(starsForRating(item.progress.bestBackendRating)) {
                        Icon(Icons.Filled.Star, null, tint = Color(0xFFFFD35A), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
