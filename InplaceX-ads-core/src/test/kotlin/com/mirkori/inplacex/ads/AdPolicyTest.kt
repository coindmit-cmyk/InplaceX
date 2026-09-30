package com.mirkori.inplacex.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPolicyTest {
    @Test
    fun `entitlements block every format before provider invocation`() {
        AdPlacement.values().forEach { placement ->
            val format = when (placement) {
                AdPlacement.GAME_BANNER -> AdFormat.BANNER
                AdPlacement.POST_MATCH_INTERSTITIAL -> AdFormat.INTERSTITIAL
                else -> AdFormat.REWARDED
            }
            assertEquals(
                AdDecision.Blocked(AdBlockReason.ENTITLEMENT),
                AdPolicy.evaluate(
                    AdRequest(placement, format, matchesPlayed = 24),
                    AdEntitlements(adsDisabled = true),
                ),
            )
        }
    }

    @Test
    fun `post match interstitial remains sparse after onboarding`() {
        val entitlements = AdEntitlements(adsDisabled = false)

        assertEquals(
            AdDecision.Blocked(AdBlockReason.EARLY_PLAYER),
            AdPolicy.evaluate(
                AdRequest(AdPlacement.POST_MATCH_INTERSTITIAL, AdFormat.INTERSTITIAL, 19),
                entitlements,
            ),
        )
        assertEquals(
            AdDecision.Allowed,
            AdPolicy.evaluate(
                AdRequest(AdPlacement.POST_MATCH_INTERSTITIAL, AdFormat.INTERSTITIAL, 20),
                entitlements,
            ),
        )
        assertEquals(
            AdDecision.Blocked(AdBlockReason.CADENCE),
            AdPolicy.evaluate(
                AdRequest(AdPlacement.POST_MATCH_INTERSTITIAL, AdFormat.INTERSTITIAL, 21),
                entitlements,
            ),
        )
    }

    @Test
    fun `reward is granted only after completed provider result`() {
        assertTrue(AdPolicy.canGrantReward(AdPresentationResult.Completed))
        assertFalse(AdPolicy.canGrantReward(AdPresentationResult.Dismissed))
        assertFalse(AdPolicy.canGrantReward(AdPresentationResult.NotReady))
        assertFalse(AdPolicy.canGrantReward(AdPresentationResult.ProviderUnavailable))
        assertFalse(AdPolicy.canGrantReward(AdPresentationResult.Failed))
    }
}
