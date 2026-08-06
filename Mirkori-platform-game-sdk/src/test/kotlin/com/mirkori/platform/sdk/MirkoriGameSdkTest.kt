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

    private fun sdk(
        transport: QueueTransport,
        entropy: SecureEntropy = CountingEntropy(),
        baseUrl: String = "https://games.dmit.life",
    ) = MirkoriGameSdk(
        MirkoriGameSdkConfig(baseUrl, "inplacex", RedirectUri),
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
