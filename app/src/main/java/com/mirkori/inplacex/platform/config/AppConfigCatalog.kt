package com.mirkori.inplacex.platform.config

import com.mirkori.inplacex.core.match.OpponentKind
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameModeDefinition

object AppConfigCatalog {
    val branding = BrandingConfig(
        appName = "InplaceX",
        marketingTitle = "InplaceX",
        defaultLanguageTag = "ru",
    )

    val platformConfig = PlatformConfig(
        navigationItems = listOf(
            SectionSpec("home", "section.home.title", "section.home.short", "section.home.reserve"),
            SectionSpec("social", "section.social.title", "section.social.short", "section.social.reserve"),
            SectionSpec("tournaments", "section.tournaments.title", "section.tournaments.short", "section.tournaments.reserve"),
            SectionSpec("shop", "section.shop.title", "section.shop.short", "section.shop.reserve"),
            SectionSpec("profile", "section.profile.title", "section.profile.short", "section.profile.reserve"),
        )
    )

    val gameModes = listOf(
        GameModeDefinition(
            id = "pve_race",
            titleKey = "mode.pve.title",
            subtitleKey = "mode.pve.subtitle",
            config = GameConfig(
                codeLength = 6,
                allowDuplicates = true,
                attemptLimit = 12,
            ),
            opponentKind = OpponentKind.BOT,
            hintsEnabled = true,
        ),
        GameModeDefinition(
            id = "pvp_duel",
            titleKey = "mode.pvp.title",
            subtitleKey = "mode.pvp.subtitle",
            config = GameConfig(
                codeLength = 6,
                allowDuplicates = true,
                attemptLimit = 12,
                turnTimeLimitSeconds = 30,
            ),
            opponentKind = OpponentKind.LOCAL_HUMAN,
            hintsEnabled = false,
            turnTimeLimitSeconds = 30,
        )
    )
}
