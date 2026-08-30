package com.mirkori.inplacex

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.GameProgressRepository
import com.mirkori.inplacex.data.local.HintStockType
import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdDecision
import com.mirkori.inplacex.ads.AdEntitlements
import com.mirkori.inplacex.ads.AdPlacement
import com.mirkori.inplacex.ads.AdPolicy
import com.mirkori.inplacex.ads.AdPresentationResult
import com.mirkori.inplacex.ads.AdPreloadResult
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest
import com.mirkori.inplacex.data.local.PlatformLocalRepository
import com.mirkori.inplacex.data.local.LocalSocialRelationship
import com.mirkori.inplacex.data.local.LocalRelationshipStatus
import com.mirkori.inplacex.data.local.LocalRelationshipType
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.core.retention.RetentionRewardType
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.ads.AdConsentDecision
import com.mirkori.inplacex.platform.ads.YandexGameBanner
import com.mirkori.inplacex.platform.ads.AdUsageTracker
import com.mirkori.inplacex.platform.ads.completedMatchCountForAds
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.platform.feedback.AndroidAppFeedbackRuntime
import com.mirkori.inplacex.platform.feedback.AppFeedbackSettingsStore
import com.mirkori.inplacex.platform.feedback.AppHapticCue
import com.mirkori.inplacex.platform.feedback.AppSoundCue
import com.mirkori.inplacex.platform.feedback.LocalAppFeedbackRuntime
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountState
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountStateKind
import com.mirkori.inplacex.platform.mirkori.MirkoriLoginResult
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendOperationResult
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendRequest
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendsResult
import com.mirkori.inplacex.platform.mirkori.MirkoriIncomingFriendRequestsResult
import com.mirkori.inplacex.platform.mirkori.MirkoriPlayerSearchResult
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicPlayerProfile
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicProfileResult
import com.mirkori.inplacex.platform.mirkori.MirkoriBillingService
import com.mirkori.inplacex.platform.mirkori.MirkoriPlatformRuntime
import com.mirkori.inplacex.platform.online.ActiveOnlineSessionStore
import com.mirkori.inplacex.platform.online.IncomingFriendInviteNotifier
import com.mirkori.inplacex.platform.online.OnlineClientResult
import com.mirkori.inplacex.platform.online.OnlineFriendInvite
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.BillingAvailability
import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.BillingPurchaseResult
import com.mirkori.inplacex.platform.services.BillingState
import com.mirkori.inplacex.platform.services.AdPlacementPolicy
import com.mirkori.inplacex.platform.services.GoogleCredentialSignIn
import com.mirkori.inplacex.platform.services.GoogleCredential
import com.mirkori.inplacex.platform.services.GoogleCredentialResult
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.inplacex.platform.services.ProviderServicesFactory
import com.mirkori.inplacex.platform.web.MirkoriWebsiteLauncher
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.profile.GoogleProfileConflictDialog
import com.mirkori.inplacex.ui.screens.settings.SettingsRootScreen
import com.mirkori.inplacex.ui.screens.settings.AdPrivacyConsentDialog
import com.mirkori.inplacex.ui.screens.shop.ShopPremiumDestination
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.shell.AppShell
import com.mirkori.inplacex.ui.shell.AppTopBar
import com.mirkori.inplacex.ui.shell.BottomLayerMode
import com.mirkori.inplacex.ui.shell.CenterLayerMode
import com.mirkori.inplacex.ui.shell.TopLayerMode
import com.mirkori.inplacex.ui.screens.home.HomeScreenState
import com.mirkori.inplacex.ui.state.TransientOperationGate
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformProfileConflictResolution
import java.net.URI

class MainActivity : ComponentActivity() {
    private var mirkoriCallbackUrl by mutableStateOf<String?>(null)
    private var resumeGeneration by mutableLongStateOf(0L)
    private lateinit var adUsageTracker: AdUsageTracker
    private lateinit var feedbackRuntime: AndroidAppFeedbackRuntime
    private lateinit var feedbackSettingsStore: AppFeedbackSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureMirkoriCallback(intent)
        adUsageTracker = AdUsageTracker.create(applicationContext)
        feedbackSettingsStore = AppFeedbackSettingsStore(applicationContext)
        feedbackRuntime = AndroidAppFeedbackRuntime(applicationContext)
        feedbackRuntime.updateSettings(feedbackSettingsStore.read())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveMode()

        setContent {
            InplaceXTheme {
                var feedbackSettings by remember {
                    mutableStateOf(feedbackSettingsStore.read())
                }
                val progressRepository = remember { GameProgressRepository(applicationContext) }
                val mirkoriPlatformRuntime = remember {
                    runCatching { MirkoriPlatformRuntime.createOrNull(applicationContext) }
                        .onFailure { error ->
                            AppLog.warn(
                                tag = "MainActivity",
                                message = "Mirkori Games runtime is unavailable",
                                attributes = mapOf("errorClass" to error.javaClass.name),
                            )
                        }
                        .getOrNull()
                }
                DisposableEffect(mirkoriPlatformRuntime) {
                    onDispose { mirkoriPlatformRuntime?.close() }
                }
                val liveBillingService = remember(mirkoriPlatformRuntime) {
                    mirkoriPlatformRuntime?.let { runtime ->
                        MirkoriBillingService(
                            runtime = runtime,
                            config = AppConfigCatalog.platformConfig.providers.billing,
                        )
                    }
                }
                val providerServices = remember(liveBillingService) {
                    ProviderServicesFactory.create(
                        context = applicationContext,
                        platformConfig = AppConfigCatalog.platformConfig,
                        billingService = liveBillingService,
                    )
                }
                val billingService = providerServices.billingService
                val adConsentRequired = providerServices.adsConfigured
                val googleCredentialSignIn = remember {
                    GoogleCredentialSignIn(
                        context = applicationContext,
                        config = AppConfigCatalog.platformConfig.providers.googlePlay,
                    )
                }
                val coroutineScope = rememberCoroutineScope()
                DisposableEffect(providerServices) {
                    providerServices.adActivityHost.attach(this@MainActivity)
                    onDispose {
                        providerServices.adActivityHost.detach(this@MainActivity)
                        providerServices.adRuntime.close()
                    }
                }

                val activeOnlineSessionStore = remember {
                    ActiveOnlineSessionStore(applicationContext)
                }
                val restoredActiveOnlineSessionId = remember(activeOnlineSessionStore) {
                    activeOnlineSessionStore.read()
                }

                var currentSection by rememberSaveable {
                    mutableStateOf(initialSectionForActiveOnlineSession(restoredActiveOnlineSessionId))
                }
                var activeOnlineSessionId by remember {
                    mutableStateOf(restoredActiveOnlineSessionId)
                }
                var isInGame by rememberSaveable { mutableStateOf(false) }
                var isNestedHomeScreen by rememberSaveable { mutableStateOf(false) }
                var isNestedSocialScreen by rememberSaveable { mutableStateOf(false) }
                var shopPremiumDestinationName by rememberSaveable {
                    mutableStateOf(ShopPremiumDestination.OVERVIEW.name)
                }
                var requestExitGame by rememberSaveable { mutableStateOf(false) }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                var selectedBannerProviderName by remember {
                    mutableStateOf<String?>(null)
                }
                var bannerLoaded by remember { mutableStateOf(false) }
                var failedBannerProviderNames by remember {
                    mutableStateOf(emptySet<String>())
                }
                var adConsentDecisionName by rememberSaveable {
                    mutableStateOf(providerServices.adConsent.currentDecision().name)
                }
                var isAdPrivacyOpen by rememberSaveable {
                    mutableStateOf(
                        adConsentRequired &&
                            providerServices.adConsent.currentDecision() ==
                            AdConsentDecision.UNDECIDED,
                    )
                }
                var isVariantToolsOpen by rememberSaveable { mutableStateOf(false) }
                var variantToolsEnabled by rememberSaveable { mutableStateOf(false) }
                var currentLanguageName by rememberSaveable { mutableStateOf(AppLanguage.RU.name) }
                var currentInspectionValue by rememberSaveable { mutableStateOf<String?>(null) }
                var homeScreenState by rememberSaveable { mutableStateOf(HomeScreenState.ROOT) }
                var requestedOnlinePlayStyleName by rememberSaveable { mutableStateOf<String?>(null) }
                var requestedOnlineCodeLength by rememberSaveable { mutableIntStateOf(4) }
                var companyActiveLevelNumber by rememberSaveable { mutableStateOf<Int?>(null) }
                val initialProgressState = remember {
                    initialProgressState(
                        context = applicationContext,
                        progressRepository = progressRepository,
                    )
                }
                var progressState by remember { mutableStateOf(initialProgressState) }
                var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var billingState by remember(billingService) {
                    mutableStateOf(billingService.cachedState())
                }
                val billingOperation = remember { TransientOperationGate() }
                var campaignProgress by remember { mutableStateOf<List<CampaignLevelProgress>>(emptyList()) }
                var claimedCampaignChapters by remember {
                    mutableStateOf(progressRepository.loadClaimedCampaignChapters())
                }
                var retentionRewardStatus by remember {
                    mutableStateOf(progressRepository.loadRetentionRewardStatus())
                }
                var profileAuthResultKey by rememberSaveable { mutableStateOf<String?>(null) }
                val profileAuthOperation = remember { TransientOperationGate() }
                var pendingGoogleProfileConflict by remember {
                    mutableStateOf<GoogleCredential?>(null)
                }
                var mirkoriAccountState by remember {
                    mutableStateOf(MirkoriAccountState(MirkoriAccountStateKind.INITIALIZING))
                }
                var mirkoriAuthResultKey by rememberSaveable { mutableStateOf<String?>(null) }
                val mirkoriAuthOperation = remember { TransientOperationGate() }
                var publicPlayerProfile by remember {
                    mutableStateOf<MirkoriPublicPlayerProfile?>(null)
                }
                var publicProfileResultKey by rememberSaveable { mutableStateOf<String?>(null) }
                val publicProfileOperation = remember { TransientOperationGate() }
                val platformLocalRepository = remember { PlatformLocalRepository(applicationContext) }
                val localPlayerProfile = remember(platformLocalRepository) {
                    platformLocalRepository.loadPlayerProfile()
                }
                var savedFriends by remember(platformLocalRepository) {
                    mutableStateOf(
                        platformLocalRepository
                            .loadRelationships(LocalRelationshipStatus.ACTIVE)
                            .filter {
                                it.relationshipType == LocalRelationshipType.FRIEND &&
                                    it.source == "platform_friendship"
                            },
                    )
                }
                var pendingFriendRequests by remember(platformLocalRepository) {
                    mutableStateOf(
                        platformLocalRepository
                            .loadRelationships(LocalRelationshipStatus.PENDING)
                            .filter { it.relationshipType == LocalRelationshipType.INVITE_OUTGOING },
                    )
                }

                LaunchedEffect(Unit) {
                    adUsageTracker.ensureCompletedMatchBaseline(
                        completedMatchCountForAds(
                            pve = initialProgressState.pveStats,
                            pvp = initialProgressState.pvpStats,
                            company = initialProgressState.companyStats,
                        ),
                    )
                }

                LaunchedEffect(adConsentDecisionName, providerServices) {
                    if (
                        AdConsentDecision.valueOf(adConsentDecisionName) !=
                        AdConsentDecision.UNDECIDED
                    ) {
                        providerServices.adRuntime.preload(
                            AdRequest(
                                placement = AdPlacement.SHOP_COINS_REWARD,
                                format = AdFormat.REWARDED,
                            ),
                        )
                        providerServices.adRuntime.preload(
                            AdRequest(
                                placement = AdPlacement.POST_MATCH_INTERSTITIAL,
                                format = AdFormat.INTERSTITIAL,
                            ),
                        )
                    }
                }

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
                LaunchedEffect(currentSection) {
                    if (currentSection == AppSection.COMPANY) {
                        retentionRewardStatus = progressRepository.loadRetentionRewardStatus()
                    }
                }
                val currentLanguage = AppLanguage.valueOf(currentLanguageName)
                LaunchedEffect(mirkoriPlatformRuntime) {
                    if (mirkoriPlatformRuntime == null) {
                        mirkoriAccountState = MirkoriAccountState(MirkoriAccountStateKind.UNAVAILABLE)
                    } else {
                        mirkoriPlatformRuntime.accountState.collect { state ->
                            mirkoriAccountState = state
                        }
                    }
                }
                LaunchedEffect(
                    mirkoriPlatformRuntime,
                    mirkoriAccountState.kind,
                    mirkoriAccountState.gamePlayerId,
                ) {
                    val runtime = mirkoriPlatformRuntime
                    if (
                        runtime == null ||
                        mirkoriAccountState.kind == MirkoriAccountStateKind.INITIALIZING ||
                        mirkoriAccountState.gamePlayerId == null
                    ) {
                        publicPlayerProfile = null
                        return@LaunchedEffect
                    }
                    when (val result = withContext(Dispatchers.IO) { runtime.loadPublicProfile() }) {
                        is MirkoriPublicProfileResult.Success -> publicPlayerProfile = result.profile
                        else -> publicPlayerProfile = null
                    }
                }
                val onlineRuntime = remember(mirkoriPlatformRuntime) {
                    mirkoriPlatformRuntime?.let { platformRuntime ->
                        OnlineRuntime.createOrNull(
                            context = applicationContext,
                            accessTokenProvider = platformRuntime,
                        )
                    }
                }
                var incomingFriendInvites by remember {
                    mutableStateOf(emptyList<OnlineFriendInvite>())
                }
                var incomingFriendRequests by remember {
                    mutableStateOf(emptyList<MirkoriFriendRequest>())
                }
                val incomingInviteNotifier = remember {
                    IncomingFriendInviteNotifier(applicationContext)
                }
                val notifiedInviteCodes = remember { mutableSetOf<String>() }
                val notifiedFriendRequestIds = remember { mutableSetOf<String>() }
                var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
                var notificationPermissionGranted by remember {
                    mutableStateOf(incomingInviteNotifier.canPostNotifications())
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    notificationPermissionGranted = granted
                }
                DisposableEffect(onlineRuntime) {
                    onDispose { onlineRuntime?.close() }
                }
                LaunchedEffect(onlineRuntime) {
                    if (onlineRuntime == null) {
                        incomingFriendInvites = emptyList()
                        return@LaunchedEffect
                    }
                    while (true) {
                        when (val result = onlineRuntime.listIncomingFriendInvites()) {
                            is OnlineClientResult.Success -> incomingFriendInvites = result.value
                            OnlineClientResult.AuthenticationRequired ->
                                incomingFriendInvites = emptyList()
                            else -> Unit
                        }
                        delay(IncomingInvitePollMillis)
                    }
                }
                LaunchedEffect(mirkoriPlatformRuntime, mirkoriAccountState.gamePlayerId) {
                    val runtime = mirkoriPlatformRuntime
                    if (runtime == null || mirkoriAccountState.gamePlayerId == null) {
                        incomingFriendRequests = emptyList()
                        return@LaunchedEffect
                    }
                    while (true) {
                        when (val result = runtime.incomingFriendRequests()) {
                            is MirkoriIncomingFriendRequestsResult.Success ->
                                incomingFriendRequests = result.requests
                            MirkoriIncomingFriendRequestsResult.Unavailable -> Unit
                        }
                        when (val result = runtime.friends()) {
                            is MirkoriFriendsResult.Success -> {
                                val (confirmedFriends, pendingRequests) = withContext(Dispatchers.IO) {
                                    val relationships = result.players.map { player ->
                                            LocalSocialRelationship(
                                                playerId = localPlayerProfile.playerId,
                                                targetPlayerId = player.gamePlayerId,
                                                targetDisplayName = player.displayName,
                                                relationshipType = LocalRelationshipType.FRIEND,
                                                status = LocalRelationshipStatus.ACTIVE,
                                                source = "platform_friendship",
                                                note = player.handle,
                                            )
                                        }
                                    platformLocalRepository.replaceRelationships(
                                        playerId = localPlayerProfile.playerId,
                                        relationshipType = LocalRelationshipType.FRIEND,
                                        relationships = relationships,
                                    )
                                    relationships.forEach { relationship ->
                                        platformLocalRepository.deleteRelationship(
                                            playerId = localPlayerProfile.playerId,
                                            targetPlayerId = relationship.targetPlayerId,
                                            relationshipType = LocalRelationshipType.INVITE_OUTGOING,
                                        )
                                    }
                                    val friends = platformLocalRepository
                                        .loadRelationships(LocalRelationshipStatus.ACTIVE)
                                        .filter {
                                            it.relationshipType == LocalRelationshipType.FRIEND &&
                                                it.source == "platform_friendship"
                                        }
                                    val pending = platformLocalRepository
                                        .loadRelationships(LocalRelationshipStatus.PENDING)
                                        .filter {
                                            it.relationshipType == LocalRelationshipType.INVITE_OUTGOING
                                        }
                                    friends to pending
                                }
                                savedFriends = confirmedFriends
                                pendingFriendRequests = pendingRequests
                            }
                            MirkoriFriendsResult.Unavailable -> Unit
                        }
                        delay(IncomingInvitePollMillis)
                    }
                }
                LaunchedEffect(mirkoriPlatformRuntime, mirkoriCallbackUrl) {
                    if (mirkoriCallbackUrl == null) {
                        mirkoriAccountState = if (mirkoriPlatformRuntime == null) {
                            MirkoriAccountState(MirkoriAccountStateKind.UNAVAILABLE)
                        } else {
                            withContext(Dispatchers.IO) { mirkoriPlatformRuntime.restoreOrBootstrap() }
                        }
                    }
                }
                LaunchedEffect(mirkoriCallbackUrl, mirkoriPlatformRuntime) {
                    val callback = mirkoriCallbackUrl ?: return@LaunchedEffect
                    currentSection = AppSection.PROFILE
                    val operationId = mirkoriAuthOperation.start()
                    if (operationId == null || mirkoriPlatformRuntime == null) {
                        mirkoriAuthResultKey = "profile.mirkori.unavailable"
                        operationId?.let(mirkoriAuthOperation::finish)
                        consumeMirkoriCallback()
                        return@LaunchedEffect
                    }
                    try {
                        when (val result = withContext(Dispatchers.IO) {
                            mirkoriPlatformRuntime.completeLogin(callback)
                        }) {
                            is MirkoriLoginResult.Connected -> {
                                mirkoriAccountState = result.accountState
                                if (result.accountState.authMode == PlatformAuthMode.GOOGLE) {
                                    progressState = progressRepository.signInWithGooglePlay(
                                        progressState.playerDisplayName,
                                    )
                                    profileAuthResultKey = "profile.auth.signed_in"
                                }
                                mirkoriAuthResultKey = "profile.mirkori.connected.success"
                            }
                            MirkoriLoginResult.ProfileConflict ->
                                mirkoriAuthResultKey = "profile.mirkori.conflict"
                            MirkoriLoginResult.Rejected ->
                                mirkoriAuthResultKey = "profile.mirkori.rejected"
                            MirkoriLoginResult.Unavailable ->
                                mirkoriAuthResultKey = "profile.mirkori.unavailable"
                            MirkoriLoginResult.AlreadyConnected ->
                                mirkoriAuthResultKey = "profile.mirkori.connected.success"
                            is MirkoriLoginResult.BrowserReady ->
                                mirkoriAuthResultKey = "profile.mirkori.rejected"
                            is MirkoriLoginResult.GoogleCredentialRequired ->
                                mirkoriAuthResultKey = "profile.mirkori.rejected"
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        AppLog.warn(
                            tag = "MainActivity",
                            message = "Mirkori Games callback operation failed",
                            attributes = mapOf("errorClass" to error.javaClass.name),
                        )
                        mirkoriAuthResultKey = "profile.mirkori.unavailable"
                    } finally {
                        mirkoriAuthOperation.finish(operationId)
                        consumeMirkoriCallback()
                    }
                }
                LaunchedEffect(
                    billingService,
                    mirkoriAccountState.kind,
                    mirkoriAccountState.accountIdentity,
                    mirkoriAccountState.gamePlayerId,
                    mirkoriAccountState.authMode,
                    resumeGeneration,
                ) {
                    billingOperation.cancel()
                    billingState = billingService.cachedState()
                    if (mirkoriAccountState.kind == MirkoriAccountStateKind.INITIALIZING) {
                        return@LaunchedEffect
                    }
                    val operationId = billingOperation.start() ?: return@LaunchedEffect
                    try {
                        val refreshed = withContext(Dispatchers.IO) { billingService.refresh() }
                        if (billingOperation.isCurrent(operationId)) billingState = refreshed
                    } finally {
                        billingOperation.finish(operationId)
                    }
                }
                LaunchedEffect(billingState.nextEntitlementExpiryDelayMs) {
                    val waitMs = billingState.nextEntitlementExpiryDelayMs ?: return@LaunchedEffect
                    delay(waitMs.coerceAtLeast(1L))
                    currentTimeMs = System.currentTimeMillis()
                    billingState = billingService.cachedState()
                }
                val strings = remember(currentLanguage) {
                    StaticLocalizationProvider.forLanguage(currentLanguage)
                }
                LaunchedEffect(
                    incomingFriendInvites,
                    incomingFriendRequests,
                    notificationPermissionGranted,
                    currentLanguage,
                ) {
                    if (
                        (incomingFriendInvites.isNotEmpty() || incomingFriendRequests.isNotEmpty()) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !incomingInviteNotifier.canPostNotifications() &&
                        !notificationPermissionRequested
                    ) {
                        notificationPermissionRequested = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return@LaunchedEffect
                    }
                    if (!incomingInviteNotifier.canPostNotifications()) return@LaunchedEffect
                    incomingFriendInvites.forEach { invite ->
                        if (notifiedInviteCodes.add(invite.inviteCode)) {
                            val messageKey = if (
                                invite.playStyle ==
                                com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle.RACE
                            ) {
                                "social.notification.race"
                            } else {
                                "social.notification.turn_based"
                            }
                            incomingInviteNotifier.post(
                                invite = invite,
                                title = strings.text("social.notification.title"),
                                message = strings.text(messageKey),
                            )
                            feedbackRuntime.playSound(AppSoundCue.NOTIFICATION)
                            feedbackRuntime.performHaptic(AppHapticCue.CONFIRM)
                        }
                    }
                    incomingFriendRequests.forEach { request ->
                        if (notifiedFriendRequestIds.add(request.requestId)) {
                            incomingInviteNotifier.postFriendRequest(
                                requestId = request.requestId,
                                title = strings.text("social.friend.request.notification.title"),
                                message = strings.text("social.friend.request.notification.message")
                                    .replace("{name}", request.player.displayName),
                            )
                            feedbackRuntime.playSound(AppSoundCue.NOTIFICATION)
                            feedbackRuntime.performHaptic(AppHapticCue.CONFIRM)
                        }
                    }
                }
                val effectiveProgressState = remember(progressState, billingState.entitlements) {
                    progressState.withServerPaidEntitlements(billingState.entitlements)
                }
                val entitlements = remember(billingState.entitlements, progressState, currentTimeMs) {
                    progressState.effectiveMonetizationEntitlements(
                        serverEntitlements = billingState.entitlements,
                        nowMs = currentTimeMs,
                    )
                }
                val refreshBilling: () -> Unit = {
                    billingOperation.start()?.let { operationId ->
                        coroutineScope.launch {
                            try {
                                val refreshed = withContext(Dispatchers.IO) { billingService.refresh() }
                                if (billingOperation.isCurrent(operationId)) billingState = refreshed
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                AppLog.warn(
                                    tag = "MainActivity",
                                    message = "Commerce refresh failed",
                                    attributes = mapOf("errorClass" to error.javaClass.name),
                                )
                            } finally {
                                billingOperation.finish(operationId)
                            }
                        }
                    }
                }
                val purchaseBilling: (BillingProductId) -> Unit = { productId ->
                    billingOperation.start()?.let { operationId ->
                        coroutineScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    billingService.purchase(productId)
                                }
                                if (billingOperation.isCurrent(operationId)) {
                                    billingState = result.state
                                    if (result is BillingPurchaseResult.OpenExternalCheckout) {
                                        if (isExternalHttpsCheckoutUrl(result.checkoutUrl)) {
                                            val browserOpened = runCatching {
                                                startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(result.checkoutUrl)),
                                                )
                                            }.onFailure { error ->
                                                AppLog.warn(
                                                    tag = "MainActivity",
                                                    message = "Commerce browser unavailable",
                                                    attributes = mapOf("errorClass" to error.javaClass.name),
                                                )
                                            }.isSuccess
                                            if (!browserOpened) {
                                                billingState = result.state.copy(
                                                    notice = BillingNotice.RETRY_REQUIRED,
                                                )
                                            }
                                        } else {
                                            billingState = result.state.copy(
                                                availability = BillingAvailability.UNAVAILABLE,
                                                notice = BillingNotice.RETRY_REQUIRED,
                                            )
                                            AppLog.warn(
                                                tag = "MainActivity",
                                                message = "Commerce checkout URL rejected",
                                            )
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                AppLog.warn(
                                    tag = "MainActivity",
                                    message = "Commerce purchase failed",
                                    attributes = mapOf("errorClass" to error.javaClass.name),
                                )
                            } finally {
                                billingOperation.finish(operationId)
                            }
                        }
                    }
                }
                val isPremium = entitlements.adsDisabled
                val showPostMatchInterstitial: () -> Unit = {
                    if (providerServices.postMatchInterstitialConfigured) {
                        val usage = adUsageTracker.snapshot()
                        val request = AdRequest(
                            placement = AdPlacement.POST_MATCH_INTERSTITIAL,
                            format = AdFormat.INTERSTITIAL,
                            matchesPlayed = usage.completedMatches,
                            foregroundUsageSeconds = usage.foregroundUsageMillis / 1_000,
                            matchesSinceLastInterstitial = usage.matchesSinceLastInterstitial,
                        )
                        val decision = AdPolicy.evaluate(
                            request = request,
                            entitlements = AdEntitlements(adsDisabled = entitlements.adsDisabled),
                            interstitialPolicy = AppConfigCatalog.platformConfig
                                .providers
                                .ads
                                .postMatchInterstitialPolicy,
                        )
                        if (decision == AdDecision.Allowed) {
                            coroutineScope.launch {
                                providerServices.adRuntime.preload(request)
                                val presentation = providerServices.adRuntime.show(request)
                                if (
                                    presentation.shownBy != null &&
                                    (
                                        presentation.result == AdPresentationResult.Completed ||
                                            presentation.result == AdPresentationResult.Dismissed
                                        )
                                ) {
                                    adUsageTracker.recordInterstitialPresented()
                                }
                                providerServices.adRuntime.preload(request)
                            }
                        }
                    }
                }
                val gameBannerEligible = AdPlacementPolicy.canRequestGameBanner(
                    isInGame = isInGame,
                    entitlements = entitlements,
                )
                LaunchedEffect(
                    gameBannerEligible,
                    adConsentDecisionName,
                    providerServices,
                    failedBannerProviderNames,
                ) {
                    if (!gameBannerEligible) {
                        selectedBannerProviderName = null
                        bannerLoaded = false
                        failedBannerProviderNames = emptySet()
                        return@LaunchedEffect
                    }
                    val request = AdRequest(
                        placement = AdPlacement.GAME_BANNER,
                        format = AdFormat.BANNER,
                    )
                    while (true) {
                        selectedBannerProviderName = providerServices.adRuntime.preload(request)
                            .firstOrNull {
                                (
                                    it.result == AdPreloadResult.READY ||
                                        it.result == AdPreloadResult.ALREADY_READY
                                    ) &&
                                    it.providerId.name !in failedBannerProviderNames
                            }
                            ?.providerId
                            ?.name
                        if (selectedBannerProviderName != null) {
                            bannerLoaded = false
                            return@LaunchedEffect
                        }
                        delay(BannerRetryDelayMillis)
                        if (failedBannerProviderNames.isNotEmpty()) {
                            failedBannerProviderNames = emptySet()
                        }
                    }
                }
                val appBackgroundStyle = ScreenBackgroundStyle.DrawableResource(
                    resourceId = R.drawable.toy_room_bg_v6,
                    fallbackColor = InplaceXColors.ToyWood,
                )
                val shopPremiumDestination = ShopPremiumDestination.valueOf(shopPremiumDestinationName)
                val isHomeSubpage = currentSection == AppSection.HOME && isNestedHomeScreen
                val isShopSubpage = currentSection == AppSection.SHOP &&
                    shopPremiumDestination == ShopPremiumDestination.PRODUCTS
                val illustratedReferenceSection = currentSection == AppSection.HOME ||
                    currentSection == AppSection.SOCIAL ||
                    currentSection == AppSection.COMPANY ||
                    currentSection == AppSection.SHOP ||
                    currentSection == AppSection.PROFILE
                val illustratedReference = illustratedReferenceSection &&
                    !isInGame && !isSettingsOpen && !isVariantToolsOpen
                val illustratedBackgroundResourceId = if (
                    currentSection == AppSection.HOME || currentSection == AppSection.SHOP
                ) {
                    R.drawable.toy_room_bg_v6
                } else {
                    R.drawable.friends_room_v8
                }
                val bottomMode = when {
                    isInGame && isPremium -> BottomLayerMode.NONE
                    isInGame && selectedBannerProviderName != null && bannerLoaded ->
                        BottomLayerMode.AD
                    isInGame && selectedBannerProviderName != null -> BottomLayerMode.AD_LOADING
                    isInGame -> BottomLayerMode.NONE
                    else -> BottomLayerMode.MENU
                }

                CompositionLocalProvider(
                    LocalAppStrings provides strings,
                    LocalAppFeedbackRuntime provides feedbackRuntime,
                ) {
                    AppShell(
                        currentSection = currentSection,
                        socialNotificationCount = incomingFriendInvites.size + incomingFriendRequests.size,
                        onSectionChange = { section ->
                            feedbackRuntime.playSound(AppSoundCue.TAP)
                            feedbackRuntime.performHaptic(AppHapticCue.SELECTION)
                            if (section == currentSection) {
                                when {
                                    section == AppSection.HOME && isNestedHomeScreen -> requestExitGame = true
                                    section == AppSection.SOCIAL && isNestedSocialScreen -> requestExitGame = true
                                    section == AppSection.SHOP && isShopSubpage -> {
                                        shopPremiumDestinationName = ShopPremiumDestination.OVERVIEW.name
                                    }
                                }
                            }
                            currentSection = section
                            isSettingsOpen = false
                            isVariantToolsOpen = false
                        },
                        bottomMode = bottomMode,
                        topMode = TopLayerMode.OVERLAY,
                        centerMode = CenterLayerMode.TRANSPARENT,
                        backgroundStyle = appBackgroundStyle,
                        illustratedReference = illustratedReference,
                        illustratedBackgroundResourceId = illustratedBackgroundResourceId,
                        topContent = {
                            AppTopBar(
                                energy = progressState.campaignEnergy,
                                energyMax = progressState.campaignEnergyMax,
                                coins = progressState.coins,
                                illustratedReference = illustratedReference,
                                showBack = isHomeSubpage || isShopSubpage || isInGame || isVariantToolsOpen,
                                showShop = !isInGame,
                                onBackClick = {
                                    when {
                                        isVariantToolsOpen -> isVariantToolsOpen = false
                                        isShopSubpage -> {
                                            shopPremiumDestinationName = ShopPremiumDestination.OVERVIEW.name
                                        }
                                        else -> requestExitGame = true
                                    }
                                },
                                onShopClick = {
                                    currentSection = AppSection.SHOP
                                    isSettingsOpen = false
                                },
                                onSettingsClick = {
                                    feedbackRuntime.playSound(AppSoundCue.TAP)
                                    feedbackRuntime.performHaptic(AppHapticCue.SELECTION)
                                    isSettingsOpen = true
                                },
                            )
                        },
                        bottomAdContent = {
                            when (selectedBannerProviderName) {
                                AdProviderId.OWNER_YANDEX.name -> key(adConsentDecisionName) {
                                    YandexGameBanner(
                                        adUnitId = providerServices.gameBannerAdUnitId,
                                        onLoaded = { bannerLoaded = true },
                                        onFailed = {
                                            selectedBannerProviderName = null
                                            bannerLoaded = false
                                            failedBannerProviderNames +=
                                                AdProviderId.OWNER_YANDEX.name
                                        },
                                    )
                                }
                                else -> VariantBottomAdContent(
                                    inspectionValue = currentInspectionValue,
                                    adsDisabled = effectiveProgressState.adsDisabledAt(currentTimeMs),
                                    toolsEnabled = variantToolsEnabled,
                                )
                            }
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
                                    onNestedScreenChange = { nested -> isNestedHomeScreen = nested },
                                    onDebugSecretChange = { currentInspectionValue = it },
                                    openPositionHints = progressState.openPositionHints,
                                    checkDigitHints = progressState.checkDigitHints,
                                    checkPositionHints = progressState.checkPositionHints,
                                    autoModeAvailable = effectiveProgressState.autoTableAssistEnabledAt(currentTimeMs),
                                    infiniteHintsEnabled = effectiveProgressState.infiniteHintsEnabled,
                                    onConsumeOpenPositionHint = {
                                        if (effectiveProgressState.infiniteHintsEnabled) {
                                            true
                                        } else if (progressRepository.consumeHint(HintStockType.OPEN_POSITION)) {
                                            progressState = progressRepository.loadState()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                    onConsumeCheckDigitHint = {
                                        if (effectiveProgressState.infiniteHintsEnabled) {
                                            true
                                        } else if (progressRepository.consumeHint(HintStockType.CHECK_DIGIT)) {
                                            progressState = progressRepository.loadState()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                    onConsumeCheckPositionHint = {
                                        if (effectiveProgressState.infiniteHintsEnabled) {
                                            true
                                        } else if (progressRepository.consumeHint(HintStockType.CHECK_POSITION)) {
                                            progressState = progressRepository.loadState()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                    onWatchRewardedHintAd = { hintType, completed ->
                                        val request = rewardedHintRequest(hintType)
                                        coroutineScope.launch {
                                            providerServices.adRuntime.preload(request)
                                            val presentation = providerServices.adRuntime.show(request)
                                            completed(AdPolicy.canGrantReward(presentation.result))
                                            providerServices.adRuntime.preload(request)
                                        }
                                    },
                                    onMatchStarted = {
                                        progressState = progressRepository.recordMatchStarted()
                                    },
                                    onRecordPveResult = { won ->
                                        progressState = progressRepository.recordModeResult(GameModeStatType.PVE_RACE, won)
                                        adUsageTracker.recordCompletedMatch()
                                        showPostMatchInterstitial()
                                    },
                                    onRecordPvpResult = { won ->
                                        progressState = progressRepository.recordModeResult(GameModeStatType.PVP_DUEL, won)
                                        adUsageTracker.recordCompletedMatch()
                                        showPostMatchInterstitial()
                                    },
                                    onOpenCompany = {
                                        currentSection = AppSection.COMPANY
                                    },
                                    onOpenOnlineMatch = { playStyle, codeLength ->
                                        requestedOnlinePlayStyleName = playStyle.name
                                        requestedOnlineCodeLength = codeLength
                                        currentSection = AppSection.SOCIAL
                                    },
                                    onlineAvailable = onlineRuntime != null,
                                )

                            currentSection == AppSection.SOCIAL -> SocialRootScreen(
                                onlineRuntime = onlineRuntime,
                                initialActiveSessionId = activeOnlineSessionId,
                                onActiveSessionChange = { sessionId ->
                                    if (activeOnlineSessionId != sessionId) {
                                        if (sessionId == null) {
                                            activeOnlineSessionStore.clear()
                                        } else {
                                            activeOnlineSessionStore.write(sessionId)
                                        }
                                        activeOnlineSessionId = sessionId
                                    }
                                },
                                friends = savedFriends,
                                pendingFriendRequests = pendingFriendRequests,
                                currentPlayerId = mirkoriAccountState.gamePlayerId,
                                onSearchPlayers = { query ->
                                    val runtime = mirkoriPlatformRuntime
                                    if (runtime == null) {
                                        MirkoriPlayerSearchResult.Unavailable
                                    } else {
                                        withContext(Dispatchers.IO) { runtime.searchPlayers(query) }
                                    }
                                },
                                onAddFriend = { player ->
                                    val result = mirkoriPlatformRuntime
                                        ?.sendFriendRequest(player.gamePlayerId)
                                        ?: MirkoriFriendOperationResult.Unavailable
                                    if (result is MirkoriFriendOperationResult.Success) {
                                        pendingFriendRequests = withContext(Dispatchers.IO) {
                                            platformLocalRepository.upsertRelationship(
                                                LocalSocialRelationship(
                                                    playerId = localPlayerProfile.playerId,
                                                    targetPlayerId = player.gamePlayerId,
                                                    targetDisplayName = player.displayName,
                                                    relationshipType = LocalRelationshipType.INVITE_OUTGOING,
                                                    status = LocalRelationshipStatus.PENDING,
                                                    source = "platform_friend_request",
                                                    note = player.handle,
                                                ),
                                            )
                                            platformLocalRepository
                                                .loadRelationships(LocalRelationshipStatus.PENDING)
                                                .filter {
                                                    it.relationshipType ==
                                                        LocalRelationshipType.INVITE_OUTGOING
                                                }
                                        }
                                    }
                                    result
                                },
                                incomingFriendRequests = incomingFriendRequests,
                                onAcceptFriendRequest = { request ->
                                    val result = mirkoriPlatformRuntime?.acceptFriendRequest(request.requestId)
                                        ?: MirkoriFriendOperationResult.Unavailable
                                    if (result is MirkoriFriendOperationResult.Success) {
                                        val player = result.request.player
                                        savedFriends = withContext(Dispatchers.IO) {
                                            platformLocalRepository.deleteRelationship(
                                                playerId = localPlayerProfile.playerId,
                                                targetPlayerId = player.gamePlayerId,
                                                relationshipType = LocalRelationshipType.INVITE_OUTGOING,
                                            )
                                            platformLocalRepository.upsertRelationship(
                                                LocalSocialRelationship(
                                                    playerId = localPlayerProfile.playerId,
                                                    targetPlayerId = player.gamePlayerId,
                                                    targetDisplayName = player.displayName,
                                                    relationshipType = LocalRelationshipType.FRIEND,
                                                    status = LocalRelationshipStatus.ACTIVE,
                                                    source = "platform_friendship",
                                                    note = player.handle,
                                                ),
                                            )
                                            platformLocalRepository
                                                .loadRelationships(LocalRelationshipStatus.ACTIVE)
                                                .filter {
                                                    it.relationshipType == LocalRelationshipType.FRIEND &&
                                                        it.source == "platform_friendship"
                                                }
                                        }
                                        pendingFriendRequests = withContext(Dispatchers.IO) {
                                            platformLocalRepository
                                                .loadRelationships(LocalRelationshipStatus.PENDING)
                                                .filter {
                                                    it.relationshipType ==
                                                        LocalRelationshipType.INVITE_OUTGOING
                                                }
                                        }
                                        incomingFriendRequests = incomingFriendRequests.filterNot {
                                            it.requestId == request.requestId
                                        }
                                    }
                                    result
                                },
                                incomingInvites = incomingFriendInvites,
                                showTestFriendBot = testFriendBotEnabled(),
                                requestedQuickMatchPlayStyle = requestedOnlinePlayStyleName?.let(
                                    com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle::valueOf,
                                ),
                                requestedQuickMatchCodeLength = requestedOnlineCodeLength,
                                onQuickMatchRequestConsumed = { requestedOnlinePlayStyleName = null },
                                requestExitGame = requestExitGame,
                                onExitGameConsumed = { requestExitGame = false },
                                onInGameChange = { inGame -> isInGame = inGame },
                                onNestedScreenChange = { nested -> isNestedSocialScreen = nested },
                            )

                            currentSection == AppSection.COMPANY -> CompanyRootScreen(
                                progressState = progressState,
                                campaignProgress = campaignProgress,
                                claimedChapterNumbers = claimedCampaignChapters,
                                activeLevelNumber = companyActiveLevelNumber,
                                onActiveLevelNumberChange = { companyActiveLevelNumber = it },
                                requestExitGame = requestExitGame,
                                onExitGameConsumed = { requestExitGame = false },
                                onInGameChange = { inGame -> isInGame = inGame },
                                onDebugSecretChange = { currentInspectionValue = it },
                                openPositionHints = progressState.openPositionHints,
                                checkDigitHints = progressState.checkDigitHints,
                                checkPositionHints = progressState.checkPositionHints,
                                autoModeAvailable = effectiveProgressState.autoTableAssistEnabledAt(currentTimeMs),
                                infiniteHintsEnabled = effectiveProgressState.infiniteHintsEnabled,
                                extraMovesBoosts = progressState.extraMovesBoosts,
                                extraTimeBoosts = progressState.extraTimeBoosts,
                                onConsumeOpenPositionHint = {
                                    if (effectiveProgressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.OPEN_POSITION)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onConsumeCheckDigitHint = {
                                    if (effectiveProgressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.CHECK_DIGIT)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onConsumeCheckPositionHint = {
                                    if (effectiveProgressState.infiniteHintsEnabled) {
                                        true
                                    } else if (progressRepository.consumeHint(HintStockType.CHECK_POSITION)) {
                                        progressState = progressRepository.loadState()
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onWatchRewardedHintAd = { hintType, completed ->
                                    val request = rewardedHintRequest(hintType)
                                    coroutineScope.launch {
                                        providerServices.adRuntime.preload(request)
                                        val presentation = providerServices.adRuntime.show(request)
                                        completed(AdPolicy.canGrantReward(presentation.result))
                                        providerServices.adRuntime.preload(request)
                                    }
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
                                    val recorded = progressRepository.loadCampaignProgress(level)
                                    campaignProgress = campaignProgress.map { existing ->
                                        if (existing.levelNumber == level) recorded else existing
                                    }
                                    adUsageTracker.recordCompletedMatch()
                                    showPostMatchInterstitial()
                                },
                                onClaimChapterReward = { chapterNumber ->
                                    val claimedState = progressRepository
                                        .claimCampaignChapterReward(chapterNumber)
                                    if (claimedState != null) {
                                        progressState = claimedState
                                        claimedCampaignChapters = claimedCampaignChapters + chapterNumber
                                        true
                                    } else {
                                        false
                                    }
                                },
                                retentionRewardStatus = retentionRewardStatus,
                                onRefreshRetentionRewards = {
                                    retentionRewardStatus = progressRepository.loadRetentionRewardStatus()
                                },
                                onClaimRetentionReward = { type: RetentionRewardType ->
                                    val claimedState = progressRepository.claimRetentionReward(type)
                                    if (claimedState != null) {
                                        progressState = claimedState
                                        retentionRewardStatus = progressRepository.loadRetentionRewardStatus()
                                        true
                                    } else {
                                        retentionRewardStatus = progressRepository.loadRetentionRewardStatus()
                                        false
                                    }
                                },
                                onCampaignTutorialCompleted = {
                                    progressState = progressRepository.completeCampaignTutorial()
                                },
                                onRecordCompanyLoss = {
                                    progressState = progressRepository.recordCompanyLoss()
                                    adUsageTracker.recordCompletedMatch()
                                    showPostMatchInterstitial()
                                },
                                onMatchStarted = {
                                    progressState = progressRepository.recordMatchStarted()
                                },
                            )

                            currentSection == AppSection.SHOP -> ShopRootScreen(
                                progressState = effectiveProgressState,
                                nowMs = currentTimeMs,
                                billingState = billingState,
                                billingInProgress = billingOperation.inProgress,
                                onRefreshBilling = refreshBilling,
                                onOpenProfile = { currentSection = AppSection.PROFILE },
                                onWatchRewardedCoins = { completed ->
                                    val request = AdRequest(
                                        placement = AdPlacement.SHOP_COINS_REWARD,
                                        format = AdFormat.REWARDED,
                                    )
                                    coroutineScope.launch {
                                        providerServices.adRuntime.preload(request)
                                        val presentation = providerServices.adRuntime.show(request)
                                        val rewarded = AdPolicy.canGrantReward(presentation.result)
                                        if (rewarded) {
                                            progressState = progressRepository.grantRewardedCoins(20)
                                        }
                                        completed(rewarded)
                                        providerServices.adRuntime.preload(request)
                                    }
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
                                    purchaseBilling(BillingProductId.REMOVE_ADS)
                                },
                                onBuyPro = {
                                    purchaseBilling(BillingProductId.PRO_SUBSCRIPTION)
                                },
                                onBuyProPlus = {
                                    purchaseBilling(BillingProductId.PRO_PLUS_SUBSCRIPTION)
                                },
                                onRetryBillingPurchase = {
                                    billingState.pendingProduct?.let(purchaseBilling)
                                },
                                premiumDestination = shopPremiumDestination,
                                onPremiumDestinationChange = { destination ->
                                    shopPremiumDestinationName = destination.name
                                },
                                onBuyTemporaryPro = {
                                    val permanentPremiumActive =
                                        billingState.entitlements.proSubscriptionActive ||
                                            billingState.entitlements.proPlusSubscriptionActive
                                    val purchased = progressRepository.buyTemporaryPro(
                                        permanentPremiumActive = permanentPremiumActive,
                                    )
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
                                progressState = effectiveProgressState,
                                nowMs = currentTimeMs,
                                mirkoriAccountState = mirkoriAccountState,
                                mirkoriAuthResultKey = mirkoriAuthResultKey,
                                mirkoriAuthInProgress = mirkoriAuthOperation.inProgress,
                                publicPlayerProfile = publicPlayerProfile,
                                publicProfileResultKey = publicProfileResultKey,
                                publicProfileInProgress = publicProfileOperation.inProgress,
                                authResultKey = profileAuthResultKey,
                                authInProgress = profileAuthOperation.inProgress,
                                showGooglePlayCard = googleProfileActionsEnabled(),
                                onMirkoriSignIn = {
                                    mirkoriAuthOperation.start()?.let { operationId ->
                                        mirkoriAuthResultKey = null
                                        coroutineScope.launch {
                                            try {
                                                val result = if (mirkoriPlatformRuntime == null) {
                                                    MirkoriLoginResult.Unavailable
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        mirkoriPlatformRuntime.beginLogin()
                                                    }
                                                }
                                                when (result) {
                                                    is MirkoriLoginResult.BrowserReady -> {
                                                        startActivity(
                                                            Intent(Intent.ACTION_VIEW, Uri.parse(result.connectUrl)),
                                                        )
                                                        mirkoriAuthResultKey = "profile.mirkori.browser_opened"
                                                    }
                                                    is MirkoriLoginResult.Connected -> {
                                                        mirkoriAccountState = result.accountState
                                                        mirkoriAuthResultKey = "profile.mirkori.connected.success"
                                                    }
                                                    MirkoriLoginResult.AlreadyConnected ->
                                                        mirkoriAuthResultKey = "profile.mirkori.connected.success"
                                                    MirkoriLoginResult.ProfileConflict ->
                                                        mirkoriAuthResultKey = "profile.mirkori.conflict"
                                                    MirkoriLoginResult.Rejected ->
                                                        mirkoriAuthResultKey = "profile.mirkori.rejected"
                                                    MirkoriLoginResult.Unavailable ->
                                                        mirkoriAuthResultKey = "profile.mirkori.unavailable"
                                                    is MirkoriLoginResult.GoogleCredentialRequired ->
                                                        mirkoriAuthResultKey = "profile.mirkori.rejected"
                                                }
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                AppLog.warn(
                                                    tag = "MainActivity",
                                                    message = "Mirkori Games sign-in operation failed",
                                                    attributes = mapOf("errorClass" to error.javaClass.name),
                                                )
                                                mirkoriAuthResultKey = "profile.mirkori.unavailable"
                                            } finally {
                                                mirkoriAccountState = mirkoriPlatformRuntime?.currentAccountState()
                                                    ?: MirkoriAccountState(MirkoriAccountStateKind.UNAVAILABLE)
                                                mirkoriAuthOperation.finish(operationId)
                                            }
                                        }
                                    }
                                },
                                onPublicHandleChange = { handle ->
                                    publicProfileOperation.start()?.let { operationId ->
                                        publicProfileResultKey = null
                                        coroutineScope.launch {
                                            try {
                                                val result = if (mirkoriPlatformRuntime == null) {
                                                    MirkoriPublicProfileResult.Unavailable
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        mirkoriPlatformRuntime.updatePublicHandle(
                                                            handle = handle,
                                                            displayName = progressState.playerDisplayName,
                                                        )
                                                    }
                                                }
                                                publicProfileResultKey = when (result) {
                                                    is MirkoriPublicProfileResult.Success -> {
                                                        publicPlayerProfile = result.profile
                                                        "profile.mirkori.handle.saved"
                                                    }
                                                    MirkoriPublicProfileResult.HandleTaken ->
                                                        "profile.mirkori.handle.taken"
                                                    MirkoriPublicProfileResult.Rejected ->
                                                        "profile.mirkori.handle.invalid"
                                                    MirkoriPublicProfileResult.Unavailable ->
                                                        "profile.mirkori.handle.unavailable"
                                                }
                                            } finally {
                                                publicProfileOperation.finish(operationId)
                                            }
                                        }
                                    }
                                },
                                onDisplayNameChange = { displayName ->
                                    publicProfileOperation.start()?.let { operationId ->
                                        publicProfileResultKey = null
                                        coroutineScope.launch {
                                            try {
                                                val result = if (mirkoriPlatformRuntime == null) {
                                                    MirkoriPublicProfileResult.Unavailable
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        mirkoriPlatformRuntime.updatePublicProfile(
                                                            displayName = displayName,
                                                        )
                                                    }
                                                }
                                                publicProfileResultKey = when (result) {
                                                    is MirkoriPublicProfileResult.Success -> {
                                                        publicPlayerProfile = result.profile
                                                        progressState = progressRepository
                                                            .updatePlayerDisplayName(result.profile.displayName)
                                                        "profile.mirkori.name.saved"
                                                    }
                                                    MirkoriPublicProfileResult.Rejected ->
                                                        "profile.mirkori.name.invalid"
                                                    MirkoriPublicProfileResult.HandleTaken,
                                                    MirkoriPublicProfileResult.Unavailable ->
                                                        "profile.mirkori.name.unavailable"
                                                }
                                            } finally {
                                                publicProfileOperation.finish(operationId)
                                            }
                                        }
                                    }
                                },
                                onAvatarChange = { avatarKey ->
                                    publicProfileOperation.start()?.let { operationId ->
                                        publicProfileResultKey = null
                                        coroutineScope.launch {
                                            try {
                                                val result = if (mirkoriPlatformRuntime == null) {
                                                    MirkoriPublicProfileResult.Unavailable
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        mirkoriPlatformRuntime.updatePublicProfile(
                                                            avatarKey = avatarKey,
                                                        )
                                                    }
                                                }
                                                publicProfileResultKey = when (result) {
                                                    is MirkoriPublicProfileResult.Success -> {
                                                        publicPlayerProfile = result.profile
                                                        "profile.mirkori.avatar.saved"
                                                    }
                                                    MirkoriPublicProfileResult.Rejected ->
                                                        "profile.mirkori.avatar.invalid"
                                                    MirkoriPublicProfileResult.HandleTaken,
                                                    MirkoriPublicProfileResult.Unavailable ->
                                                        "profile.mirkori.avatar.unavailable"
                                                }
                                            } finally {
                                                publicProfileOperation.finish(operationId)
                                            }
                                        }
                                    }
                                },
                                onGooglePlaySignIn = {
                                    profileAuthOperation.start()?.let { operationId ->
                                        profileAuthResultKey = null
                                        coroutineScope.launch {
                                            try {
                                                val runtime = mirkoriPlatformRuntime
                                                val preparation = if (runtime == null) {
                                                    MirkoriLoginResult.Unavailable
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        runtime.beginGoogleLogin()
                                                    }
                                                }
                                                profileAuthResultKey = when (preparation) {
                                                    is MirkoriLoginResult.GoogleCredentialRequired -> {
                                                        when (
                                                            val credentialResult = googleCredentialSignIn.signIn(
                                                                activity = this@MainActivity,
                                                                nonce = preparation.nonce,
                                                            )
                                                        ) {
                                                            is GoogleCredentialResult.Success -> {
                                                                val completed = if (runtime == null) {
                                                                    MirkoriLoginResult.Unavailable
                                                                } else {
                                                                    withContext(Dispatchers.IO) {
                                                                        runtime.completeGoogleLogin(
                                                                            credentialResult.credential.idToken,
                                                                        )
                                                                    }
                                                                }
                                                                when (completed) {
                                                                    is MirkoriLoginResult.Connected -> {
                                                                        mirkoriAccountState = completed.accountState
                                                                        if (completed.accountState.authMode == PlatformAuthMode.GOOGLE) {
                                                                            progressState = progressRepository.signInWithGooglePlay(
                                                                                credentialResult.credential.playerName
                                                                                    ?: progressState.playerDisplayName,
                                                                            )
                                                                            "profile.auth.signed_in"
                                                                        } else {
                                                                            "profile.auth.rejected"
                                                                        }
                                                                    }
                                                                    MirkoriLoginResult.AlreadyConnected -> {
                                                                        if (mirkoriAccountState.authMode == PlatformAuthMode.GOOGLE) {
                                                                            progressState = progressRepository.signInWithGooglePlay(
                                                                                credentialResult.credential.playerName
                                                                                    ?: progressState.playerDisplayName,
                                                                            )
                                                                            "profile.auth.signed_in"
                                                                        } else {
                                                                            "profile.auth.rejected"
                                                                        }
                                                                    }
                                                                    MirkoriLoginResult.ProfileConflict -> {
                                                                        pendingGoogleProfileConflict =
                                                                            credentialResult.credential
                                                                        "profile.mirkori.conflict"
                                                                    }
                                                                    MirkoriLoginResult.Rejected,
                                                                    is MirkoriLoginResult.BrowserReady,
                                                                    is MirkoriLoginResult.GoogleCredentialRequired,
                                                                    -> "profile.auth.rejected"
                                                                    MirkoriLoginResult.Unavailable -> "profile.auth.unavailable"
                                                                }
                                                            }
                                                            GoogleCredentialResult.Cancelled -> "profile.auth.cancelled"
                                                            GoogleCredentialResult.Unavailable -> "profile.auth.not_configured"
                                                            GoogleCredentialResult.Failed -> "profile.auth.rejected"
                                                        }
                                                    }
                                                    is MirkoriLoginResult.Connected -> {
                                                        mirkoriAccountState = preparation.accountState
                                                        if (preparation.accountState.authMode == PlatformAuthMode.GOOGLE) {
                                                            progressState = progressRepository.signInWithGooglePlay(
                                                                progressState.playerDisplayName,
                                                            )
                                                            "profile.auth.signed_in"
                                                        } else {
                                                            "profile.auth.rejected"
                                                        }
                                                    }
                                                    MirkoriLoginResult.AlreadyConnected -> {
                                                        if (mirkoriAccountState.authMode == PlatformAuthMode.GOOGLE) {
                                                            progressState = progressRepository.signInWithGooglePlay(
                                                                progressState.playerDisplayName,
                                                            )
                                                            "profile.auth.signed_in"
                                                        } else {
                                                            "profile.auth.rejected"
                                                        }
                                                    }
                                                    MirkoriLoginResult.ProfileConflict,
                                                    MirkoriLoginResult.Rejected,
                                                    is MirkoriLoginResult.BrowserReady,
                                                    -> "profile.auth.rejected"
                                                    MirkoriLoginResult.Unavailable -> "profile.auth.unavailable"
                                                }
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                AppLog.warn(
                                                    tag = "MainActivity",
                                                    message = "Google sign-in operation failed",
                                                    throwable = error,
                                                )
                                                profileAuthResultKey = "profile.auth.unavailable"
                                            } finally {
                                                profileAuthOperation.finish(operationId)
                                            }
                                        }
                                    }
                                },
                                onGooglePlaySignOut = {
                                    profileAuthOperation.start()?.let { operationId ->
                                        coroutineScope.launch {
                                            try {
                                                googleCredentialSignIn.signOut()
                                                activeOnlineSessionStore.clear()
                                                activeOnlineSessionId = null
                                                progressState = progressRepository.signOutFromGooglePlay()
                                                profileAuthResultKey = "profile.auth.signed_out"
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                AppLog.warn(
                                                    tag = "MainActivity",
                                                    message = "Google sign-out operation failed",
                                                    throwable = error,
                                                )
                                                profileAuthResultKey = "profile.auth.unavailable"
                                            } finally {
                                                profileAuthOperation.finish(operationId)
                                            }
                                        }
                                    }
                                },
                                onOpenShop = { currentSection = AppSection.SHOP },
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

                        pendingGoogleProfileConflict?.let { credential ->
                            GoogleProfileConflictDialog(
                                strings = strings,
                                busy = profileAuthOperation.inProgress,
                                onUseExistingProfile = {
                                    profileAuthOperation.start()?.let { operationId ->
                                        profileAuthResultKey = "profile.auth.in_progress"
                                        coroutineScope.launch {
                                            try {
                                                val runtime = mirkoriPlatformRuntime
                                                val completed = if (runtime == null) {
                                                    MirkoriLoginResult.Unavailable
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        runtime.completeGoogleLogin(
                                                            idToken = credential.idToken,
                                                            conflictResolution =
                                                                PlatformProfileConflictResolution.USE_EXISTING_PROFILE,
                                                        )
                                                    }
                                                }
                                                if (!profileAuthOperation.isCurrent(operationId)) return@launch
                                                profileAuthResultKey = when (completed) {
                                                    is MirkoriLoginResult.Connected -> {
                                                        if (
                                                            mirkoriAccountState.gamePlayerId != null &&
                                                            mirkoriAccountState.gamePlayerId !=
                                                            completed.accountState.gamePlayerId
                                                        ) {
                                                            withContext(Dispatchers.IO) {
                                                                platformLocalRepository.replaceRelationships(
                                                                    playerId = localPlayerProfile.playerId,
                                                                    relationshipType =
                                                                        LocalRelationshipType.FRIEND,
                                                                    relationships = emptyList(),
                                                                )
                                                                platformLocalRepository.replaceRelationships(
                                                                    playerId = localPlayerProfile.playerId,
                                                                    relationshipType =
                                                                        LocalRelationshipType.INVITE_OUTGOING,
                                                                    relationships = emptyList(),
                                                                )
                                                            }
                                                            savedFriends = emptyList()
                                                            pendingFriendRequests = emptyList()
                                                        }
                                                        mirkoriAccountState = completed.accountState
                                                        progressState = progressRepository.signInWithGooglePlay(
                                                            credential.playerName
                                                                ?: progressState.playerDisplayName,
                                                        )
                                                        "profile.auth.signed_in"
                                                    }
                                                    MirkoriLoginResult.AlreadyConnected ->
                                                        "profile.auth.signed_in"
                                                    MirkoriLoginResult.ProfileConflict ->
                                                        "profile.mirkori.conflict"
                                                    MirkoriLoginResult.Rejected,
                                                    is MirkoriLoginResult.BrowserReady,
                                                    is MirkoriLoginResult.GoogleCredentialRequired,
                                                    -> "profile.auth.rejected"
                                                    MirkoriLoginResult.Unavailable ->
                                                        "profile.auth.unavailable"
                                                }
                                                if (completed !is MirkoriLoginResult.Connected) {
                                                    withContext(Dispatchers.IO) {
                                                        runtime?.cancelPendingLogin()
                                                    }
                                                }
                                            } catch (error: Exception) {
                                                if (error is CancellationException) throw error
                                                AppLog.warn(
                                                    tag = "MainActivity",
                                                    message = "Confirmed Google profile sign-in failed",
                                                    throwable = error,
                                                )
                                                profileAuthResultKey = "profile.auth.unavailable"
                                                withContext(Dispatchers.IO) {
                                                    mirkoriPlatformRuntime?.cancelPendingLogin()
                                                }
                                            } finally {
                                                if (profileAuthOperation.finish(operationId)) {
                                                    pendingGoogleProfileConflict = null
                                                }
                                            }
                                        }
                                    }
                                },
                                onKeepCurrentProfile = {
                                    profileAuthOperation.start()?.let { operationId ->
                                        coroutineScope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    mirkoriPlatformRuntime?.cancelPendingLogin()
                                                }
                                                if (profileAuthOperation.isCurrent(operationId)) {
                                                    profileAuthResultKey =
                                                        "profile.google.conflict.kept"
                                                }
                                            } finally {
                                                if (profileAuthOperation.finish(operationId)) {
                                                    pendingGoogleProfileConflict = null
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }

                        if (isSettingsOpen) {
                            SettingsRootScreen(
                                currentLanguage = currentLanguage,
                                adConsentDecision = AdConsentDecision.valueOf(adConsentDecisionName),
                                feedbackSettings = feedbackSettings,
                                onLanguageChange = { language ->
                                    currentLanguageName = language.name
                                },
                                onVibrationChange = { enabled ->
                                    feedbackSettings = feedbackSettings.copy(vibrationEnabled = enabled)
                                    feedbackSettingsStore.write(feedbackSettings)
                                    feedbackRuntime.updateSettings(feedbackSettings)
                                    if (enabled) feedbackRuntime.performHaptic(AppHapticCue.CONFIRM)
                                },
                                onSoundChange = { enabled ->
                                    feedbackSettings = feedbackSettings.copy(soundEnabled = enabled)
                                    feedbackSettingsStore.write(feedbackSettings)
                                    feedbackRuntime.updateSettings(feedbackSettings)
                                    if (enabled) feedbackRuntime.playSound(AppSoundCue.CONFIRM)
                                },
                                onMusicChange = { enabled ->
                                    feedbackSettings = feedbackSettings.copy(musicEnabled = enabled)
                                    feedbackSettingsStore.write(feedbackSettings)
                                    feedbackRuntime.updateSettings(feedbackSettings)
                                },
                                onOpenAdPrivacy = {
                                    isSettingsOpen = false
                                    coroutineScope.launch {
                                        providerServices.adRuntime.showProviderPrivacyOptions()
                                        isAdPrivacyOpen = true
                                    }
                                },
                                onOpenWebsitePage = { page ->
                                    feedbackRuntime.playSound(AppSoundCue.TAP)
                                    feedbackRuntime.performHaptic(AppHapticCue.SELECTION)
                                    MirkoriWebsiteLauncher.open(this@MainActivity, page)
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

                        if (isAdPrivacyOpen) {
                            AdPrivacyConsentDialog(
                                onAccept = {
                                    providerServices.adConsent.updateDecision(
                                        AdConsentDecision.ACCEPTED,
                                    )
                                    selectedBannerProviderName = null
                                    bannerLoaded = false
                                    coroutineScope.launch {
                                        providerServices.adRuntime.onConsentChanged(
                                            AdConsentDecision.ACCEPTED,
                                        )
                                        adConsentDecisionName = AdConsentDecision.ACCEPTED.name
                                        isAdPrivacyOpen = false
                                    }
                                },
                                onDecline = {
                                    providerServices.adConsent.updateDecision(
                                        AdConsentDecision.DECLINED,
                                    )
                                    selectedBannerProviderName = null
                                    bannerLoaded = false
                                    coroutineScope.launch {
                                        providerServices.adRuntime.onConsentChanged(
                                            AdConsentDecision.DECLINED,
                                        )
                                        adConsentDecisionName = AdConsentDecision.DECLINED.name
                                        isAdPrivacyOpen = false
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    }

    override fun onPause() {
        if (::feedbackRuntime.isInitialized) feedbackRuntime.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        if (::feedbackRuntime.isInitialized) feedbackRuntime.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureMirkoriCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::feedbackRuntime.isInitialized) feedbackRuntime.onForeground()
        resumeGeneration += 1L
    }

    override fun onStart() {
        super.onStart()
        adUsageTracker.onForeground()
    }

    override fun onStop() {
        adUsageTracker.onBackground()
        super.onStop()
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

    private fun captureMirkoriCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (
            data.scheme.equals("https", ignoreCase = true) &&
            data.host.equals("games.dmit.life", ignoreCase = true) &&
            data.path == "/connect/inplacex/callback"
        ) {
            mirkoriCallbackUrl = data.toString()
        }
    }

    private fun consumeMirkoriCallback() {
        mirkoriCallbackUrl = null
        setIntent(Intent(intent).setData(null))
    }
}

internal fun initialSectionForActiveOnlineSession(sessionId: String?): AppSection =
    if (sessionId == null) AppSection.HOME else AppSection.SOCIAL

internal fun GameProgressState.withServerPaidEntitlements(
    entitlements: MonetizationEntitlements,
): GameProgressState = variantPaidProgressState(this, entitlements)

internal fun GameProgressState.effectiveMonetizationEntitlements(
    serverEntitlements: MonetizationEntitlements,
    nowMs: Long,
): MonetizationEntitlements {
    val paidProgress = withServerPaidEntitlements(serverEntitlements)
    return MonetizationEntitlements(
        adFreePurchased = paidProgress.adFreePurchased,
        proSubscriptionActive = paidProgress.proSubscriptionActive || temporaryProActiveAt(nowMs),
        proPlusSubscriptionActive = paidProgress.proPlusSubscriptionActive,
    )
}

internal fun isExternalHttpsCheckoutUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val isLoginCallback = uri.host.equals("games.dmit.life", ignoreCase = true) &&
        uri.path == "/connect/inplacex/callback"
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65_535) &&
        !isLoginCallback
}.getOrDefault(false)

private fun rewardedHintRequest(hintType: HintStockType): AdRequest =
    AdRequest(
        placement = when (hintType) {
            HintStockType.OPEN_POSITION -> AdPlacement.GAME_OPEN_POSITION_HINT
            HintStockType.CHECK_DIGIT -> AdPlacement.GAME_CHECK_DIGIT_HINT
            HintStockType.CHECK_POSITION -> AdPlacement.GAME_CHECK_POSITION_HINT
        },
        format = AdFormat.REWARDED,
    )

private const val BannerRetryDelayMillis = 30_000L
private const val IncomingInvitePollMillis = 5_000L
