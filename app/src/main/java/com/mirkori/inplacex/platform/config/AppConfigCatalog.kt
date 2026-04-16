package com.mirkori.inplacex.platform.config

import androidx.compose.ui.graphics.Color
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.match.OpponentKind
import com.mirkori.inplacex.core.match.PreMatchConfig
import com.mirkori.inplacex.core.match.SecretSetupKind
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameModeFamily
import com.mirkori.inplacex.core.model.GameModeDefinition
import com.mirkori.inplacex.ui.background.ScreenBackgroundPreset

object AppConfigCatalog {
    private val campaignLevelOne = CampaignLevelGenerator.generate(levelNumber = 1)

    val branding = BrandingConfig(
        appName = "InplaceX",
        marketingTitle = "InplaceX",
        defaultLanguageTag = "ru",
    )

    val platformConfig = PlatformConfig(
        navigationItems = listOf(
            SectionSpec("home", "section.home.title", "section.home.short", "section.home.reserve"),
            SectionSpec("social", "section.social.title", "section.social.short", "section.social.reserve"),
            SectionSpec("company", "section.company.title", "section.company.short", "section.company.reserve"),
            SectionSpec("shop", "section.shop.title", "section.shop.short", "section.shop.reserve"),
            SectionSpec("profile", "section.profile.title", "section.profile.short", "section.profile.reserve"),
        ),
        shellAppearance = ShellAppearanceConfig(
            appBackground = LayerBackgroundConfig(
                useOwnBackground = true,
                imageAssetPath = "image/background/app_bg.png",
                preset = ScreenBackgroundPreset.DefaultBlue,
                solidColor = Color(0xFF4C6FFF),
            ),
            topBar = TopBarAppearanceConfig(
                container = LayerBackgroundConfig(
                    useOwnBackground = true,
                    imageAssetPath = "image/background/top_bg.png",
                    solidColor = Color(0xFFF6F7FF),
                ),
                backIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_back.svg",
                    tintColor = Color(0xFF39415E),
                ),
                energyIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_energy.svg",
                    tintColor = Color(0xFF4C6FFF),
                ),
                coinsIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_coins.svg",
                    tintColor = Color(0xFFCC8A00),
                ),
                settingsIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_settings.svg",
                    tintColor = Color(0xFF39415E),
                ),
            ),
            centerSurface = LayerBackgroundConfig(
                useOwnBackground = true,
                imageAssetPath = "image/background/center_bg.png",
                solidColor = Color(0xFFFDFDFF),
            ),
            bottomBar = LayerBackgroundConfig(
                useOwnBackground = true,
                imageAssetPath = "image/background/bottom_bg.png",
                solidColor = Color(0xFFF2F4FF),
            ),
        )
    )

    val gameModes = listOf(
        GameModeDefinition(
            id = "pve_race",
            titleKey = "mode.pve.title",
            subtitleKey = "mode.pve.subtitle",
            family = GameModeFamily.RACE,
            config = GameConfig(
                codeLength = 6,
                allowDuplicates = true,
                attemptLimit = 12,
            ),
            opponentKind = OpponentKind.BOT,
            hintsEnabled = true,
        ),
        GameModeDefinition(
            id = "pvp_bot_duel",
            titleKey = "mode.pvp.title",
            subtitleKey = "mode.pvp.subtitle",
            family = GameModeFamily.DUEL,
            config = GameConfig(
                codeLength = 6,
                allowDuplicates = true,
                attemptLimit = 12,
                turnTimeLimitSeconds = 30,
            ),
            opponentKind = OpponentKind.BOT,
            hintsEnabled = false,
            turnTimeLimitSeconds = 30,
            preMatchConfig = PreMatchConfig(
                secretSelectionTimeoutSeconds = 60,
                devBotSecretDelaySeconds = 5,
                secretSetupKind = SecretSetupKind.PLAYER_SELECTED,
            ),
            botDifficulty = BotDifficulty.MEDIUM,
        ),
        GameModeDefinition(
            id = "company_race",
            titleKey = "section.company.title",
            subtitleKey = "section.company.reserve",
            family = GameModeFamily.CAMPAIGN_RACE,
            config = GameConfig(
                codeLength = campaignLevelOne.config.codeLength,
                allowDuplicates = true,
                attemptLimit = campaignLevelOne.config.attemptLimit,
            ),
            opponentKind = OpponentKind.BOT,
            hintsEnabled = true,
            totalTimeLimitSeconds = campaignLevelOne.raceTimeLimitSeconds,
            campaignLevelNumber = campaignLevelOne.levelNumber,
        )
    )
}
