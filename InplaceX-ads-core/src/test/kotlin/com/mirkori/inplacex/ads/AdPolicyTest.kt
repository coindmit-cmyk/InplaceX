package com.mirkori.inplacex.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class AdPolicyTest {
    @Test
    fun `ad-free entitlements block forced ads but keep opt-in rewards available`() {
        AdPlacement.values().forEach { placement ->
            val format = when (placement) {
                AdPlacement.GAME_BANNER -> AdFormat.BANNER
                AdPlacement.POST_MATCH_INTERSTITIAL -> AdFormat.INTERSTITIAL
                else -> AdFormat.REWARDED
            }
            val decision = AdPolicy.evaluate(
                AdRequest(placement, format, matchesPlayed = 24),
                AdEntitlements(adsDisabled = true),
            )
            if (format == AdFormat.REWARDED) {
                assertEquals(AdDecision.Allowed, decision)
            } else {
                assertEquals(AdDecision.Blocked(AdBlockReason.ENTITLEMENT), decision)
            }
        }
    }

    @Test
    fun `format mismatch remains blocked for ad-free rewarded placements`() {
        assertEquals(
            AdDecision.Blocked(AdBlockReason.PLACEMENT_FORMAT_MISMATCH),
            AdPolicy.evaluate(
                request = AdRequest(
                    placement = AdPlacement.SHOP_COINS_REWARD,
                    format = AdFormat.BANNER,
                ),
                entitlements = AdEntitlements(adsDisabled = true),
            ),
        )
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
    fun `post match interstitial requires configured foreground usage and games since impression`() {
        val request = AdRequest(
            placement = AdPlacement.POST_MATCH_INTERSTITIAL,
            format = AdFormat.INTERSTITIAL,
            matchesPlayed = 30,
            foregroundUsageSeconds = 599,
            matchesSinceLastInterstitial = 4,
        )
        val policy = PostMatchInterstitialPolicy(
            minimumCompletedMatches = 20,
            minimumForegroundUsageSeconds = 600,
            gamesBetweenImpressions = 4,
        )

        assertEquals(
            AdDecision.Blocked(AdBlockReason.USAGE_TIME),
            AdPolicy.evaluate(request, AdEntitlements(adsDisabled = false), policy),
        )
        assertEquals(
            AdDecision.Blocked(AdBlockReason.CADENCE),
            AdPolicy.evaluate(
                request.copy(
                    foregroundUsageSeconds = 600,
                    matchesSinceLastInterstitial = 3,
                ),
                AdEntitlements(adsDisabled = false),
                policy,
            ),
        )
        assertEquals(
            AdDecision.Allowed,
            AdPolicy.evaluate(
                request.copy(foregroundUsageSeconds = 600),
                AdEntitlements(adsDisabled = false),
                policy,
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

    @Test
    fun `routing uses Yandex in every market until another provider is available`() {
        val providers = listOf(
            AdProviderId.COMPANY_ADMOB,
            AdProviderId.OWNER_YANDEX,
            AdProviderId.COMPANY_HUAWEI,
        )
        val request = AdRequest(AdPlacement.GAME_BANNER, AdFormat.BANNER)

        assertEquals(
            listOf(AdProviderId.OWNER_YANDEX),
            OwnerFirstAdRoutingPolicy.plan(
                AdRuntimeContext(AdMarket.GLOBAL, providers),
                request,
            ).providers,
        )
        assertEquals(
            listOf(
                AdProviderId.OWNER_YANDEX,
            ),
            OwnerFirstAdRoutingPolicy.plan(
                AdRuntimeContext(AdMarket.RUSSIA, providers),
                request,
            ).providers,
        )
        assertEquals(
            listOf(AdProviderId.OWNER_YANDEX),
            OwnerFirstAdRoutingPolicy.plan(
                AdRuntimeContext(AdMarket.UNKNOWN, providers),
                request,
            ).providers,
        )
    }

    @Test
    fun `router falls back only when owner ad was not presented`() {
        val owner = FakeAdProvider(
            id = AdProviderId.OWNER_YANDEX,
            presentation = AdPresentationResult.NotReady,
        )
        val company = FakeAdProvider(
            id = AdProviderId.COMPANY_ADMOB,
            presentation = AdPresentationResult.Completed,
        )
        val router = AdRouter(
            providers = listOf(owner, company),
            routingPolicy = AdRoutingPolicy { _, _ ->
                AdRoutePlan(listOf(owner.id, company.id))
            },
        )

        val result = runSuspend {
            router.show(
                context = AdRuntimeContext(
                    market = AdMarket.GLOBAL,
                    availableProviders = listOf(company.id, owner.id),
                ),
                request = AdRequest(
                    placement = AdPlacement.SHOP_COINS_REWARD,
                    format = AdFormat.REWARDED,
                ),
            )
        }

        assertEquals(AdPresentationResult.Completed, result.result)
        assertEquals(AdProviderId.COMPANY_ADMOB, result.shownBy)
        assertEquals(RevenueBeneficiary.COMPANY, result.beneficiary)
        assertEquals(
            listOf(AdProviderId.OWNER_YANDEX, AdProviderId.COMPANY_ADMOB),
            result.attempts.map(AdPresentationAttempt::providerId),
        )
    }

    @Test
    fun `router does not fall back after owner ad was dismissed`() {
        val owner = FakeAdProvider(
            id = AdProviderId.OWNER_YANDEX,
            presentation = AdPresentationResult.Dismissed,
        )
        val company = FakeAdProvider(
            id = AdProviderId.COMPANY_ADMOB,
            presentation = AdPresentationResult.Completed,
        )
        val router = AdRouter(
            providers = listOf(owner, company),
            routingPolicy = AdRoutingPolicy { _, _ ->
                AdRoutePlan(listOf(owner.id, company.id))
            },
        )

        val result = runSuspend {
            router.show(
                context = AdRuntimeContext(
                    market = AdMarket.GLOBAL,
                    availableProviders = listOf(owner.id, company.id),
                ),
                request = AdRequest(
                    placement = AdPlacement.GAME_CHECK_DIGIT_HINT,
                    format = AdFormat.REWARDED,
                ),
            )
        }

        assertEquals(AdPresentationResult.Dismissed, result.result)
        assertEquals(AdProviderId.OWNER_YANDEX, result.shownBy)
        assertEquals(1, result.attempts.size)
        assertEquals(0, company.showCalls)
    }

    private class FakeAdProvider(
        override val id: AdProviderId,
        private val presentation: AdPresentationResult,
    ) : AdProvider {
        var showCalls: Int = 0
            private set

        override suspend fun preload(request: AdRequest): AdPreloadResult =
            AdPreloadResult.READY

        override suspend fun show(request: AdRequest): AdPresentationResult {
            showCalls += 1
            return presentation
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return outcome?.getOrThrow() ?: error("Suspend block did not complete")
    }
}
