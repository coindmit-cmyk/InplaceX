package com.mirkori.platform.sdk

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MirkoriGameSdkTest {
    @Test
    fun bootstrapUsesRedactedRequestAndReturnsGuestProfile() {
        val transport = QueueTransport(
            success(
                """
                {
                  "accountId":"00000000-0000-4000-8000-000000000101",
                  "gamePlayerId":"00000000-0000-4000-8000-000000000102",
                  "gameId":"inplacex",
                  "installationId":"00000000-0000-4000-8000-000000000103",
                  "credentials":${credentialsJson("guest")}
                }
                """.trimIndent(),
            ),
        )
        val sdk = sdk(transport)
        val installation = InstallationIdentity(
            "00000000-0000-4000-8000-000000000103",
            "A".repeat(43),
        )

        val result = runSuspend {
            sdk.bootstrapGuest(
                installation = installation,
                platform = GameClientPlatform.ANDROID,
                appVersion = "1.0.0",
                idempotencyKey = PlatformIdempotencyKey("bootstrap-key"),
            )
        }

        assertEquals(PlatformAuthMode.GUEST, result.authMode)
        assertEquals("00000000-0000-4000-8000-000000000102", result.gamePlayerId)
        val request = transport.requests.single()
        assertEquals("https://games.dmit.life/api/v1/auth/guest/bootstrap", request.url)
        assertEquals("bootstrap-key", request.headers["Idempotency-Key"])
        assertTrue(request.body.contains(installation.installationSecret))
        assertFalse(request.toString().contains(installation.installationSecret))
        assertFalse(result.toString().contains(result.credentials.refreshToken))
        assertFalse(installation.toString().contains(installation.installationSecret))
    }

    @Test
    fun browserLoginCreatesS256ChallengeValidatesCallbackAndExchanges() {
        val session = "S".repeat(64)
        val transport = QueueTransport(
            success(
                """{"session":"$session","connectUrl":"https://games.dmit.life/connect?session=$session","expiresAtEpochMs":1786032600000}""",
            ),
            success(
                """
                {
                  "accountId":"00000000-0000-4000-8000-000000000201",
                  "gamePlayerId":"00000000-0000-4000-8000-000000000202",
                  "gameId":"inplacex",
                  "authMode":"local",
                  "credentials":${credentialsJson("linked")}
                }
                """.trimIndent(),
            ),
        )
        val sdk = sdk(transport, CountingEntropy())

        val pending = runSuspend {
            sdk.beginAccountLogin(
                profileAccessToken = "access." + "x".repeat(40),
                installationId = "00000000-0000-4000-8000-000000000203",
                idempotencyKey = PlatformIdempotencyKey("create-login-key"),
            )
        }

        val createRequest = transport.requests.single()
        assertEquals("Bearer access.${"x".repeat(40)}", createRequest.headers["Authorization"])
        val createJson = Json.parseToJsonElement(createRequest.body).jsonObject
        assertEquals("S256", createJson["codeChallengeMethod"]?.jsonPrimitive?.content)
        assertEquals(pkceChallenge(pending.codeVerifier), createJson["codeChallenge"]?.jsonPrimitive?.content)
        assertEquals(pending.state, createJson["state"]?.jsonPrimitive?.content)
        assertFalse(pending.connectUrl.contains(pending.codeVerifier))
        assertFalse(pending.toString().contains(pending.codeVerifier))

        val linked = runSuspend {
            sdk.completeAccountLogin(
                callbackUrl = "https://games.dmit.life/connect/inplacex/callback?session=$session&state=${pending.state}",
                pending = pending,
                idempotencyKey = PlatformIdempotencyKey("exchange-login-key"),
            )
        }

        assertEquals(PlatformAuthMode.LOCAL, linked.authMode)
        assertEquals("00000000-0000-4000-8000-000000000202", linked.gamePlayerId)
        assertEquals(2, transport.requests.size)
        val exchange = transport.requests.last()
        assertEquals("https://games.dmit.life/api/v1/game-auth/exchange", exchange.url)
        assertFalse(exchange.headers.containsKey("Authorization"))
        assertTrue(exchange.body.contains(pending.codeVerifier))
        assertFalse(exchange.toString().contains(pending.codeVerifier))
    }

    @Test
    fun nativeGoogleLoginKeepsProviderCredentialOutOfUrlsAndReturnsGameSession() {
        val session = "G".repeat(64)
        val idToken = "google-id-token-" + "x".repeat(120)
        val transport = QueueTransport(
            success(
                """{"session":"$session","connectUrl":"https://games.dmit.life/connect?session=$session","expiresAtEpochMs":1786032600000}""",
            ),
            success(
                """
                {
                  "accountId":"00000000-0000-4000-8000-000000000211",
                  "gamePlayerId":"00000000-0000-4000-8000-000000000212",
                  "gameId":"inplacex",
                  "authMode":"google",
                  "credentials":${credentialsJson("google-linked")}
                }
                """.trimIndent(),
            ),
        )
        val sdk = sdk(transport, CountingEntropy())
        val accessToken = "access." + "g".repeat(40)
        val pending = runSuspend {
            sdk.beginAccountLogin(
                profileAccessToken = accessToken,
                installationId = "00000000-0000-4000-8000-000000000213",
                idempotencyKey = PlatformIdempotencyKey("native-google-create"),
            )
        }

        val linked = runSuspend {
            sdk.completeGoogleAccountLogin(
                accessToken,
                idToken,
                pending,
                PlatformIdempotencyKey("native-google-complete"),
            )
        }

        assertEquals(PlatformAuthMode.GOOGLE, linked.authMode)
        assertEquals("00000000-0000-4000-8000-000000000212", linked.gamePlayerId)
        val request = transport.requests.last()
        assertEquals("https://games.dmit.life/api/v1/game-auth/google", request.url)
        assertEquals("Bearer $accessToken", request.headers["Authorization"])
        assertEquals("native-google-complete", request.headers["Idempotency-Key"])
        assertTrue(request.body.contains(idToken))
        assertTrue(request.body.contains(pending.codeVerifier))
        assertFalse(request.url.contains(idToken))
        assertFalse(request.toString().contains(idToken))
        assertFalse(request.body.contains("conflictResolution"))
    }

    @Test
    fun confirmedGoogleProfileConflictUsesExplicitResolutionOnlyInRequestBody() {
        val session = "R".repeat(64)
        val idToken = "google-id-token-" + "y".repeat(120)
        val transport = QueueTransport(
            success(
                """{"session":"$session","connectUrl":"https://games.dmit.life/connect?session=$session","expiresAtEpochMs":1786032600000}""",
            ),
            success(
                """
                {
                  "accountId":"00000000-0000-4000-8000-000000000221",
                  "gamePlayerId":"00000000-0000-4000-8000-000000000222",
                  "gameId":"inplacex",
                  "authMode":"google",
                  "credentials":${credentialsJson("google-existing")}
                }
                """.trimIndent(),
            ),
        )
        val sdk = sdk(transport, CountingEntropy())
        val accessToken = "access." + "h".repeat(40)
        val pending = runSuspend {
            sdk.beginAccountLogin(
                profileAccessToken = accessToken,
                installationId = "00000000-0000-4000-8000-000000000223",
                idempotencyKey = PlatformIdempotencyKey("confirmed-google-create"),
            )
        }

        runSuspend {
            sdk.completeGoogleAccountLogin(
                profileAccessToken = accessToken,
                idToken = idToken,
                pending = pending,
                conflictResolution = PlatformProfileConflictResolution.USE_EXISTING_PROFILE,
                idempotencyKey = PlatformIdempotencyKey("confirmed-google-complete"),
            )
        }

        val request = transport.requests.last()
        assertTrue(request.body.contains("\"conflictResolution\":\"use_existing_profile\""))
        assertFalse(request.url.contains("use_existing_profile"))
        assertFalse(request.toString().contains(idToken))
    }

    @Test
    fun callbackMismatchAndProfileConflictNeverExchangeCredentials() {
        val session = "Q".repeat(64)
        val transport = QueueTransport(
            success(
                """{"session":"$session","connectUrl":"https://games.dmit.life/connect?session=$session","expiresAtEpochMs":1786032600000}""",
            ),
        )
        val sdk = sdk(transport)
        val pending = runSuspend {
            sdk.beginAccountLogin(
                profileAccessToken = "a".repeat(43),
                installationId = "00000000-0000-4000-8000-000000000303",
                idempotencyKey = PlatformIdempotencyKey("callback-test-create"),
            )
        }

        assertThrows(PlatformCallbackRejectedException::class.java) {
            runSuspend {
                sdk.completeAccountLogin(
                    "https://games.dmit.life/connect/inplacex/callback?session=$session&state=${"x".repeat(43)}",
                    pending,
                )
            }
        }
        assertThrows(PlatformProfileConflictException::class.java) {
            runSuspend {
                sdk.completeAccountLogin(
                    "https://games.dmit.life/connect/inplacex/callback?session=$session&state=${pending.state}&error=profile_conflict",
                    pending,
                )
            }
        }
        assertThrows(PlatformCallbackRejectedException::class.java) {
            runSuspend {
                sdk.completeAccountLogin(
                    "https://games.dmit.life/connect/inplacex/callback?session=$session&session=$session&state=${pending.state}",
                    pending,
                )
            }
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun platformOriginIsHttpsUnlessExplicitLoopbackDevelopmentIsEnabled() {
        assertThrows(IllegalArgumentException::class.java) {
            sdk(QueueTransport(), baseUrl = "http://games.dmit.life")
        }
        assertThrows(IllegalArgumentException::class.java) {
            sdk(QueueTransport(), baseUrl = "http://127.0.0.1:39123")
        }

        val local = MirkoriGameSdk(
            MirkoriGameSdkConfig(
                platformBaseUrl = "http://127.0.0.1:39123",
                gameId = "inplacex",
                redirectUri = RedirectUri,
                allowCleartextLoopback = true,
            ),
            QueueTransport(),
        )
        assertEquals("http://127.0.0.1:39123", local.config.platformBaseUrl)
    }

    @Test
    fun gameIdValidationExactlyMatchesTheBackendContract() {
        val transport = QueueTransport(success("""{"schemaVersion":1,"products":[]}"""))
        val oneCharacterGame = sdk(transport, gameId = "a")

        assertTrue(runSuspend { oneCharacterGame.products("RUB") }.isEmpty())
        assertEquals(
            "https://games.dmit.life/api/v1/commerce/games/a/products?currency=RUB",
            transport.requests.single().url,
        )
        assertThrows(IllegalArgumentException::class.java) {
            sdk(QueueTransport(), gameId = "a_b")
        }
    }

    @Test
    fun apiErrorsExposeOnlyStatusAndStableCode() {
        val transport = QueueTransport(PlatformHttpResponse(401, """{"error":"refresh_rejected"}"""))
        val sdk = sdk(transport)

        val error = assertThrows(PlatformApiException::class.java) {
            runSuspend {
                sdk.refresh("r".repeat(43), PlatformIdempotencyKey("refresh-key"))
            }
        }

        assertEquals(401, error.status)
        assertEquals("refresh_rejected", error.errorCode)
        assertFalse(transport.requests.single().toString().contains("r".repeat(43)))
        assertFalse(transport.servedResponses.single().toString().contains("refresh_rejected"))
    }

    @Test
    fun friendProfileApiSendsRequestsListsIncomingAcceptsAndListsFriends() {
        val playerId = "00000000-0000-4000-8000-000000000502"
        val requestId = "00000000-0000-4000-8000-000000000503"
        val profile = """{"gamePlayerId":"$playerId","handle":"friend","displayName":"Friend","avatarUrl":"https://games.dmit.life/assets/player-avatars/robot"}"""
        val pending = """{"requestId":"$requestId","status":"pending","player":$profile,"createdAtEpochMs":1786464000000}"""
        val accepted = pending.replace("\"pending\"", "\"accepted\"")
        val transport = QueueTransport(
            success(pending),
            success("""{"schemaVersion":1,"requests":[$pending]}"""),
            success(accepted),
            success("""{"schemaVersion":1,"players":[$profile]}"""),
        )
        val sdk = sdk(transport)
        val accessToken = "access." + "f".repeat(40)

        assertEquals(
            PlatformFriendRequestStatus.PENDING,
            runSuspend {
                sdk.createFriendRequest(
                    accessToken,
                    playerId,
                    PlatformIdempotencyKey("friend-request-create"),
                )
            }.status,
        )
        assertEquals(requestId, runSuspend { sdk.incomingFriendRequests(accessToken) }.single().requestId)
        assertEquals(
            PlatformFriendRequestStatus.ACCEPTED,
            runSuspend {
                sdk.acceptFriendRequest(
                    accessToken,
                    requestId,
                    PlatformIdempotencyKey("friend-request-accept"),
                )
            }.status,
        )
        assertEquals(playerId, runSuspend { sdk.friends(accessToken) }.single().gamePlayerId)
        assertEquals(PlatformHttpMethod.POST, transport.requests[0].method)
        assertTrue(transport.requests[0].body.contains(playerId))
        assertTrue(transport.requests[2].url.endsWith("/$requestId/accept"))
    }

    @Test
    fun commerceFlowUsesBearerTokensStableKeysAndTypedResponses() {
        val orderId = "00000000-0000-4000-8000-000000000401"
        val checkoutId = "00000000-0000-4000-8000-000000000402"
        val receiptId = "00000000-0000-4000-8000-000000000403"
        val orderJson = """
            {"id":"$orderId","gameId":"inplacex","gamePlayerId":"00000000-0000-4000-8000-000000000404",
             "productId":"inplacex.coins-100","currency":"RUB","amountMinor":9900,"status":"pending",
             "createdAt":"2026-08-07T10:00:00Z","updatedAt":"2026-08-07T10:00:00Z"}
        """.trimIndent().replace("\n", "")
        val transport = QueueTransport(
            success(
                """{"schemaVersion":1,"products":[{"id":"inplacex.coins-100","gameId":"inplacex","slug":"coins-100","displayName":"100 монет","description":"Игровая валюта","productKind":"currency","version":1,"price":{"currency":"RUB","amountMinor":9900},"grants":[{"entitlementKey":"currency.coins","type":"consumable","quantity":100}]}]}""",
            ),
            success(orderJson),
            success(
                """{"schemaVersion":1,"checkout":{"id":"$checkoutId","orderId":"$orderId","provider":"fake_checkout","status":"ready","expiresAt":"2026-08-07T10:15:00Z","createdAt":"2026-08-07T10:00:00Z","updatedAt":"2026-08-07T10:00:01Z"},"paymentUrl":"https://payments.invalid/checkout/$checkoutId"}""",
            ),
            success(orderJson),
            success("""{"schemaVersion":1,"orders":[$orderJson]}"""),
            success(
                """{"schemaVersion":1,"entitlements":[{"key":"currency.coins","type":"consumable","quantity":100}]}""",
            ),
            success(
                """{"schemaVersion":2,"consumption":{"id":"$receiptId","entitlementKey":"currency.coins","quantity":20,"remainingQuantity":80,"createdAt":"2026-08-07T10:02:00Z"},"entitlements":[{"key":"currency.coins","type":"consumable","quantity":80}]}""",
            ),
        )
        val sdk = sdk(transport)
        val accessToken = "access." + "x".repeat(40)

        val products = runSuspend { sdk.products("RUB") }
        val order = runSuspend {
            sdk.createOrder(
                accessToken,
                "inplacex.coins-100",
                "RUB",
                PlatformIdempotencyKey("create-order-key"),
            )
        }
        val checkout = runSuspend {
            sdk.createCheckout(accessToken, order.id, PlatformIdempotencyKey("create-checkout-key"))
        }
        val restoredOrder = runSuspend { sdk.order(accessToken, order.id) }
        val orders = runSuspend { sdk.orders(accessToken) }
        val entitlements = runSuspend { sdk.entitlements(accessToken) }
        val consumption = runSuspend {
            sdk.consumeEntitlement(
                accessToken,
                "currency.coins",
                20,
                PlatformIdempotencyKey("consume-coins-key"),
            )
        }

        assertEquals(100L, products.single().grants.single().quantity)
        assertEquals(order, restoredOrder)
        assertEquals(orderId, orders.single().id)
        assertEquals(checkoutId, checkout.id)
        assertFalse(checkout.toString().contains(checkout.paymentUrl))
        assertEquals(100L, entitlements.single().quantity)
        assertEquals(receiptId, consumption.id)
        assertEquals(80L, consumption.remainingQuantity)
        assertEquals(PlatformHttpMethod.GET, transport.requests.first().method)
        assertEquals(
            "https://games.dmit.life/api/v1/commerce/games/inplacex/products?currency=RUB",
            transport.requests.first().url,
        )
        assertEquals("create-order-key", transport.requests[1].headers["Idempotency-Key"])
        assertEquals("create-checkout-key", transport.requests[2].headers["Idempotency-Key"])
        assertEquals("Bearer $accessToken", transport.requests[2].headers["Authorization"])
        assertEquals(PlatformHttpMethod.GET, transport.requests[3].method)
        assertTrue(transport.requests.last().url.contains("/api/v2/commerce/entitlements/"))
        assertEquals("consume-coins-key", transport.requests.last().headers["Idempotency-Key"])
    }

    private fun sdk(
        transport: QueueTransport,
        entropy: SecureEntropy = CountingEntropy(),
        baseUrl: String = "https://games.dmit.life",
        gameId: String = "inplacex",
    ) = MirkoriGameSdk(
        MirkoriGameSdkConfig(baseUrl, gameId, RedirectUri),
        transport,
        entropy,
    )

    private companion object {
        const val RedirectUri = "https://games.dmit.life/connect/inplacex/callback"
    }
}

private class QueueTransport(vararg initial: PlatformHttpResponse) : PlatformTransport {
    val requests = mutableListOf<PlatformHttpRequest>()
    val responses = initial.toMutableList()
    val servedResponses = mutableListOf<PlatformHttpResponse>()

    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        requests += request
        return (responses.removeFirstOrNull() ?: error("No queued response")).also(servedResponses::add)
    }
}

private class CountingEntropy : SecureEntropy {
    private var next = 1
    override fun bytes(count: Int): ByteArray = ByteArray(count) { next.toByte() }.also { next++ }
}

private fun credentialsJson(prefix: String): String =
    """{"accessToken":"$prefix.${"a".repeat(43)}","refreshToken":"$prefix-${"r".repeat(43)}","accessExpiresAtEpochMs":1786032600000,"refreshExpiresAtEpochMs":1788624600000}"""

private fun success(body: String) = PlatformHttpResponse(200, body)

private fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
)

private fun <T> runSuspend(block: suspend () -> T): T {
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
