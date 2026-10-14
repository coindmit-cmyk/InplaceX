package com.mirkori.inplacex.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampaignEnergyPurchaseInstrumentedTest {
    @Test
    fun fullEnergyPurchaseDoesNotCharge() = withIsolatedDatabase("energy_purchase_full", { NOW }) { context, config ->
        val repository = GameProgressRepository(context, config)
        val before = repository.loadState(NOW)
        assertEquals(MAX_CAMPAIGN_ENERGY, before.campaignEnergy)
        assertFalse(repository.buyCampaignEnergy(25))
        val after = repository.loadState(NOW)
        assertEquals(before.coins, after.coins)
        assertEquals(before.campaignEnergy, after.campaignEnergy)
    }

    @Test
    fun oneSlotPurchaseChargesOnceAndRepeatIsRejected() = withIsolatedDatabase("energy_purchase_one", { NOW }) { context, config ->
        val repository = GameProgressRepository(context, config)
        repository.consumeCampaignEnergyForLoss(NOW)
        val before = repository.loadState(NOW)
        assertTrue(repository.buyCampaignEnergy(25))
        val purchased = repository.loadState(NOW)
        assertEquals(before.coins - 25, purchased.coins)
        assertEquals(MAX_CAMPAIGN_ENERGY, purchased.campaignEnergy)
        assertFalse(repository.buyCampaignEnergy(25))
        val repeated = repository.loadState(NOW)
        assertEquals(purchased.coins, repeated.coins)
        assertEquals(MAX_CAMPAIGN_ENERGY, repeated.campaignEnergy)
    }

    @Test
    fun insufficientCoinsLeavesStateUnchanged() = withIsolatedDatabase("energy_purchase_poor", { NOW }) { context, config ->
        val repository = GameProgressRepository(context, config)
        repository.consumeCampaignEnergyForLoss(NOW)
        val before = repository.loadState(NOW)
        assertFalse(repository.buyCampaignEnergy(before.coins + 1))
        val after = repository.loadState(NOW)
        assertEquals(before.coins, after.coins)
        assertEquals(before.campaignEnergy, after.campaignEnergy)
    }

    @Test
    fun regenerationToFullRejectsBeforeCharging() {
        var nowMs = NOW
        withIsolatedDatabase("energy_purchase_regen", { nowMs }) { context, config ->
            val repository = GameProgressRepository(context, config)
            repository.consumeCampaignEnergyForLoss(NOW)
            val before = repository.loadState(NOW)
            val regeneratedNow = NOW + MAX_CAMPAIGN_ENERGY * ENERGY_REFILL_MINUTES * 60_000L
            nowMs = regeneratedNow
            assertFalse(repository.buyCampaignEnergy(25))
            val after = repository.loadState(regeneratedNow)
            assertEquals(before.coins, after.coins)
            assertEquals(MAX_CAMPAIGN_ENERGY, after.campaignEnergy)
        }
    }

    @Test
    fun invalidInputsLeaveStateUnchanged() = withIsolatedDatabase("energy_purchase_invalid", { NOW }) { context, config ->
        val repository = GameProgressRepository(context, config)
        repository.consumeCampaignEnergyForLoss(NOW)
        val before = repository.loadState(NOW)
        for ((cost, amount) in listOf(25 to 0, 25 to -1, -1 to 1)) {
            try {
                repository.buyCampaignEnergy(cost, amount)
                throw AssertionError("Expected invalid purchase arguments to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected validation failure.
            }
        }
        val after = repository.loadState(NOW)
        assertEquals(before.coins, after.coins)
        assertEquals(before.campaignEnergy, after.campaignEnergy)
    }

    @Test
    fun hugeAmountIsCappedWithoutOverflow() = withIsolatedDatabase("energy_purchase_huge", { NOW }) { context, config ->
        val repository = GameProgressRepository(context, config)
        repository.consumeCampaignEnergyForLoss(NOW)
        val before = repository.loadState(NOW)
        assertTrue(repository.buyCampaignEnergy(25, Int.MAX_VALUE))
        val after = repository.loadState(NOW)
        assertEquals(before.coins - 25, after.coins)
        assertEquals(MAX_CAMPAIGN_ENERGY, after.campaignEnergy)
    }

    @Test
    fun concurrentRepositoriesCannotBothBuyLastSlot() = withIsolatedDatabase("energy_purchase_race", { NOW }) { context, config ->
        val first = GameProgressRepository(context, config)
        first.consumeCampaignEnergyForLoss(NOW)
        val before = first.loadState(NOW)
        assertEquals(MAX_CAMPAIGN_ENERGY - 1, before.campaignEnergy)
        val second = GameProgressRepository(context, config)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { repository ->
                pool.submit<Boolean> {
                    start.await()
                    repository.buyCampaignEnergy(25)
                }
            }
            start.countDown()
            val outcomes = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it })
            assertEquals(1, outcomes.count { !it })
            val after = first.loadState(NOW)
            assertEquals(MAX_CAMPAIGN_ENERGY, after.campaignEnergy)
            assertEquals(before.coins - 25, after.coins)
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
