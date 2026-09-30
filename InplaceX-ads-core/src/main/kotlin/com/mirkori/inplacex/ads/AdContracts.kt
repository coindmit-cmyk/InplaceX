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

data class AdEntitlements(
    val adsDisabled: Boolean,
)

data class AdRequest(
    val placement: AdPlacement,
    val format: AdFormat,
    val matchesPlayed: Int = 0,
) {
    init {
        require(matchesPlayed >= 0)
    }
}

enum class AdBlockReason {
    ENTITLEMENT,
    PLACEMENT_FORMAT_MISMATCH,
    EARLY_PLAYER,
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

fun interface AdProvider {
    suspend fun show(request: AdRequest): AdPresentationResult
}

object AdPolicy {
    fun evaluate(
        request: AdRequest,
        entitlements: AdEntitlements,
    ): AdDecision {
        if (entitlements.adsDisabled) {
            return AdDecision.Blocked(AdBlockReason.ENTITLEMENT)
        }
        if (expectedFormat(request.placement) != request.format) {
            return AdDecision.Blocked(AdBlockReason.PLACEMENT_FORMAT_MISMATCH)
        }
        if (request.placement == AdPlacement.POST_MATCH_INTERSTITIAL) {
            if (request.matchesPlayed < MinimumMatchesBeforeInterstitial) {
                return AdDecision.Blocked(AdBlockReason.EARLY_PLAYER)
            }
            if (request.matchesPlayed % InterstitialCadence != 0) {
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
