package com.mirkori.inplacex.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirkoriGameDeliveryRepositoryInstrumentedTest {
    @Test
    fun coinGrantAndRefundApplyExactlyOnceAcrossRestartAndPersistAcknowledgementIdentity() {
        withIsolatedDatabase("mirkori_delivery_once", { NOW_MS }) { context, config ->
            val repository = GameProgressRepository(context, config)
            val grant = delivery(
                deliveryId = "00000000-0000-4000-8000-000000000101",
                eventId = "00000000-0000-4000-8000-000000000102",
                sequenceNumber = 1,
                quantityDelta = 100,
                payloadSha256 = "a".repeat(64),
            )

            val first = repository.prepareMirkoriGameDelivery(grant, "ack-grant-original")
            assertEquals(220, repository.loadState().coins)
            assertEquals("ack-grant-original", first.acknowledgementIdempotencyKey)
            assertNull(first.acknowledgedAtMs)

            val afterRestart = GameProgressRepository(context, config)
            val replay = afterRestart.prepareMirkoriGameDelivery(grant, "ack-grant-replacement")
            assertEquals(220, afterRestart.loadState().coins)
            assertEquals("ack-grant-original", replay.acknowledgementIdempotencyKey)

            afterRestart.markMirkoriGameDeliveryAcknowledged(
                grant.deliveryId,
                replay.acknowledgementIdempotencyKey,
                ACKNOWLEDGED_AT_MS,
            )
            afterRestart.markMirkoriGameDeliveryAcknowledged(
                grant.deliveryId,
                replay.acknowledgementIdempotencyKey,
                ACKNOWLEDGED_AT_MS,
            )
            assertEquals(
                ACKNOWLEDGED_AT_MS,
                afterRestart.prepareMirkoriGameDelivery(grant, "unused-key").acknowledgedAtMs,
            )

            val refund = delivery(
                deliveryId = "00000000-0000-4000-8000-000000000103",
                eventId = "00000000-0000-4000-8000-000000000104",
                sequenceNumber = 2,
                quantityDelta = -40,
                correctionQuantity = 60,
                payloadSha256 = "b".repeat(64),
            )
            afterRestart.prepareMirkoriGameDelivery(refund, "ack-refund-original")
            assertEquals(180, afterRestart.loadState().coins)
            afterRestart.prepareMirkoriGameDelivery(refund, "ack-refund-replacement")
            assertEquals(180, afterRestart.loadState().coins)

            assertThrows(MirkoriGameDeliveryConflictException::class.java) {
                afterRestart.prepareMirkoriGameDelivery(
                    grant.copy(payloadSha256 = "c".repeat(64)),
                    "conflicting-key",
                )
            }
            assertThrows(MirkoriGameDeliveryCannotApplyException::class.java) {
                afterRestart.prepareMirkoriGameDelivery(
                    delivery(
                        deliveryId = "00000000-0000-4000-8000-000000000105",
                        eventId = "00000000-0000-4000-8000-000000000106",
                        sequenceNumber = 3,
                        quantityDelta = -1_000,
                        payloadSha256 = "d".repeat(64),
                    ),
                    "unapplicable-refund",
                )
            }
            assertEquals(180, afterRestart.loadState().coins)
        }
    }

    private fun delivery(
        deliveryId: String,
        eventId: String,
        sequenceNumber: Long,
        quantityDelta: Long,
        correctionQuantity: Long = 0,
        payloadSha256: String,
    ) = LocalMirkoriGameDelivery(
        deliveryId = deliveryId,
        accountId = "00000000-0000-4000-8000-000000000201",
        gamePlayerId = "00000000-0000-4000-8000-000000000202",
        entitlementEventId = eventId,
        entitlementId = "00000000-0000-4000-8000-000000000203",
        sequenceNumber = sequenceNumber,
        action = if (quantityDelta > 0) "grant" else "revoke",
        gameId = "inplacex",
        productId = "inplacex.coins-100",
        orderId = "00000000-0000-4000-8000-000000000204",
        entitlementKey = "coins",
        entitlementKind = "consumable_balance",
        quantityDelta = quantityDelta,
        validFromMs = NOW_MS - 1_000,
        expiresAtMs = null,
        correctionQuantity = correctionQuantity,
        payloadSha256 = payloadSha256,
        createdAtMs = NOW_MS,
        application = MirkoriDeliveryApplication.COINS,
    )

    private companion object {
        const val NOW_MS = 1_786_100_000_000L
        const val ACKNOWLEDGED_AT_MS = NOW_MS + 5_000
    }
}
