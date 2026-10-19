package com.mirkori.inplacex.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.social.FriendsReferenceStyle
import com.mirkori.inplacex.ui.screens.social.IllustratedSurface
import com.mirkori.inplacex.ui.theme.FinalUiColors
import java.util.Locale

@Composable
internal fun HomeRecordsScreen(
    kind: ReferenceSetupKind,
    stats: ModeStats,
    onBack: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val resourceDensity = LocalContext.current.resources.displayMetrics.density
    val localDensity = LocalDensity.current.density
    val minimumBackHeight = kotlin.math.ceil(48f * resourceDensity) / localDensity
    val race = kind == ReferenceSetupKind.RACE
    val scopeKey = if (race) "home.records.scope.race" else "home.records.scope.duel"
    val zero = stats.wins == 0 && stats.losses == 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home-records-screen")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IllustratedSurface(
            colors = FriendsReferenceStyle.Cream,
            modifier = Modifier.fillMaxWidth(),
            rim = FriendsReferenceStyle.Border,
            radius = 20.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = strings.text("home.records.title"),
                    modifier = Modifier.fillMaxWidth().testTag("home-records-title").semantics { heading() },
                    color = if (race) FinalUiColors.ModeOrangeDeep else FriendsReferenceStyle.Purple[1],
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = strings.text(scopeKey),
                    modifier = Modifier.fillMaxWidth().testTag("home-records-scope"),
                    style = FriendsReferenceStyle.Body,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = String.format(Locale.ROOT, strings.text("home.records.wins"), stats.wins),
                    modifier = Modifier.fillMaxWidth().testTag("home-records-wins"),
                    style = FriendsReferenceStyle.CardTitle,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = String.format(Locale.ROOT, strings.text("home.records.losses"), stats.losses),
                    modifier = Modifier.fillMaxWidth().testTag("home-records-losses"),
                    style = FriendsReferenceStyle.CardTitle,
                    textAlign = TextAlign.Center,
                )
                if (zero) {
                    Text(
                        text = strings.text("home.records.empty"),
                        modifier = Modifier.fillMaxWidth().testTag("home-records-empty"),
                        style = FriendsReferenceStyle.Body,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = strings.text("home.records.unavailable"),
                    modifier = Modifier.fillMaxWidth().testTag("home-records-unavailable"),
                    style = FriendsReferenceStyle.Small,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Surface(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().heightIn(min = minimumBackHeight.dp).testTag("home-records-back"),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            color = FinalUiColors.Primary,
            contentColor = FinalUiColors.ChromeText,
        ) {
            Text(
                text = strings.text("top.back"),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
