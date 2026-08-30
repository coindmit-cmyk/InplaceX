package com.mirkori.inplacex.ui.screens.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.R
import com.mirkori.inplacex.core.model.GameModeDefinition
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.social.FriendsReferenceStyle
import com.mirkori.inplacex.ui.screens.social.IllustratedSurface

internal enum class ReferenceSetupKind {
    RACE,
    DUEL,
}

@Composable
internal fun ReferenceHomeSelectionScreen(
    pveMode: GameModeDefinition,
    pvpMode: GameModeDefinition,
    onOpenPve: () -> Unit,
    onOpenPvp: () -> Unit,
    onOpenCompany: () -> Unit,
) {
    val strings = LocalAppStrings.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Inplace")
                    withStyle(SpanStyle(color = Color(0xFFFFC928))) { append("X") }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(scaleX = .94f, scaleY = 1.26f)
                    .offset(y = 6.dp)
                    .semantics { heading() }
                    .testTag("reference-home-logo"),
                color = Color.White,
                fontSize = if (compact) 42.sp else 50.sp,
                lineHeight = if (compact) 48.sp else 56.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (compact) 7.dp else 10.dp))
            Text(
                text = strings.text("home.subtitle"),
                modifier = Modifier.fillMaxWidth().offset(y = 3.dp),
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (compact) 20.dp else 32.dp))
            ReferenceHomeModeCard(
                title = strings.text(pveMode.titleKey),
                subtitle = strings.text(pveMode.subtitleKey),
                art = R.drawable.art_stopwatch_v11,
                artScale = 1.35f,
                colors = listOf(Color(0xFFFFBE2B), Color(0xFFF19A06), Color(0xFFD77A02)),
                contentColor = Color(0xFF4B2609),
                height = if (compact) 116.dp else 126.dp,
                testTag = "reference-home-race",
                onClick = onOpenPve,
            )
            Spacer(Modifier.height(12.dp))
            ReferenceHomeModeCard(
                title = strings.text(pvpMode.titleKey),
                subtitle = strings.text(pvpMode.subtitleKey),
                art = R.drawable.art_duel_crest_v11,
                artScale = 1.17f,
                colors = listOf(Color(0xFF8C50C5), Color(0xFF6731A3), Color(0xFF432276)),
                contentColor = Color.White,
                height = if (compact) 116.dp else 127.dp,
                testTag = "reference-home-duel",
                onClick = onOpenPvp,
            )
            Spacer(Modifier.height(12.dp))
            ReferenceHomeModeCard(
                title = strings.text("home.company.continue"),
                subtitle = strings.text("home.company.teaser"),
                art = R.drawable.art_company_shield_v11,
                artScale = 1.30f,
                colors = listOf(Color(0xFF91BD35), Color(0xFF679A22), Color(0xFF3E7415)),
                contentColor = Color.White,
                height = if (compact) 116.dp else 126.dp,
                testTag = "reference-home-company",
                onClick = onOpenCompany,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReferenceHomeModeCard(
    title: String,
    subtitle: String,
    @DrawableRes art: Int,
    artScale: Float,
    colors: List<Color>,
    contentColor: Color,
    height: Dp,
    testTag: String,
    onClick: () -> Unit,
) {
    IllustratedSurface(
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onClick),
        rim = Color(0xFFFFD24D),
        radius = 19.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(art),
                contentDescription = null,
                modifier = Modifier
                    .size(82.dp)
                    .graphicsLayer(scaleX = artScale, scaleY = artScale),
                contentScale = ContentScale.Fit,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    color = contentColor,
                    fontSize = 28.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = contentColor.copy(alpha = .96f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                modifier = Modifier.size(38.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.Black.copy(alpha = .20f),
                contentColor = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .58f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(27.dp))
                }
            }
        }
    }
}

@Composable
internal fun ReferenceModeSetupScreen(
    kind: ReferenceSetupKind,
    codeLength: Int,
    onCodeLengthChange: (Int) -> Unit,
    onPlayLocal: () -> Unit,
    onPlayOnline: () -> Unit,
    onlineAvailable: Boolean,
    onBack: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val race = kind == ReferenceSetupKind.RACE
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(top = if (race) 11.dp else 13.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (race) 5.dp else 6.dp),
        ) {
            ReferenceSetupHero(kind = kind, compact = compact)
            IllustratedSurface(
                colors = FriendsReferenceStyle.Cream,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (compact) {
                            Modifier.heightIn(min = if (race) 457.dp else 451.dp)
                        } else {
                            Modifier.height(if (race) 457.dp else 451.dp)
                        },
                    )
                    .testTag(if (race) "reference-race-setup" else "reference-duel-setup"),
                rim = Color(0xFFD49842),
                radius = 20.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(top = 17.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = strings.text("game.race_setup.code_length"),
                        color = FriendsReferenceStyle.Ink,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReferenceStepperButton(
                            label = "−",
                            enabled = codeLength > MinimumHomeCodeLength,
                            testTag = "reference-code-minus",
                            onClick = { onCodeLengthChange(selectHomeCodeLength(codeLength - 1)) },
                        )
                        Text(
                            text = strings.homeCodeLength(codeLength),
                            modifier = Modifier.width(122.dp),
                            color = FriendsReferenceStyle.Ink,
                            fontSize = 22.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                        )
                        ReferenceStepperButton(
                            label = "+",
                            enabled = codeLength < MaximumHomeCodeLength,
                            testTag = "reference-code-plus",
                            onClick = { onCodeLengthChange(selectHomeCodeLength(codeLength + 1)) },
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = strings.text("reference.setup.duplicates"),
                        color = FriendsReferenceStyle.Ink.copy(alpha = .82f),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (race) {
                        Spacer(Modifier.height(12.dp))
                        ReferenceRaceOnlineNotice()
                        Spacer(Modifier.height(10.dp))
                        ReferenceSetupAction(
                            title = strings.text("reference.race.quick"),
                            subtitle = strings.text("reference.race.quick.subtitle"),
                            art = R.drawable.art_race_flag_v11,
                            primary = true,
                            enabled = onlineAvailable,
                            height = 80.dp,
                            testTag = "reference-race-online",
                            onClick = onPlayOnline,
                        )
                        Spacer(Modifier.height(10.dp))
                        ReferenceSetupAction(
                            title = strings.text("reference.race.training"),
                            subtitle = strings.text("reference.race.training.subtitle"),
                            art = R.drawable.art_training_target_v11,
                            enabled = true,
                            height = 72.dp,
                            testTag = "reference-race-local",
                            onClick = onPlayLocal,
                        )
                        Spacer(Modifier.height(5.dp))
                        ReferenceSetupAction(
                            title = strings.text("reference.race.records"),
                            subtitle = strings.text("reference.race.records.unavailable"),
                            art = R.drawable.art_records_podium_v11,
                            artScale = 1.12f,
                            enabled = false,
                            height = 72.dp,
                            testTag = "reference-race-records",
                            onClick = {},
                        )
                    } else {
                        Spacer(Modifier.height(27.dp))
                        ReferenceSetupAction(
                            title = strings.text("home.pvp.bot"),
                            subtitle = strings.text("reference.duel.bot.subtitle"),
                            art = R.drawable.art_friend_bot_v11,
                            artScale = 1.55f,
                            primary = true,
                            enabled = true,
                            height = 100.dp,
                            testTag = "reference-duel-bot",
                            onClick = onPlayLocal,
                        )
                        Spacer(Modifier.height(10.dp))
                        ReferenceSetupAction(
                            title = strings.text("home.pvp.online"),
                            subtitle = strings.text("reference.duel.online.subtitle"),
                            art = R.drawable.art_online_globe_v11,
                            artScale = 1.55f,
                            enabled = onlineAvailable,
                            height = 88.dp,
                            testTag = "reference-duel-online",
                            onClick = onPlayOnline,
                        )
                        Spacer(Modifier.height(10.dp))
                        ReferenceSetupAction(
                            title = strings.text("top.back"),
                            subtitle = strings.text("reference.setup.back.subtitle"),
                            vectorBack = true,
                            enabled = true,
                            height = 78.dp,
                            testTag = "reference-duel-back",
                            onClick = onBack,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceSetupHero(kind: ReferenceSetupKind, compact: Boolean) {
    val strings = LocalAppStrings.current
    val race = kind == ReferenceSetupKind.RACE
    IllustratedSurface(
        colors = if (race) {
            listOf(Color(0xFFFFC347), Color(0xFFFFA819), Color(0xFFF18A05))
        } else {
            listOf(Color(0xFF8044B4), Color(0xFF5E2E95), Color(0xFF44236F))
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (compact) {
                    Modifier.heightIn(min = if (race) 182.dp else 185.dp)
                } else {
                    Modifier.height(if (race) 193.dp else 196.dp)
                },
            )
            .testTag(if (race) "reference-race-hero" else "reference-duel-hero"),
        rim = Color(0xFFFFD34D),
        radius = 20.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(if (race) R.drawable.art_stopwatch_v11 else R.drawable.art_duel_crest_v11),
                contentDescription = null,
                modifier = Modifier
                    .size(if (compact) 116.dp else 132.dp)
                    .graphicsLayer(
                        scaleX = if (race) 1.25f else 1.15f,
                        scaleY = if (race) 1.25f else 1.15f,
                    ),
                contentScale = ContentScale.Fit,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.text(if (race) "reference.race.title" else "reference.duel.title"),
                    modifier = Modifier.semantics { heading() },
                    color = if (race) Color(0xFF4A2506) else Color.White,
                    fontSize = if (compact) 29.sp else 34.sp,
                    lineHeight = if (compact) 33.sp else 38.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = strings.text(if (race) "reference.race.subtitle" else "reference.duel.subtitle"),
                    color = if (race) Color(0xFF4A2506) else Color.White,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReferenceStepperButton(
    label: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(width = 48.dp, height = 52.dp)
            .testTag(testTag),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = Color(0xFFFFF0D5),
        contentColor = FriendsReferenceStyle.Ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3A94D)),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 23.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ReferenceSetupAction(
    title: String,
    subtitle: String,
    @DrawableRes art: Int? = null,
    artScale: Float = 1.3f,
    vectorBack: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean,
    height: Dp,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = if (primary) {
        listOf(Color(0xFF2488EA), Color(0xFF1265C5), Color(0xFF07418D))
    } else {
        listOf(Color(0xFFFFF3DB), Color(0xFFFCE7BC), Color(0xFFF3D59C))
    }
    IllustratedSurface(
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .testTag(testTag)
            .semantics { if (!enabled) disabled() }
            .alpha(if (enabled) 1f else .62f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        rim = if (primary) Color(0xFFFFC443) else Color(0xFFE1B56D),
        radius = 15.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                vectorBack -> Surface(
                    modifier = Modifier.size(58.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color(0xFFFFEAC0),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3B66B)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = FriendsReferenceStyle.Ink,
                        )
                    }
                }
                art != null -> Image(
                    painter = painterResource(art),
                    contentDescription = null,
                    modifier = Modifier
                        .size(58.dp)
                        .graphicsLayer(scaleX = artScale, scaleY = artScale),
                    contentScale = ContentScale.Fit,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = if (primary) Color.White else FriendsReferenceStyle.Ink,
                    fontSize = 21.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = if (primary) Color.White.copy(alpha = .94f) else FriendsReferenceStyle.Ink.copy(alpha = .82f),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReferenceRaceOnlineNotice() {
    val strings = LocalAppStrings.current
    IllustratedSurface(
        colors = listOf(Color(0xFFFFE8AD), Color(0xFFFFD77A)),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag("reference-race-energy"),
        rim = Color(0xFFFFAF24),
        radius = 13.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(27.dp),
                tint = Color(0xFF0784D6),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    strings.text("reference.race.energy.title"),
                    color = FriendsReferenceStyle.Ink,
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    strings.text("reference.race.energy.subtitle"),
                    color = FriendsReferenceStyle.Ink.copy(alpha = .82f),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}
