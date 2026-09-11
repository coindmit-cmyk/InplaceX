package com.mirkori.platform.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlayPurchaseSdkTest {
    @Test
    fun distributionIsSnapshottedAndPurchaseTokenIsSentOnlyToVerificationEndpoint() {
        val orderId = "00000000-0000-4000-8000-000000000601"
        val paymentId = "00000000-0000-4000-8000-000000000602"
        val orderPending = orderJson(orderId, "pending")
        val orderPaid = orderJson(orderId, "paid")
        val payment = """{"id":"$paymentId","orderId":"$orderId","status":"succeeded","paymentMethodId":"google_play","channel":"android","currency":"USD","amountMinor":499,"createdAt":"2026-09-11T10:00:00Z","updatedAt":"2026-09-11T10:00:05Z"}"""
        val transport = RecordingTransport(
            PlatformHttpResponse(201, orderPending),
            PlatformHttpResponse(
                200,
                """{"schemaVersion":1,"payment":$payment,"order":$orderPaid,"entitlements":[{"key":"coins","type":"consumable","quantity":100}],"providerFinalized":true}""",
            ),
        )
        val sdk = MirkoriGameSdk(
            config = MirkoriGameSdkConfig(
                platformBaseUrl = "https://games.dmit.life",
                gameId = "inplacex",
                redirectUri = "https://games.dmit.life/connect/inplacex/callback",
                distributionId = "global-google",
            ),
            transport = transport,
        )
        val accessToken = "profile." + "a".repeat(40)
        val purchaseToken = "google-play-purchase-token-601"

        val order = runGooglePlaySuspend {
            sdk.createOrder(
                accessToken,
                "inplacex.coins-100",
                "USD",
                PlatformIdempotencyKey("order-idempotency-601"),
            )
        }
        val result = runGooglePlaySuspend {
            sdk.verifyGooglePlayPurchase(
                accessToken,
                paymentId,
                purchaseToken,
                PlatformIdempotencyKey("purchase-idempotency-601"),
            )
        }

        assertEquals("global-google", order.distributionId)
        assertEquals(PlatformDistributionPaymentChannel.GOOGLE_PLAY, order.distributionPaymentChannel)
        assertEquals(PlatformPaymentStatus.SUCCEEDED, result.payment.status)
        assertEquals(100L, result.entitlements.single().quantity)
        assertTrue(result.providerFinalized)
        assertEquals(
            "{\"productId\":\"inplacex.coins-100\",\"currency\":\"USD\",\"distributionId\":\"global-google\"}",
            transport.requests.first().body,
        )
        val verification = transport.requests.last()
        assertTrue(verification.url.endsWith("/api/v1/commerce/payments/$paymentId/google-play-purchase"))
        assertEquals("purchase-idempotency-601", verification.headers["Idempotency-Key"])
        assertEquals("{\"purchaseToken\":\"$purchaseToken\"}", verification.body)
        assertFalse(verification.toString().contains(purchaseToken))
    }

    private fun orderJson(orderId: String, status: String): String =
        """{"id":"$orderId","gameId":"inplacex","gamePlayerId":"00000000-0000-4000-8000-000000000603","productId":"inplacex.coins-100","currency":"USD","amountMinor":499,"tenderType":"money","distributionId":"global-google","distributionPaymentChannel":"google_play","distributionPackageName":"com.mirkori.inplacex","status":"$status","createdAt":"2026-09-11T10:00:00Z","updatedAt":"2026-09-11T10:00:05Z"}"""

    private class RecordingTransport(vararg responses: PlatformHttpResponse) : PlatformTransport {
        private val responses = responses.toMutableList()
        val requests = mutableListOf<PlatformHttpRequest>()

        override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }
}

private fun <T> runGooglePlaySuspend(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
            latch.countDown()
        }
    })
    check(latch.await(10, TimeUnit.SECONDS)) { "Suspending test timed out" }
    return requireNotNull(outcome).getOrThrow()
}
