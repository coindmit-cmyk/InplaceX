package com.mirkori.inplacex.ui.screens.shop

import com.mirkori.inplacex.platform.services.BillingAvailability
import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.BillingProduct
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.BillingState
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopCommercePresentationTest {
    @Test
    fun pendingPaymentCanRetryOnlyTheSameProduct() {
        val state = readyState().copy(
            pendingProduct = BillingProductId.REMOVE_ADS,
            notice = BillingNotice.AWAITING_PAYMENT,
        )

        assertTrue(shouldRetryPendingPurchase(state))
        assertTrue(canStartBillingAction(state, BillingProductId.REMOVE_ADS, inProgress = false))
        assertFalse(canStartBillingAction(state, BillingProductId.PRO_SUBSCRIPTION, inProgress = false))
    }

    @Test
    fun offlineAndBusyStatesCannotStartAnotherCheckout() {
        val offline = readyState().copy(
            availability = BillingAvailability.OFFLINE,
            notice = BillingNotice.OFFLINE,
        )

        assertFalse(canStartBillingAction(offline, BillingProductId.REMOVE_ADS, inProgress = false))
        assertEquals("shop.billing.offline", billingNoticeLocalizationKey(offline.notice))
        assertEquals("shop.billing.cancelled", billingNoticeLocalizationKey(BillingNotice.PAYMENT_CANCELLED))
        assertEquals(
            "shop.billing.checkout_expired",
            billingNoticeLocalizationKey(BillingNotice.CHECKOUT_EXPIRED),
        )
    }

    @Test
    fun confirmedProductCannotStartAnotherCheckout() {
        val state = readyState().copy(
            entitlements = MonetizationEntitlements(
                adFreePurchased = true,
                proSubscriptionActive = false,
                proPlusSubscriptionActive = false,
            ),
        )

        assertFalse(canStartBillingAction(state, BillingProductId.REMOVE_ADS, inProgress = false))
        assertTrue(canStartBillingAction(state, BillingProductId.PRO_SUBSCRIPTION, inProgress = false))
    }

    @Test
    fun proPlusDisablesLowerProCheckoutBecauseItIncludesProAccess() {
        val state = readyState().copy(
            entitlements = MonetizationEntitlements(
                adFreePurchased = false,
                proSubscriptionActive = false,
                proPlusSubscriptionActive = true,
            ),
        )

        assertTrue(state.entitlements.effectiveProAccessActive)
        assertFalse(canStartBillingAction(state, BillingProductId.PRO_SUBSCRIPTION, inProgress = false))
        assertFalse(canStartBillingAction(state, BillingProductId.PRO_PLUS_SUBSCRIPTION, inProgress = false))
    }

    @Test
    fun serverPriceFormattingDoesNotMixWithLocalCoinPrices() {
        assertEquals("99,00 ₽", formatBillingPrice(9_900, "RUB"))
        assertEquals("4.99 USD", formatBillingPrice(499, "USD"))
    }

    @Test
    fun prepaidTimedAccessShowsTypedOfferDurationWithoutRecurringClaim() {
        val rendered = formatPrepaidAccessDuration(30L * 86_400L) { key ->
            when (key) {
                "shop.product.duration_days" -> "{count} days"
                else -> error("Unexpected key")
            }
        }

        assertEquals("30 days", rendered)
    }

    private fun readyState(): BillingState = BillingState(
        availability = BillingAvailability.READY,
        products = BillingProductId.entries.associateWith { id ->
            BillingProduct(
                platformProductId = id.name.lowercase(),
                displayName = id.name,
                description = id.name,
                currency = "RUB",
                amountMinor = 9_900,
            )
        },
    )
}
