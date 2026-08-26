package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.R
import kotlin.math.roundToInt

internal object FriendsReferenceStyle {
    val Ink = Color(0xFF38200D)
    val Cream = listOf(Color(0xFFFFEED0), Color(0xFFF9E6BD), Color(0xFFF5DCAB))
    val Purple = listOf(Color(0xFF8654CA), Color(0xFF563294), Color(0xFF301B5B))
    val Blue = listOf(Color(0xFF227CCA), Color(0xFF11528C), Color(0xFF08263F))
    val Green = listOf(Color(0xFF5C852A), Color(0xFF365D1E), Color(0xFF1B3D16))
    val Chrome = listOf(Color(0xFF214563), Color(0xFF142E47), Color(0xFF0B1D30))
    val Border = Color(0xFFC28B43)
    val LightRim = Color(0xFFFCE9C5)
    val WhiteShadow = Shadow(Color(0xCC241408), Offset(0f, 2f), 3f)
    val Title = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 35.sp, lineHeight = 40.sp, color = Color.White, shadow = WhiteShadow,
    )
    val CardTitle = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 21.sp, color = Ink,
    )
    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 19.sp, color = Ink,
    )
    val Small = Body.copy(fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
internal fun IllustratedSurface(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    rim: Color = FriendsReferenceStyle.Border,
    radius: Dp = 17.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .shadow(4.dp, shape, ambientColor = Color(0xFF3A1A07), spotColor = Color(0xFF3A1A07))
            .clip(shape)
            .background(Brush.verticalGradient(colors))
            .border(1.dp, rim, shape)
            .drawWithCache {
                val inset = 2.dp.toPx()
                val innerSize = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
                val innerRadius = CornerRadius((radius.toPx() - inset).coerceAtLeast(0f))
                val edge = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = .68f), Color.White.copy(alpha = .06f), Color.Black.copy(alpha = .25f)),
                )
                onDrawWithContent {
                    // Фактура и ободок рисуются в координатах панели, без растяжения текста.
                    repeat(70) { i ->
                        val x = ((i * 83 + 17) % 997) / 997f * size.width
                        val y = ((i * 157 + 31) % 991) / 991f * size.height
                        drawCircle(Color.White.copy(alpha = .025f), 1.dp.toPx(), Offset(x, y))
                    }
                    drawRoundRect(edge, Offset(inset, inset), innerSize, innerRadius, style = Stroke(1.dp.toPx()))
                    drawContent()
                }
            },
        content = content,
    )
}

internal enum class FriendsArt(val column: Int, val row: Int) {
    BOY(0, 0), PURPLE_HAIR(1, 0), CAP(2, 0), GIRL(3, 0),
    INVITE(0, 1), SWORDS(1, 1), ROBOT(2, 1), ENVELOPE(3, 1),
}

@Composable
internal fun FriendsIllustration(art: FriendsArt, modifier: Modifier = Modifier) {
    val atlas = ImageBitmap.imageResource(R.drawable.friends_art_atlas_v8)
    val painter = remember(atlas, art) {
        val cellWidth = atlas.width / 4f
        val cellHeight = atlas.height / 2f
        val inset = .06f
        val left = (cellWidth * (art.column + inset)).roundToInt()
        val top = (cellHeight * (art.row + inset)).roundToInt()
        BitmapPainter(
            image = atlas,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize((cellWidth * (1 - 2 * inset)).roundToInt(), (cellHeight * (1 - 2 * inset)).roundToInt()),
        )
    }
    Image(painter, contentDescription = null,
        modifier = modifier.clip(if (art.row == 0) CircleShape else RoundedCornerShape(10.dp)))
}
