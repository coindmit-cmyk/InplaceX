package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun AppBottomAd(
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("game-banner-slot"),
        shape = RoundedCornerShape(20.dp),
        color = InplaceXColors.ToyPurple,
        contentColor = Color.White,
        border = BorderStroke(2.dp, InplaceXColors.ToyPurpleTop),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = InplaceXColors.ToyOrange,
                contentColor = Color.White,
            ) {
                Text(
                    text = "AD",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                )
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (content == null) {
                    Text(
                        text = LocalAppStrings.current.text("game.ad_slot"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    content()
                }
            }
        }
    }
}
