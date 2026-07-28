package com.mirkori.inplacex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.data.local.BoostStockType
import com.mirkori.inplacex.data.local.GameModeStatType
import com.mirkori.inplacex.data.local.GameProgressRepository
import com.mirkori.inplacex.data.local.HintStockType
import com.mirkori.inplacex.data.local.MonetizationProductType
import com.mirkori.inplacex.data.local.PlatformLocalRepository
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.platform.online.GuestAuthResult
import com.mirkori.inplacex.platform.online.GoogleChallengeResult
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.InterstitialPlacement
import com.mirkori.inplacex.platform.services.GoogleCredentialResult
import com.mirkori.inplacex.platform.services.GoogleCredentialSignIn
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.inplacex.platform.services.ProviderServicesFactory
import com.mirkori.inplacex.platform.services.RewardedPlacement
import com.mirkori.inplacex.ui.background.ScreenBackgroundPreset
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.settings.SettingsRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.shell.AppShell
import com.mirkori.inplacex.ui.shell.AppTopBar
import com.mirkori.inplacex.ui.shell.BottomLayerMode
import com.mirkori.inplacex.ui.shell.CenterLayerMode
import com.mirkori.inplacex.ui.shell.TopLayerMode
import com.mirkori.inplacex.ui.screens.home.HomeScreenState
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveMode()

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
                val googleCredentialSignIn = remember {
                    GoogleCredentialSignIn(
                        context = applicationContext,
                        config = AppConfigCatalog.platformConfig.providers.googlePlay,
                    )
                }
                val coroutineScope = rememberCoroutineScope()

                var currentSection by rememberSaveable { mutableStateOf(AppSection.HOME) }
                var isInGame by rememberSaveable { mutableStateOf(false) }
                var requestExitGame by rememberSaveable { mutableStateOf(false) }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                var isVariantToolsOpen by rememberSaveable { mutableStateOf(false) }
                var variantToolsEnabled by rememberSaveable { mutableStateOf(false) }
                var currentLanguageName by rememberSaveable { mutableStateOf(AppLanguage.RU.name) }
                var currentInspectionValue by rememberSaveable { mutableStateOf<String?>(null) }
                var homeScreenState by rememberSaveable { mutableStateOf(HomeScreenState.ROOT) }
                var companyActiveLevelNumber by rememberSaveable { mutableStateOf<Int?>(null) }
                var progressState by remember { mutableStateOf(progressRepository.loadState()) }
                var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var campaignProgress by remember { mutableStateOf<List<CampaignLevelProgress>>(emptyList()) }
                var profileAuthResultKey by rememberSaveable { mutableStateOf<String?>(null) }
                var profileAuthInProgress by rememberSaveable { mutableStateOf(false) }
                val platformLocalRepository = remember { PlatformLocalRepository(applicationContext) }

                LaunchedEffect(progressState.temporaryProExpiresAtMs) {
                    currentTimeMs = System.currentTimeMillis()
                    while (progressState.temporaryProActiveAt(currentTimeMs)) {
                        val remainingMs = progressState.temporaryProExpiresAtMs - currentTimeMs
                        delay(minOf(1_000L, remainingMs.coerceAtLeast(1L)))
                        currentTimeMs = System.currentTimeMillis()
                    }
                }

                LaunchedEffect(
                    progressState.highestUnlockedCampaignLevel,
                    progressState.totalCampaignRating,
                ) {
                    val campaignUpperBound = maxOf(40, progressState.highestUnlockedCampaignLevel + 20)
                    campaignProgress = withContext(Dispatchers.IO) {
                        progressRepository.loadCampaignProgressRange(1, campaignUpperBound)
                    }
                }
                val currentLanguage = AppLanguage.valueOf(currentLanguageName)
                val localPlayerProfile = remember(platformLocalRepository) {
                    platformLocalRepository.loadPlayerProfile()
                }
                val onlineRuntime = remember(currentLanguage, localPlayerProfile.installationId) {
                    OnlineRuntime.createOrNull(
                        context = applicationContext,
                        profile = localPlayerProfile,
                        locale = if (currentLanguage == AppLanguage.RU) "ru-RU" else "en-US",
                        regionCode = if (currentLanguage == AppLanguage.RU) "RU" else "US",
                    )
                }
                DisposableEffect(onlineRuntime) {
                    onDispose { onlineRuntime?.close() }
                }
                val strings = remember(currentLanguage) {
                    StaticLocalizationProvider.forLanguage(currentLanguage)
                }
                val entitlements = remember(progressState, currentTimeMs) {
                    MonetizationEntitlements(
                        adFreePurchased = progressState.adFreePurchased,
                        proSubscriptionActive = progressState.proSubscriptionActive ||
                            progressState.temporaryProActiveAt(currentTimeMs),
                        proPlusSubscriptionActive = progressState.proPlusSubscriptionActive,
                    )
                }
                val isPremium = entitlements.adsDisabled
                val useUnifiedSceneBackground =
                    !isInGame || currentSection == AppSection.COMPANY
                val shouldShowVariantBottomSlot =
                    isInGame && variantToolsBottomSlotEnabled(variantToolsEnabled)
                val appBackgroundStyle = if (useUnifiedSceneBackground) {
                    ScreenBackgroundStyle.Preset(ScreenBackgroundPreset.WarmWorkshop)
                } else {
                    ScreenBackgroundStyle.ImageAsset(
                        assetPath = "image/background/app_bg.png",
                        fallbackColor = InplaceXColors.ToyWood,
                    )
                }
                val bottomMode = when {
                    isInGame -> if (isPremium && !shouldShowVariantBottomSlot) BottomLayerMode.NONE else BottomLayerMode.AD
                    else -> BottomLayerMode.MENU
                }

                CompositionLocalProvider(LocalAppStrings provides strings) {
                    AppShell(
                        currentSection = currentSection,
                        onSectionChange = { section ->
                            currentSection = section
                            isSettingsOpen = false
                            isVariantToolsOpen = false
                        },
                        bottomMode = bottomMode,
                        topMode = TopLayerMode.OVERLAY,
                        centerMode = if (useUnifiedSceneBackground) CenterLayerMode.TRANSPARENT else CenterLayerMode.SURFACE,
                        backgroundStyle = appBackgroundStyle,
                        topContent = {
                            AppTopBar(
                                energy = progressState.campaignEnergy,
                                energyMax = progressState.campaignEnergyMax,
                                coins = progressState.coins,
                                showBack = isInGame || isVariantToolsOpen,
                                showShop = !isInGame,
                                onBackClick = {
                                    when {
                                        isVariantToolsOpen -> isVariantToolsOpen = false
                                        else -> requestExitGame = true
                                    }
                                },
                                onShopClick = {
                                    currentSection = AppSection.SHOP
                                    isSettingsOpen = false
                                },
                                onSettingsClick = { isSettingsOpen = true },
                            )
                        },
                        bottomAdContent = {
                            VariantBottomAdContent(
                                inspectionValue = currentInspectionValue,
                                adsDisabled = progressState.adsDisabledAt(currentTimeMs),
                                toolsEnabled = variantToolsEnabled,
                            )
                        },
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            when {
                                currentSection == AppSection.HOME -> HomeRootScreen(
                                    screenState = homeScreenState,
                                    onScreenStateChange = { homeScreenState = it },
                                    requestExitGame = requestExitGame,
                                    onExitGameConsumed = { requestExitGame = false },
                                    onInGameChange = { inGame -> isInGame = inGame },
                                    onDebugSecretChange = { currentInspectionValue = it },
                                    openPositionHints = progressState.openPositionHints,
                                    checkDigitHints = progressState.checkDigitHints,
                                    checkPositionHints = progressState.checkPositionHints,
                                    autoModeAvailable = progressState.autoTableAssistEnabledAt(currentTimeMs),
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
                                    },
                                    onOpenCompany = {
                                        currentSection = AppSection.COMPANY
                                    },
                                )

                            currentSection == AppSection.SOCIAL -> SocialRootScreen(
                                onlineRuntime = onlineRuntime,
                            )

                            currentSection == AppSection.COMPANY -> CompanyRootScreen(
                                progressState = progressState,
                                campaignProgress = campaignProgress,
                                activeLevelNumber = companyActiveLevelNumber,
                                onActiveLevelNumberChange = { companyActiveLevelNumber = it },
                                requestExitGame = requestExitGame,
                                onExitGameConsumed = { requestExitGame = false },
                                onInGameChange = { inGame -> isInGame = inGame },
                                onDebugSecretChange = { currentInspectionValue = it },
                                openPositionHints = progressState.openPositionHints,
                                checkDigitHints = progressState.checkDigitHints,
                                checkPositionHints = progressState.checkPositionHints,
                                autoModeAvailable = progressState.autoTableAssistEnabledAt(currentTimeMs),
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
                                },
                            )

                            currentSection == AppSection.SHOP -> ShopRootScreen(
                                progressState = progressState,
                                nowMs = currentTimeMs,
                                onWatchRewardedCoins = {
                                    val rewarded = adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD)
                                    if (rewarded) {
                                        progressState = progressRepository.grantRewardedCoins(20)
                                    }
                                    rewarded
                                },
                                onBuyOpenPositionHint = {
                                    val purchased = progressRepository.buyHint(HintStockType.OPEN_POSITION, costCoins = 20)
                                    if (purchased) {
                                        progressState = progressRepository.loadState()
                                    }
                                    purchased
                                },
                                onBuyCheckDigitHint = {
                                    val purchased = progressRepository.buyHint(HintStockType.CHECK_DIGIT, costCoins = 15)
                                    if (purchased) {
                                        progressState = progressRepository.loadState()
                                    }
                                    purchased
                                },
                                onBuyCheckPositionHint = {
                                    val purchased = progressRepository.buyHint(HintStockType.CHECK_POSITION, costCoins = 25)
                                    if (purchased) {
                                        progressState = progressRepository.loadState()
                                    }
                                    purchased
                                },
                                onBuyExtraMovesBoost = {
                                    val purchased = progressRepository.buyBoost(BoostStockType.EXTRA_MOVES, costCoins = 30)
                                    if (purchased) {
                                        progressState = progressRepository.loadState()
                                    }
                                    purchased
                                },
                                onBuyExtraTimeBoost = {
                                    val purchased = progressRepository.buyBoost(BoostStockType.EXTRA_TIME, costCoins = 30)
                                    if (purchased) {
                                        progressState = progressRepository.loadState()
                                    }
                                    purchased
                                },
                                onBuyEnergy = {
                                    val purchased = progressRepository.buyCampaignEnergy(costCoins = 25)
                                    if (purchased) {
                                        progressState = progressRepository.loadState()
                                    }
                                    purchased
                                },
                                onBuyRemoveAds = {
                                    val purchased = billingService.purchase(BillingProductId.REMOVE_ADS)
                                    if (purchased) {
                                        progressState = progressRepository.activateProduct(MonetizationProductType.REMOVE_ADS)
                                    }
                                    purchased
                                },
                                onBuyPro = {
                                    val purchased = billingService.purchase(BillingProductId.PRO_SUBSCRIPTION)
                                    if (purchased) {
                                        progressState = progressRepository.activateProduct(MonetizationProductType.PRO_SUBSCRIPTION)
                                    }
                                    purchased
                                },
                                onBuyProPlus = {
                                    val purchased = billingService.purchase(BillingProductId.PRO_PLUS_SUBSCRIPTION)
                                    if (purchased) {
                                        progressState = progressRepository.activateProduct(MonetizationProductType.PRO_PLUS_SUBSCRIPTION)
                                    }
                                    purchased
                                },
                                onBuyTemporaryPro = {
                                    val purchased = progressRepository.buyTemporaryPro()
                                    if (purchased) {
                                        progressState = progressRepository.loadState()
                                        AppLog.info(
                                            tag = "MainActivity",
                                            message = "temporary Pro purchased",
                                            attributes = mapOf(
                                                "priceCoins" to TemporaryProPolicy.PRICE_COINS.toString(),
                                                "durationMinutes" to
                                                    (TemporaryProPolicy.DURATION_MS / 60_000L).toString(),
                                            ),
                                        )
                                    }
                                    purchased
                                },
                            )

                            currentSection == AppSection.PROFILE -> ProfileRootScreen(
                                progressState = progressState,
                                nowMs = currentTimeMs,
                                authResultKey = profileAuthResultKey,
                                authInProgress = profileAuthInProgress,
                                onGooglePlaySignIn = {
                                    if (!profileAuthInProgress) {
                                        profileAuthInProgress = true
                                        profileAuthResultKey = null
                                        coroutineScope.launch {
                                            val challenge = onlineRuntime?.createGoogleChallenge()
                                            profileAuthResultKey = when (challenge) {
                                                is GoogleChallengeResult.Ready -> {
                                                    when (
                                                        val providerResult = googleCredentialSignIn.signIn(
                                                            activity = this@MainActivity,
                                                            nonce = challenge.challenge.nonce,
                                                        )
                                                    ) {
                                                        is GoogleCredentialResult.Success -> {
                                                            when (
                                                                val serverResult = onlineRuntime.authenticateWithGoogle(
                                                                    idToken = providerResult.credential.idToken,
                                                                    nonce = challenge.challenge.nonce,
                                                                )
                                                            ) {
                                                                is GuestAuthResult.Authenticated -> {
                                                                    progressState = progressRepository.signInWithGooglePlay(
                                                                        providerResult.credential.playerName
                                                                            ?: progressState.playerDisplayName,
                                                                    )
                                                                    "profile.auth.signed_in"
                                                                }
                                                                GuestAuthResult.Rejected ->
                                                                    "profile.auth.rejected"
                                                                GuestAuthResult.TemporarilyUnavailable ->
                                                                    "profile.auth.unavailable"
                                                            }
                                                        }
                                                        GoogleCredentialResult.Cancelled ->
                                                            "profile.auth.cancelled"
                                                        GoogleCredentialResult.Unavailable ->
                                                            "profile.auth.not_configured"
                                                        GoogleCredentialResult.Failed ->
                                                            "profile.auth.rejected"
                                                    }
                                                }
                                                GoogleChallengeResult.AuthenticationRequired,
                                                GoogleChallengeResult.Rejected,
                                                -> "profile.auth.rejected"
                                                GoogleChallengeResult.ProviderUnavailable ->
                                                    "profile.auth.not_configured"
                                                GoogleChallengeResult.TemporarilyUnavailable,
                                                null,
                                                -> "profile.auth.unavailable"
                                            }
                                            profileAuthInProgress = false
                                        }
                                    }
                                },
                                onGooglePlaySignOut = {
                                    if (!profileAuthInProgress) {
                                        profileAuthInProgress = true
                                        coroutineScope.launch {
                                            googleCredentialSignIn.signOut()
                                            onlineRuntime?.signOut()
                                            progressState = progressRepository.signOutFromGooglePlay()
                                            profileAuthResultKey = "profile.auth.signed_out"
                                            profileAuthInProgress = false
                                        }
                                    }
                                },
                            )
                        }

                        VariantToolsSurface(
                            isOpen = isVariantToolsOpen,
                            progressState = progressState,
                            progressRepository = progressRepository,
                            platformLocalRepository = platformLocalRepository,
                            onProgressStateChange = { progressState = it },
                            onClose = { isVariantToolsOpen = false },
                        )

                        if (isSettingsOpen) {
                            SettingsRootScreen(
                                currentLanguage = currentLanguage,
                                onLanguageChange = { language ->
                                    currentLanguageName = language.name
                                },
                                onOpenInternalTools = {
                                    isSettingsOpen = false
                                    variantToolsEnabled = true
                                    isVariantToolsOpen = true
                                },
                                onClose = {
                                    isSettingsOpen = false
                                },
                            )
                            BackHandler(enabled = isSettingsOpen) {
                                isSettingsOpen = false
                            }
                        }
                    }
                }
            }
        }
    }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    private fun applyImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
