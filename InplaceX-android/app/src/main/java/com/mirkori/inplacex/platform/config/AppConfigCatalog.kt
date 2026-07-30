package com.mirkori.inplacex.platform.config

import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.match.OpponentKind
import com.mirkori.inplacex.core.match.PreMatchConfig
import com.mirkori.inplacex.core.match.SecretSetupKind
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameModeFamily
import com.mirkori.inplacex.core.model.GameModeDefinition
import com.mirkori.inplacex.ui.background.ScreenBackgroundPreset
import com.mirkori.inplacex.ui.theme.InplaceXColors

object AppConfigCatalog {
    private val campaignLevelOne = CampaignLevelGenerator.generate(levelNumber = 1)
    private val providerEnvironment = when (BuildConfig.PROVIDER_ENVIRONMENT.lowercase()) {
        "live" -> ProviderEnvironment.LIVE
        else -> ProviderEnvironment.SANDBOX
    }

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
        providers = ProviderConfig(
            environment = providerEnvironment,
            googlePlay = GooglePlayProviderConfig(
                webClientId = BuildConfig.GOOGLE_PLAY_WEB_CLIENT_ID,
                serverClientId = BuildConfig.GOOGLE_PLAY_SERVER_CLIENT_ID,
                gamesProjectId = BuildConfig.GOOGLE_PLAY_GAMES_PROJECT_ID,
            ),
            ads = AdsProviderConfig(
                admobAppId = BuildConfig.ADMOB_APP_ID,
                gameBannerAdUnitId = BuildConfig.ADMOB_GAME_BANNER_AD_UNIT_ID,
                rewardedAdUnitId = BuildConfig.ADMOB_REWARDED_AD_UNIT_ID,
                postMatchInterstitialAdUnitId = BuildConfig.ADMOB_POST_MATCH_INTERSTITIAL_AD_UNIT_ID,
            ),
            billing = BillingProviderConfig(
                removeAdsProductId = BuildConfig.BILLING_REMOVE_ADS_PRODUCT_ID,
                proSubscriptionId = BuildConfig.BILLING_PRO_SUBSCRIPTION_ID,
                proPlusSubscriptionId = BuildConfig.BILLING_PRO_PLUS_SUBSCRIPTION_ID,
            ),
        ),
        shellAppearance = ShellAppearanceConfig(
            appBackground = LayerBackgroundConfig(
                useOwnBackground = true,
                imageAssetPath = "image/background/app_bg.png",
                preset = ScreenBackgroundPreset.Dark,
                solidColor = InplaceXColors.Midnight,
            ),
            topBar = TopBarAppearanceConfig(
                container = LayerBackgroundConfig(
                    useOwnBackground = true,
                    imageAssetPath = "image/background/top_bg.png",
                    solidColor = InplaceXColors.Surface,
                ),
                backIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_back.svg",
                    tintColor = InplaceXColors.Ink,
                ),
                energyIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_energy.svg",
                    tintColor = InplaceXColors.Cyan,
                ),
                coinsIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_coins.svg",
                    tintColor = InplaceXColors.Amber,
                ),
                settingsIcon = IconAssetConfig(
                    imageAssetPath = "image/icon/top_settings.svg",
                    tintColor = InplaceXColors.Ink,
                ),
            ),
            centerSurface = LayerBackgroundConfig(
                useOwnBackground = true,
                imageAssetPath = "image/background/center_bg.png",
                solidColor = InplaceXColors.Surface,
            ),
            bottomBar = LayerBackgroundConfig(
                useOwnBackground = true,
                imageAssetPath = "image/background/bottom_bg.png",
                solidColor = InplaceXColors.MidnightElevated,
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
                maxConsecutiveDuplicateDigits = 3,
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
