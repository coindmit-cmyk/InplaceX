package com.mirkori.inplacex.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Контракт пяти страниц v6; плотная сетка игрового поля использует FinalUiTokens.
object PageColors {
    val Chrome = Color(0xFF173B5E)
    val ChromeDark = Color(0xFF092A49)
    val Primary = Color(0xFF0B6EDB)
    val PrimaryLight = Color(0xFF2C9AEE)
    val Cream = Color(0xFFFFF4DE)
    val CreamSecondary = Color(0xFFFFEBC7)
    val Border = Color(0xFFD8B36D)
    val Text = Color(0xFF3B2818)
    val TextSecondary = Color(0xFF786047)
    val Friends = FinalUiColors.ModePurple
    val Company = FinalUiColors.ModeOrange
    val Shop = FinalUiColors.ModeOrangeDeep
    val Profile = Primary
    val Success = Color(0xFF386B27)
    val Error = Color(0xFFAB2929)
}

object PageDimens {
    val Margin = 12.dp
    val Gap = 10.dp
    val HeroRadius = 18.dp
    val CardRadius = 16.dp
    val InnerRadius = 12.dp
    val ButtonRadius = 12.dp
    val PillRadius = 14.dp
    val Elevation = 4.dp
    val TouchTarget = 48.dp
}

object PageType {
    val Title = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
    val Subtitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val Section = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
    val CardTitle = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val Secondary = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val Button = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val Navigation = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
}
