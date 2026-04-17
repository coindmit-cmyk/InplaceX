package com.mirkori.inplacex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirkori.inplacex.data.local.GameProgressRepository
import com.mirkori.inplacex.data.local.BoostStockType
import com.mirkori.inplacex.data.local.GameModeStatType
import com.mirkori.inplacex.data.local.HintStockType
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.InterstitialPlacement
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.inplacex.platform.services.ProviderServicesFactory
import com.mirkori.inplacex.platform.services.RewardedPlacement
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.settings.SettingsRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.shell.AppTopBar
import com.mirkori.inplacex.ui.shell.AppShell
import com.mirkori.inplacex.ui.shell.BottomLayerMode
import com.mirkori.inplacex.ui.shell.DebugSecretAdSlot
import com.mirkori.inplacex.ui.shell.TopLayerMode
import com.mirkori.inplacex.ui.theme.InplaceXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableImmersiveFullscreen()

        setContent {
            InplaceXTheme {
                val progressRepository = remember { GameProgressRepository(applicationContext) }
                val providerServices = remember {
                    ProviderServicesFactory.create(
                        context = applicationContext,
                        platformConfig = AppConfigCatalog.platformConfig,
                    )
                }
                val adService = providerServices.adService
                val billingService = providerServices.billingService
                val authService = providerServices.authService
                var currentSection by rememberSaveable { mutableStateOf(AppSection.HOME) }
                var isInGame by rememberSaveable { mutableStateOf(false) }
                var requestExitGame by rememberSaveable { mutableStateOf(false) }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                var currentLanguageName by rememberSaveable { mutableStateOf(AppLanguage.RU.name) }
                var currentDebugSecret by rememberSaveable { mutableStateOf<String?>(null) }
                var progressState by remember { mutableStateOf(progressRepository.loadState()) }
                val campaignProgress = remember(progressState.highestUnlockedCampaignLevel, progressState.totalCampaignRating) {
                    val campaignUpperBound = maxOf(40, progressState.highestUnlockedCampaignLevel + 20)
                    progressRepository.loadCampaignProgressRange(1, campaignUpperBound)
                }
                val currentLanguage = AppLanguage.valueOf(currentLanguageName)
                val strings = remember(currentLanguage) {
                    StaticLocalizationProvider.forLanguage(currentLanguage)
                }
                val entitlements = remember(progressState) {
                    MonetizationEntitlements(
                        adFreePurchased = progressState.adFreePurchased,
                        proSubscriptionActive = progressState.proSubscriptionActive,
                        proPlusSubscriptionActive = progressState.proPlusSubscriptionActive,
                    )
                }
                val isPremium = entitlements.adsDisabled

                val bottomMode = when {
                    isInGame -> if (isPremium) BottomLayerMode.NONE else BottomLayerMode.AD
                    else -> BottomLayerMode.MENU
                }

                CompositionLocalProvider(LocalAppStrings provides strings) {
                    AppShell(
                        currentSection = currentSection,
                        onSectionChange = { section ->
                            currentSection = section
                            isSettingsOpen = false
                        },
                        bottomMode = bottomMode,
                        topMode = TopLayerMode.OVERLAY,
                        backgroundStyle = ScreenBackgroundStyle.ImageAsset(
                            assetPath = "image/background/app_bg.png",
                            fallbackColor = Color(0xFF4C6FFF)
                        ),
                        topContent = {
                            AppTopBar(
                                energy = progressState.campaignEnergy,
                                coins = progressState.coins,
                                showBack = isInGame,
                                onBackClick = { requestExitGame = true },
                                onSettingsClick = { isSettingsOpen = true }
                            )
                        },
                        bottomAdContent = {
                            DebugSecretAdSlot(
                                debugSecret = currentDebugSecret,
                                adsDisabled = progressState.adsDisabled
                            )
                        }
                    ) {
                        when {
                            currentSection == AppSection.HOME -> HomeRootScreen(
                                requestExitGame = requestExitGame,
                                onExitGameConsumed = { requestExitGame = false },
                                onInGameChange = { inGame -> isInGame = inGame },
                                onDebugSecretChange = { currentDebugSecret = it },
                                openPositionHints = progressState.openPositionHints,
                                checkDigitHints = progressState.checkDigitHints,
                                checkPositionHints = progressState.checkPositionHints,
                                autoModeAvailable = progressState.autoTableAssistEnabled,
                                infiniteHintsEnabled = progressState.infiniteHintsEnabled,
                                onConsumeOpenPositionHint = {
                                    if (progressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.OPEN_POSITION)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onConsumeCheckDigitHint = {
                                    if (progressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.CHECK_DIGIT)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onConsumeCheckPositionHint = {
                                    if (progressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.CHECK_POSITION)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onWatchRewardedHintAd = { hintType ->
                                    val placement = when (hintType) {
                                        HintStockType.OPEN_POSITION -> RewardedPlacement.GAME_OPEN_POSITION_HINT
                                        HintStockType.CHECK_DIGIT -> RewardedPlacement.GAME_CHECK_DIGIT_HINT
                                        HintStockType.CHECK_POSITION -> RewardedPlacement.GAME_CHECK_POSITION_HINT
                                    }
                                    adService.showRewardedAd(placement)
                                },
                                onMatchStarted = {
                                    progressState = progressRepository.recordMatchStarted()
                                },
                                onRecordPveResult = { won ->
                                    progressState = progressRepository.recordModeResult(GameModeStatType.PVE_RACE, won)
                                    if (adService.shouldShowPostGameInterstitial(progressState.matchesPlayed, entitlements)) {
                                        adService.showInterstitial(InterstitialPlacement.POST_MATCH)
                                    }
                                },
                                onRecordPvpResult = { won ->
                                    progressState = progressRepository.recordModeResult(GameModeStatType.PVP_DUEL, won)
                                    if (adService.shouldShowPostGameInterstitial(progressState.matchesPlayed, entitlements)) {
                                        adService.showInterstitial(InterstitialPlacement.POST_MATCH)
                                    }
                                }
                            )

                            currentSection == AppSection.SOCIAL -> SocialRootScreen()
                            currentSection == AppSection.COMPANY -> CompanyRootScreen(
                                progressState = progressState,
                                campaignProgress = campaignProgress,
                                requestExitGame = requestExitGame,
                                onExitGameConsumed = { requestExitGame = false },
                                onInGameChange = { inGame -> isInGame = inGame },
                                onDebugSecretChange = { currentDebugSecret = it },
                                openPositionHints = progressState.openPositionHints,
                                checkDigitHints = progressState.checkDigitHints,
                                checkPositionHints = progressState.checkPositionHints,
                                autoModeAvailable = progressState.autoTableAssistEnabled,
                                infiniteHintsEnabled = progressState.infiniteHintsEnabled,
                                extraMovesBoosts = progressState.extraMovesBoosts,
                                extraTimeBoosts = progressState.extraTimeBoosts,
                                onConsumeOpenPositionHint = {
                                    if (progressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.OPEN_POSITION)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onConsumeCheckDigitHint = {
                                    if (progressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.CHECK_DIGIT)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onConsumeCheckPositionHint = {
                                    if (progressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.CHECK_POSITION)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onWatchRewardedHintAd = { hintType ->
                                    val placement = when (hintType) {
                                        HintStockType.OPEN_POSITION -> RewardedPlacement.GAME_OPEN_POSITION_HINT
                                        HintStockType.CHECK_DIGIT -> RewardedPlacement.GAME_CHECK_DIGIT_HINT
                                        HintStockType.CHECK_POSITION -> RewardedPlacement.GAME_CHECK_POSITION_HINT
                                    }
                                    adService.showRewardedAd(placement)
                                },
                                onConsumeExtraMovesBoost = {
                                    if (progressRepository.consumeBoost(BoostStockType.EXTRA_MOVES)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onConsumeExtraTimeBoost = {
                                    if (progressRepository.consumeBoost(BoostStockType.EXTRA_TIME)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onBuyEnergy = {
                                    if (progressRepository.buyCampaignEnergy(costCoins = 25)) {
                                        progressState = progressRepository.loadState()
                                    }
                                },
                                onRecordCampaignCompletion = { level, rating ->
                                    progressState = progressRepository.recordCampaignCompletion(level, rating)
                                },
                                onRecordCompanyLoss = {
                                    progressState = progressRepository.recordCompanyLoss()
                                },
                                onMatchStarted = {
                                    progressState = progressRepository.recordMatchStarted()
                                }
                            )
                            currentSection == AppSection.SHOP -> ShopRootScreen(
                                progressState = progressState,
                                onWatchRewardedCoins = {
                                    if (adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD)) {
                                        progressState = progressRepository.grantRewardedCoins(20)
                                    }
                                },
                                onBuyOpenPositionHint = {
                                    if (progressRepository.buyHint(HintStockType.OPEN_POSITION, costCoins = 20)) {
                                        progressState = progressRepository.loadState()
                                    }
                                },
                                onBuyCheckDigitHint = {
                                    if (progressRepository.buyHint(HintStockType.CHECK_DIGIT, costCoins = 15)) {
                                        progressState = progressRepository.loadState()
                                    }
                                },
                                onBuyCheckPositionHint = {
                                    if (progressRepository.buyHint(HintStockType.CHECK_POSITION, costCoins = 25)) {
                                        progressState = progressRepository.loadState()
                                    }
                                },
                                onBuyExtraMovesBoost = {
                                    if (progressRepository.buyBoost(BoostStockType.EXTRA_MOVES, costCoins = 30)) {
                                        progressState = progressRepository.loadState()
                                    }
                                },
                                onBuyExtraTimeBoost = {
                                    if (progressRepository.buyBoost(BoostStockType.EXTRA_TIME, costCoins = 30)) {
                                        progressState = progressRepository.loadState()
                                    }
                                },
                                onBuyEnergy = {
                                    if (progressRepository.buyCampaignEnergy(costCoins = 25)) {
                                        progressState = progressRepository.loadState()
                                    }
                                },
                                onBuyRemoveAds = {
                                    if (billingService.purchase(BillingProductId.REMOVE_ADS)) {
                                        progressState = progressRepository.activateProduct(com.mirkori.inplacex.data.local.MonetizationProductType.REMOVE_ADS)
                                    }
                                },
                                onBuyPro = {
                                    if (billingService.purchase(BillingProductId.PRO_SUBSCRIPTION)) {
                                        progressState = progressRepository.activateProduct(com.mirkori.inplacex.data.local.MonetizationProductType.PRO_SUBSCRIPTION)
                                    }
                                },
                                onBuyProPlus = {
                                    if (billingService.purchase(BillingProductId.PRO_PLUS_SUBSCRIPTION)) {
                                        progressState = progressRepository.activateProduct(com.mirkori.inplacex.data.local.MonetizationProductType.PRO_PLUS_SUBSCRIPTION)
                                    }
                                }
                            )
                            currentSection == AppSection.PROFILE -> ProfileRootScreen(
                                progressState = progressState,
                                onGooglePlaySignIn = {
                                    val session = authService.signInWithGooglePlay()
                                    progressState = progressRepository.signInWithGooglePlay(session.playerName)
                                },
                                onGooglePlaySignOut = {
                                    authService.signOut()
                                    progressState = progressRepository.signOutFromGooglePlay()
                                },
                                onAddDeveloperCoins = {
                                    progressState = progressRepository.addCoins(100)
                                }
                            )
                        }

                        if (isSettingsOpen) {
                            SettingsRootScreen(
                                currentLanguage = currentLanguage,
                                onLanguageChange = { language ->
                                    currentLanguageName = language.name
                                },
                                onClose = {
                                    isSettingsOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen()
        }
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
