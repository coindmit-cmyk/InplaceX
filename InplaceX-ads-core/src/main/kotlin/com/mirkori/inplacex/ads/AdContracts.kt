package com.mirkori.inplacex.ads

enum class AdPlacement(val wireName: String) {
    GAME_BANNER("game_banner"),
    GAME_OPEN_POSITION_HINT("game_open_position_hint"),
    GAME_CHECK_DIGIT_HINT("game_check_digit_hint"),
    GAME_CHECK_POSITION_HINT("game_check_position_hint"),
    SHOP_COINS_REWARD("shop_coins_reward"),
    POST_MATCH_INTERSTITIAL("post_match_interstitial"),
}

enum class AdFormat {
    BANNER,
    REWARDED,
    INTERSTITIAL,
}

enum class AdMarket {
    RUSSIA,
    GLOBAL,
    UNKNOWN,
}

enum class RevenueBeneficiary {
    OWNER,
    COMPANY,
}

enum class AdProviderId(
    val beneficiary: RevenueBeneficiary,
) {
    OWNER_YANDEX(RevenueBeneficiary.OWNER),
    COMPANY_ADMOB(RevenueBeneficiary.COMPANY),
    COMPANY_HUAWEI(RevenueBeneficiary.COMPANY),
}

data class AdEntitlements(
    val adsDisabled: Boolean,
)

data class AdRequest(
    val placement: AdPlacement,
    val format: AdFormat,
    val matchesPlayed: Int = 0,
    val foregroundUsageSeconds: Long = 0,
    val matchesSinceLastInterstitial: Int? = null,
) {
    init {
        require(matchesPlayed >= 0)
        require(foregroundUsageSeconds >= 0)
        require(matchesSinceLastInterstitial == null || matchesSinceLastInterstitial >= 0)
    }
}

data class PostMatchInterstitialPolicy(
    val minimumCompletedMatches: Int = AdPolicy.MinimumMatchesBeforeInterstitial,
    val minimumForegroundUsageSeconds: Long = 0,
    val gamesBetweenImpressions: Int = AdPolicy.InterstitialCadence,
) {
    init {
        require(minimumCompletedMatches >= 0)
        require(minimumForegroundUsageSeconds >= 0)
        require(gamesBetweenImpressions > 0)
    }
}

enum class AdBlockReason {
    ENTITLEMENT,
    PLACEMENT_FORMAT_MISMATCH,
    EARLY_PLAYER,
    USAGE_TIME,
    CADENCE,
}

sealed interface AdDecision {
    data object Allowed : AdDecision
    data class Blocked(val reason: AdBlockReason) : AdDecision
}

sealed interface AdPresentationResult {
    data object Completed : AdPresentationResult
    data object Dismissed : AdPresentationResult
    data object NotReady : AdPresentationResult
    data object ProviderUnavailable : AdPresentationResult
    data object Failed : AdPresentationResult
}

enum class AdPreloadResult {
    READY,
    ALREADY_READY,
    PROVIDER_UNAVAILABLE,
    FAILED,
}

interface AdProvider {
    val id: AdProviderId

    suspend fun preload(request: AdRequest): AdPreloadResult

    suspend fun show(request: AdRequest): AdPresentationResult
}

data class AdRuntimeContext(
    val market: AdMarket,
    val availableProviders: List<AdProviderId>,
) {
    init {
        require(availableProviders.distinct().size == availableProviders.size)
    }
}

data class AdRoutePlan(
    val providers: List<AdProviderId>,
)

fun interface AdRoutingPolicy {
    fun plan(
        context: AdRuntimeContext,
        request: AdRequest,
    ): AdRoutePlan
}

object OwnerFirstAdRoutingPolicy : AdRoutingPolicy {
    override fun plan(
        context: AdRuntimeContext,
        request: AdRequest,
    ): AdRoutePlan {
        if (context.market == AdMarket.UNKNOWN) {
            return AdRoutePlan(emptyList())
        }

        val providers = context.availableProviders
            .withIndex()
            .filter { (_, providerId) -> providerId.supports(context.market) }
            .sortedWith(
                compareBy<IndexedValue<AdProviderId>>(
                    { it.value.beneficiary.routePriority },
                    { it.index },
                ),
            )
            .map { it.value }

        return AdRoutePlan(providers)
    }

    private fun AdProviderId.supports(market: AdMarket): Boolean = when (this) {
        AdProviderId.OWNER_YANDEX -> market == AdMarket.RUSSIA
        AdProviderId.COMPANY_ADMOB,
        AdProviderId.COMPANY_HUAWEI,
        -> false
    }

    private val RevenueBeneficiary.routePriority: Int
        get() = when (this) {
            RevenueBeneficiary.OWNER -> 0
            RevenueBeneficiary.COMPANY -> 1
        }
}

data class AdPreloadAttempt(
    val providerId: AdProviderId,
    val result: AdPreloadResult,
)

data class AdPresentationAttempt(
    val providerId: AdProviderId,
    val result: AdPresentationResult,
)

data class RoutedAdPresentation(
    val result: AdPresentationResult,
    val shownBy: AdProviderId?,
    val attempts: List<AdPresentationAttempt>,
) {
    val beneficiary: RevenueBeneficiary?
        get() = shownBy?.beneficiary
}

class AdRouter(
    providers: Collection<AdProvider>,
    private val routingPolicy: AdRoutingPolicy = OwnerFirstAdRoutingPolicy,
) {
    private val providersById = providers.associateBy(AdProvider::id)

    init {
        require(providersById.size == providers.size)
    }

    fun plan(
        context: AdRuntimeContext,
        request: AdRequest,
    ): AdRoutePlan = routingPolicy.plan(context, request)

    suspend fun preload(
        providerId: AdProviderId,
        request: AdRequest,
    ): AdPreloadAttempt {
        val provider = providersById[providerId]
        return AdPreloadAttempt(
            providerId = providerId,
            result = provider?.preload(request) ?: AdPreloadResult.PROVIDER_UNAVAILABLE,
        )
    }

    suspend fun show(
        context: AdRuntimeContext,
        request: AdRequest,
    ): RoutedAdPresentation {
        val attempts = mutableListOf<AdPresentationAttempt>()
        for (providerId in plan(context, request).providers) {
            val result = providersById[providerId]?.show(request)
                ?: AdPresentationResult.ProviderUnavailable
            attempts += AdPresentationAttempt(providerId, result)
            if (result == AdPresentationResult.Completed || result == AdPresentationResult.Dismissed) {
                return RoutedAdPresentation(
                    result = result,
                    shownBy = providerId,
                    attempts = attempts,
                )
            }
        }

        return RoutedAdPresentation(
            result = attempts.lastOrNull()?.result ?: AdPresentationResult.ProviderUnavailable,
            shownBy = null,
            attempts = attempts,
        )
    }
}

object AdPolicy {
    fun evaluate(
        request: AdRequest,
        entitlements: AdEntitlements,
        interstitialPolicy: PostMatchInterstitialPolicy = PostMatchInterstitialPolicy(),
    ): AdDecision {
        if (expectedFormat(request.placement) != request.format) {
            return AdDecision.Blocked(AdBlockReason.PLACEMENT_FORMAT_MISMATCH)
        }
        if (entitlements.adsDisabled && request.format != AdFormat.REWARDED) {
            return AdDecision.Blocked(AdBlockReason.ENTITLEMENT)
        }
        if (request.placement == AdPlacement.POST_MATCH_INTERSTITIAL) {
            if (request.matchesPlayed < interstitialPolicy.minimumCompletedMatches) {
                return AdDecision.Blocked(AdBlockReason.EARLY_PLAYER)
            }
            if (request.foregroundUsageSeconds < interstitialPolicy.minimumForegroundUsageSeconds) {
                return AdDecision.Blocked(AdBlockReason.USAGE_TIME)
            }
            val cadenceReached = request.matchesSinceLastInterstitial?.let {
                it >= interstitialPolicy.gamesBetweenImpressions
            } ?: (
                (request.matchesPlayed - interstitialPolicy.minimumCompletedMatches) %
                    interstitialPolicy.gamesBetweenImpressions == 0
                )
            if (!cadenceReached) {
                return AdDecision.Blocked(AdBlockReason.CADENCE)
            }
        }
        return AdDecision.Allowed
    }

    fun canGrantReward(result: AdPresentationResult): Boolean =
        result == AdPresentationResult.Completed

    private fun expectedFormat(placement: AdPlacement): AdFormat = when (placement) {
        AdPlacement.GAME_BANNER -> AdFormat.BANNER
        AdPlacement.GAME_OPEN_POSITION_HINT,
        AdPlacement.GAME_CHECK_DIGIT_HINT,
        AdPlacement.GAME_CHECK_POSITION_HINT,
        AdPlacement.SHOP_COINS_REWARD,
        -> AdFormat.REWARDED
        AdPlacement.POST_MATCH_INTERSTITIAL -> AdFormat.INTERSTITIAL
    }

    const val MinimumMatchesBeforeInterstitial = 20
    const val InterstitialCadence = 4
}
