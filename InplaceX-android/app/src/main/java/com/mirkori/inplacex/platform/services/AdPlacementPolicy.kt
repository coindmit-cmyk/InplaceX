package com.mirkori.inplacex.platform.services

import com.mirkori.inplacex.ads.AdDecision
import com.mirkori.inplacex.ads.AdEntitlements
import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdPlacement
import com.mirkori.inplacex.ads.AdPolicy
import com.mirkori.inplacex.ads.AdRequest

const val GAME_BANNER_SLOT_ID = "game"

object AdPlacementPolicy {
    fun canRequestGameBanner(
        isInGame: Boolean,
        entitlements: MonetizationEntitlements,
    ): Boolean =
        isInGame &&
            AdPolicy.evaluate(
                request = AdRequest(
                    placement = AdPlacement.GAME_BANNER,
                    format = AdFormat.BANNER,
                ),
                entitlements = entitlements.toAdEntitlements(),
            ) == AdDecision.Allowed

    fun shouldShowPostMatchInterstitial(
        matchesPlayed: Int,
        entitlements: MonetizationEntitlements,
    ): Boolean =
        AdPolicy.evaluate(
            request = AdRequest(
                placement = AdPlacement.POST_MATCH_INTERSTITIAL,
                format = AdFormat.INTERSTITIAL,
                matchesPlayed = matchesPlayed,
            ),
            entitlements = entitlements.toAdEntitlements(),
        ) == AdDecision.Allowed

    fun shouldReserveGameBanner(
        isInGame: Boolean,
        entitlements: MonetizationEntitlements,
        providerAccepted: Boolean,
    ): Boolean =
        canRequestGameBanner(isInGame, entitlements) && providerAccepted

    private fun MonetizationEntitlements.toAdEntitlements(): AdEntitlements =
        AdEntitlements(adsDisabled = adsDisabled)
}
