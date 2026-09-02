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
    fun browserLoginAcceptsYandexLinkedGameSession() {
        val session = "Y".repeat(64)
        val transport = QueueTransport(
            success(
                """{"session":"$session","connectUrl":"https://games.dmit.life/connect?session=$session","expiresAtEpochMs":1786032600000}""",
            ),
            success(
                """
                {
                  "accountId":"00000000-0000-4000-8000-000000000205",
                  "gamePlayerId":"00000000-0000-4000-8000-000000000206",
                  "gameId":"inplacex",
                  "authMode":"yandex",
                  "credentials":${credentialsJson("yandex-linked")}
                }
                """.trimIndent(),
            ),
        )
        val sdk = sdk(transport, CountingEntropy())
        val pending = runSuspend {
            sdk.beginAccountLogin(
                profileAccessToken = "access." + "y".repeat(40),
                installationId = "00000000-0000-4000-8000-000000000207",
                idempotencyKey = PlatformIdempotencyKey("yandex-login-create"),
            )
        }

        val linked = runSuspend {
            sdk.completeAccountLogin(
                "https://games.dmit.life/connect/inplacex/callback?session=$session&state=${pending.state}",
                pending,
                PlatformIdempotencyKey("yandex-login-exchange"),
            )
        }

        assertEquals(PlatformAuthMode.YANDEX, linked.authMode)
        assertEquals("00000000-0000-4000-8000-000000000206", linked.gamePlayerId)
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
                profileAccessToken = accessToken,
                idToken = idToken,
                pending = pending,
                idempotencyKey = PlatformIdempotencyKey("native-google-complete"),
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
    fun updateCheckReturnsUpToDateOptionalAndRequiredServerDecisions() {
        val transport = QueueTransport(
            success(updateDecisionJson(currentVersionCode = 50, updateAvailable = false, required = false)),
            success(updateDecisionJson(currentVersionCode = 42, updateAvailable = true, required = false)),
            success(
                updateDecisionJson(
                    currentVersionCode = 10,
                    updateAvailable = true,
                    required = true,
                    channel = "beta",
                ),
            ),
            success(
                updateDecisionJson(
                    currentVersionCode = 7,
                    updateAvailable = true,
                    required = false,
                    platform = "windows",
                    fileName = "InplaceX-1.0.0.zip",
                    minimumSupportedVersionCode = 1,
                    minimumAndroidSdk = null,
                    packageName = null,
                    fingerprints = null,
                ),
            ),
        )
        val sdk = sdk(transport)

        val current = runSuspend { sdk.checkForUpdate(50) }
        val optional = runSuspend { sdk.checkForUpdate(42) }
        val required = runSuspend { sdk.checkForUpdate(10, channel = PlatformReleaseChannel.BETA) }
        val windows = runSuspend { sdk.checkForUpdate(7, PlatformReleasePlatform.WINDOWS) }

        assertEquals(PlatformUpdateStatus.UP_TO_DATE, current.status)
        assertEquals(null, current.release)
        assertEquals(PlatformUpdateStatus.OPTIONAL, optional.status)
        assertEquals(50L, optional.release?.versionCode)
        assertEquals(PlatformUpdateStatus.REQUIRED, required.status)
        assertEquals(40L, required.release?.minimumSupportedVersionCode)
        assertEquals(PlatformReleasePlatform.WINDOWS, windows.release?.platform)
        assertEquals(null, windows.release?.packageName)
        assertEquals(
            "https://games.dmit.life/api/v1/catalog/games/inplacex/updates" +
                "?platform=android&channel=stable&versionCode=42",
            transport.requests[1].url,
        )
        assertTrue(transport.requests.all { it.method == PlatformHttpMethod.GET && it.headers.keys == setOf("Accept") })
    }

    @Test
    fun updateCheckRejectsMismatchedOrUnsafeReleaseMetadata() {
        val valid = updateDecisionJson(currentVersionCode = 10, updateAvailable = true, required = true)
        val invalidResponses = listOf(
            valid.replace("\"gameId\":\"inplacex\"", "\"gameId\":\"other-game\""),
            valid.replace("\"channel\":\"stable\"", "\"channel\":\"beta\""),
            valid.replace("\"currentVersionCode\":10", "\"currentVersionCode\":11"),
            valid.replace("\"updateAvailable\":true", "\"updateAvailable\":false"),
            valid.replace("\"versionCode\":50", "\"versionCode\":10"),
            valid.replace("https://games.dmit.life/downloads/", "http://games.dmit.life/downloads/"),
            valid.replace("\"${"a".repeat(64)}\"", "\"${"A".repeat(64)}\""),
            valid.replace("com.mirkori.inplacex", "invalid-package"),
            valid.replace(androidFingerprint(), androidFingerprint().lowercase()),
            valid.replace("\"minimumAndroidSdk\":26", "\"minimumAndroidSdk\":4294967322"),
        )

        invalidResponses.forEach { response ->
            assertThrows(IllegalArgumentException::class.java) {
                runSuspend { sdk(QueueTransport(success(response))).checkForUpdate(10) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { sdk(QueueTransport()).checkForUpdate(0) }
        }
    }

    @Test
    fun distributionAwareUpdateUsesImmutableBuildCapabilityAndRejectsServerMismatch() {
        val valid = distributionUpdateDecisionJson()
        val transport = QueueTransport(
            success(valid),
            success(valid.replace("rf-mirkori", "global-google")),
            success(valid.replace("\"paymentChannel\":\"mirkori\"", "\"paymentChannel\":\"google_play\"")),
        )
        val sdk = sdk(transport, distributionId = "rf-mirkori")

        val decision = runSuspend { sdk.checkForDistributionUpdate(39) }

        assertEquals(PlatformUpdateStatus.REQUIRED, decision.status)
        assertEquals(PlatformDistributionPaymentChannel.MIRKORI, decision.distribution.paymentChannel)
        assertEquals("RF release", decision.release?.changelogs?.get("en"))
        assertEquals(
            "https://games.dmit.life/api/v2/catalog/games/inplacex/updates" +
                "?distributionId=rf-mirkori&channel=stable&versionCode=39",
            transport.requests.first().url,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { sdk.checkForDistributionUpdate(39) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { sdk.checkForDistributionUpdate(39) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { sdk(QueueTransport()).checkForDistributionUpdate(39) }
        }

        val globalValid = distributionUpdateDecisionJson(
            distributionId = "global-google",
            marketScope = "global",
            packageName = "com.mirkori.inplacex",
            signingIdentityRef = "inplacex-global-signing",
            paymentChannel = "google_play",
            deliveryChannel = "google_play",
            downloadUrl = null,
        )
        val globalTransport = QueueTransport(
            success(globalValid),
            success(globalValid.replace(
                "\"packageName\":\"com.mirkori.inplacex\"",
                "\"downloadUrl\":\"https://games.dmit.life/downloads/forbidden.apk\",\"packageName\":\"com.mirkori.inplacex\"",
            )),
        )
        val globalSdk = sdk(globalTransport, distributionId = "global-google")
        val globalDecision = runSuspend { globalSdk.checkForDistributionUpdate(39) }
        assertEquals(PlatformDistributionDeliveryChannel.GOOGLE_PLAY, globalDecision.distribution.deliveryChannel)
        assertEquals(null, globalDecision.release?.downloadUrl)
        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { globalSdk.checkForDistributionUpdate(39) }
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
        assertEquals(PlatformRecoveryAction.REAUTHENTICATE, error.recoveryAction)
        assertFalse(transport.requests.single().toString().contains("r".repeat(43)))
        assertFalse(transport.servedResponses.single().toString().contains("refresh_rejected"))
    }

    @Test
    fun apiAndTransportFailuresExposeTypedRecoveryWithoutLeakingBodies() {
        val apiCases = listOf(
            PlatformHttpResponse(503, """{"error":"provider_unavailable"}""") to
                ("provider_unavailable" to PlatformRecoveryAction.RETRY_SAME_REQUEST),
            PlatformHttpResponse(409, """{"error":"idempotency_conflict"}""") to
                ("idempotency_conflict" to PlatformRecoveryAction.RESOLVE_CONFLICT),
            PlatformHttpResponse(400, """{"error":"Bearer secret-value"}""") to
                ("invalid_response" to PlatformRecoveryAction.DO_NOT_RETRY),
        )
        apiCases.forEachIndexed { index, (response, expected) ->
            val error = assertThrows(PlatformApiException::class.java) {
                runSuspend {
                    sdk(QueueTransport(response)).refresh(
                        "token-${"r".repeat(43)}",
                        PlatformIdempotencyKey("failure-$index"),
                    )
                }
            }
            assertEquals(expected.first, error.errorCode)
            assertEquals(expected.second, error.recoveryAction)
            assertFalse(error.toString().contains("secret-value"))
        }

        assertTrue(PlatformTransportFailure.NETWORK_UNAVAILABLE.retryable)
        assertTrue(PlatformTransportFailure.TIMEOUT.retryable)
        assertFalse(PlatformTransportFailure.TLS_REJECTED.retryable)
        assertFalse(PlatformTransportFailure.CANCELLED.retryable)
        assertFalse(PlatformTransportFailure.INVALID_RESPONSE.retryable)
        val accessToken = "access." + "s".repeat(40)
        val transport = PlatformTransport {
            throw PlatformTransportException(PlatformTransportFailure.NETWORK_UNAVAILABLE)
        }
        val transportError = assertThrows(PlatformTransportException::class.java) {
            runSuspend {
                MirkoriGameSdk(
                    MirkoriGameSdkConfig("https://games.dmit.life", "inplacex", RedirectUri),
                    transport,
                ).beginAccountLogin(
                    accessToken,
                    "00000000-0000-4000-8000-000000000208",
                    PlatformIdempotencyKey("transport-failure"),
                )
            }
        }
        assertEquals(PlatformTransportFailure.NETWORK_UNAVAILABLE, transportError.failure)
        assertFalse(transportError.toString().contains(accessToken))
    }

    @Test
    fun publicProfileApiNormalizesHandleEncodesSearchAndUsesPutForUpdates() {
        val profile = """{"gamePlayerId":"00000000-0000-4000-8000-000000000501","handle":"workdmit","displayName":"Dmit","avatarUrl":null}"""
        val transport = QueueTransport(
            success(profile),
            success("""{"schemaVersion":1,"players":[$profile]}"""),
            success(profile),
        )
        val sdk = sdk(transport)
        val accessToken = "access." + "p".repeat(40)

        val current = runSuspend { sdk.publicProfile(accessToken) }
        val search = runSuspend { sdk.searchPlayers(accessToken, " @work dmit ") }
        val updated = runSuspend {
            sdk.updatePublicProfile(
                accessToken,
                "@WorkDmit",
                "Dmit",
                PlatformIdempotencyKey("public-profile-update"),
            )
        }

        assertEquals("workdmit", current.handle)
        assertEquals(current, search.single())
        assertEquals(current, updated)
        assertEquals(
            "https://games.dmit.life/api/v1/game-profiles/search?query=work+dmit",
            transport.requests[1].url,
        )
        assertEquals(PlatformHttpMethod.PUT, transport.requests[2].method)
        assertEquals("public-profile-update", transport.requests[2].headers["Idempotency-Key"])
        assertEquals("Bearer $accessToken", transport.requests[2].headers["Authorization"])
        assertTrue(transport.requests[2].body.contains("workdmit"))
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
        val deliveryId = "00000000-0000-4000-8000-000000000405"
        val entitlementEventId = "00000000-0000-4000-8000-000000000406"
        val entitlementId = "00000000-0000-4000-8000-000000000407"
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
                """{"schemaVersion":1,"deliveries":[{"id":"$deliveryId","entitlementEventId":"$entitlementEventId","entitlementId":"$entitlementId","sequenceNumber":1,"action":"grant","gameId":"inplacex","productId":"inplacex.coins-100","orderId":"$orderId","entitlementKey":"currency.coins","entitlementKind":"consumable_balance","quantityDelta":100,"validFrom":"2026-08-07T10:01:00Z","correctionQuantity":0,"payloadSha256":"${"a".repeat(64)}","createdAt":"2026-08-07T10:01:00Z"}]}""",
            ),
            success(
                """{"schemaVersion":1,"acknowledgement":{"deliveryId":"$deliveryId","acknowledgedAt":"2026-08-07T10:01:30Z"}}""",
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
        val deliveries = runSuspend { sdk.pendingGameDeliveries(accessToken, limit = 10) }
        val acknowledgement = runSuspend {
            sdk.acknowledgeGameDelivery(
                accessToken,
                deliveries.single().id,
                PlatformIdempotencyKey("ack-delivery-key"),
            )
        }
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
        assertEquals(deliveryId, deliveries.single().id)
        assertEquals(PlatformGameDeliveryAction.GRANT, deliveries.single().action)
        assertEquals(PlatformEntitlementKind.CONSUMABLE_BALANCE, deliveries.single().entitlementKind)
        assertEquals(deliveryId, acknowledgement.deliveryId)
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
        assertTrue(transport.requests[6].url.endsWith("/api/v1/commerce/game-deliveries?limit=10"))
        assertEquals("ack-delivery-key", transport.requests[7].headers["Idempotency-Key"])
        assertEquals("{\"applied\":true}", transport.requests[7].body)
        assertTrue(transport.requests.last().url.contains("/api/v2/commerce/entitlements/"))
        assertEquals("consume-coins-key", transport.requests.last().headers["Idempotency-Key"])
    }

    @Test
    fun providerNeutralPaymentFlowKeepsProviderOutOfGameSdkContract() {
        val orderId = "00000000-0000-4000-8000-000000000411"
        val paymentId = "00000000-0000-4000-8000-000000000412"
        val paymentJson = """{"schemaVersion":1,"payment":{"id":"$paymentId","orderId":"$orderId","status":"requires_action","paymentMethodId":"sbp","channel":"android","currency":"RUB","amountMinor":19900,"expiresAt":"2026-08-12T12:15:00Z","createdAt":"2026-08-12T12:00:00Z","updatedAt":"2026-08-12T12:00:01Z","nextAction":{"type":"redirect","url":"https://payments.invalid/opaque"}}}"""
        val statusJson = paymentJson.replace(
            "\"status\":\"requires_action\"",
            "\"status\":\"processing\"",
        ).replace(
            ",\"nextAction\":{\"type\":\"redirect\",\"url\":\"https://payments.invalid/opaque\"}",
            "",
        )
        val transport = QueueTransport(
            success(
                """{"schemaVersion":1,"orderId":"$orderId","currency":"RUB","amountMinor":19900,"countryCode":"RU","methods":[{"id":"bank_card","category":"card","displayName":"Банковская карта","nextActionTypes":["redirect"]},{"id":"sbp","category":"bank_transfer","displayName":"СБП","nextActionTypes":["redirect"]}]}""",
            ),
            success(paymentJson),
            success(statusJson),
        )
        val sdk = sdk(transport)
        val accessToken = "access." + "x".repeat(40)

        val methods = runSuspend { sdk.paymentMethods(accessToken, orderId, PlatformPaymentChannel.ANDROID) }
        val payment = runSuspend {
            sdk.createPayment(
                accessToken,
                orderId,
                "sbp",
                PlatformPaymentChannel.ANDROID,
                PlatformIdempotencyKey("payment-create-key"),
            )
        }
        val restored = runSuspend { sdk.payment(accessToken, paymentId) }

        assertEquals(listOf("bank_card", "sbp"), methods.methods.map(PlatformPaymentMethod::id))
        assertEquals(PlatformPaymentNextActionType.REDIRECT, payment.nextAction?.type)
        assertFalse(payment.toString().contains("payments.invalid"))
        assertEquals(PlatformPaymentStatus.PROCESSING, restored.status)
        assertTrue(transport.requests[0].url.endsWith("/payment-methods?channel=android"))
        assertEquals("payment-create-key", transport.requests[1].headers["Idempotency-Key"])
        assertEquals("{\"paymentMethodId\":\"sbp\",\"channel\":\"android\"}", transport.requests[1].body)
        assertFalse(transport.requests[1].body.contains("provider"))
        assertTrue(transport.requests[2].url.endsWith("/commerce/payments/$paymentId"))
    }

    @Test
    fun guestCheckoutHandoffUsesProfileTokenAndRedactsBrowserProof() {
        val handoffId = "00000000-0000-4000-8000-000000000421"
        val checkoutUrl = "https://games.dmit.life/checkout/handoff/mgh1_$handoffId.${"A".repeat(43)}"
        val transport = QueueTransport(
            success(
                """{"schemaVersion":1,"handoffId":"$handoffId","productId":"inplacex.full","currency":"RUB","checkoutUrl":"$checkoutUrl","expiresAt":"2099-08-31T15:00:00Z"}""",
            ),
        )
        val sdk = sdk(transport)
        val accessToken = "guest." + "x".repeat(43)

        val handoff = runSuspend {
            sdk.createGuestCheckoutHandoff(
                accessToken,
                "inplacex.full",
                "RUB",
                PlatformIdempotencyKey("guest-handoff-key"),
            )
        }

        assertEquals(handoffId, handoff.id)
        assertEquals(checkoutUrl, handoff.checkoutUrl)
        assertFalse(handoff.toString().contains(checkoutUrl))
        val request = transport.requests.single()
        assertEquals(PlatformHttpMethod.POST, request.method)
        assertTrue(request.url.endsWith("/api/v1/commerce/guest-checkout-handoffs"))
        assertEquals("Bearer $accessToken", request.headers["Authorization"])
        assertEquals("guest-handoff-key", request.headers["Idempotency-Key"])
        assertEquals("{\"productId\":\"inplacex.full\",\"currency\":\"RUB\"}", request.body)
        assertFalse(request.body.contains("accountId"))
        assertFalse(request.body.contains("gamePlayerId"))
    }

    private fun sdk(
        transport: QueueTransport,
        entropy: SecureEntropy = CountingEntropy(),
        baseUrl: String = "https://games.dmit.life",
        gameId: String = "inplacex",
        distributionId: String? = null,
    ) = MirkoriGameSdk(
        MirkoriGameSdkConfig(baseUrl, gameId, RedirectUri, distributionId = distributionId),
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

private fun updateDecisionJson(
    currentVersionCode: Long,
    updateAvailable: Boolean,
    required: Boolean,
    platform: String = "android",
    channel: String = "stable",
    fileName: String = "InplaceX-1.0.0.apk",
    minimumSupportedVersionCode: Long = 40,
    minimumAndroidSdk: Int? = 26,
    packageName: String? = "com.mirkori.inplacex",
    fingerprints: List<String>? = listOf(androidFingerprint()),
): String {
    val release = if (updateAvailable) {
        val androidFields = buildString {
            minimumAndroidSdk?.let { append(",\"minimumAndroidSdk\":$it") }
            packageName?.let { append(",\"packageName\":\"$it\"") }
            fingerprints?.let { values ->
                append(",\"signingCertificateSha256Fingerprints\":[")
                append(values.joinToString(",") { "\"$it\"" })
                append("]")
            }
        }
        ",\"release\":{\"id\":\"inplacex-1.0.0-50\",\"gameId\":\"inplacex\",\"platform\":\"$platform\",\"channel\":\"$channel\",\"versionName\":\"1.0.0\",\"versionCode\":50,\"minimumSupportedVersionCode\":$minimumSupportedVersionCode,\"publishedAt\":\"2026-08-30T12:00:00Z\",\"changelog\":\"Safer update\",\"fileName\":\"$fileName\",\"sizeBytes\":12345678,\"sha256\":\"${"a".repeat(64)}\",\"downloadUrl\":\"https://games.dmit.life/downloads/inplacex-1.0.0-50/$fileName\"$androidFields}"
    } else {
        ""
    }
    return "{\"schemaVersion\":1,\"gameId\":\"inplacex\",\"platform\":\"$platform\",\"channel\":\"$channel\",\"currentVersionCode\":$currentVersionCode,\"updateAvailable\":$updateAvailable,\"required\":$required$release}"
}

private fun distributionUpdateDecisionJson(
    distributionId: String = "rf-mirkori",
    marketScope: String = "rf",
    packageName: String = "com.mirkori.inplacex.rf",
    signingIdentityRef: String = "inplacex-rf-signing",
    paymentChannel: String = "mirkori",
    deliveryChannel: String = "direct_apk",
    downloadUrl: String? = "https://games.dmit.life/downloads/inplacex-rf-42/InplaceX-rf.apk",
): String {
    val fingerprints = "[\"${androidFingerprint()}\"]"
    val downloadField = downloadUrl?.let { ",\"downloadUrl\":\"$it\"" }.orEmpty()
    val distribution = """
        {"id":"$distributionId","gameId":"inplacex","platform":"android","marketScope":"$marketScope",
        "packageName":"$packageName","signingIdentityRef":"$signingIdentityRef",
        "signingCertificateSha256Fingerprints":$fingerprints,"paymentChannel":"$paymentChannel","deliveryChannel":"$deliveryChannel",
        "releaseChannels":["beta","stable"],"status":"active","effectiveConfigurationVersion":3}
    """.trimIndent()
    val release = """
        {"id":"inplacex-rf-42","gameId":"inplacex","distributionId":"$distributionId","platform":"android",
        "channel":"stable","versionName":"1.2.0","versionCode":42,"minimumSupportedVersionCode":40,
        "minimumAndroidSdk":26,"publishedAt":"2026-08-30T12:00:00Z",
        "changelogs":{"ru":"Релиз РФ","en":"RF release"},"fileName":"InplaceX-rf.apk",
        "sizeBytes":12345678,"sha256":"${"a".repeat(64)}"$downloadField,
        "packageName":"$packageName","signingIdentityRef":"$signingIdentityRef",
        "signingCertificateSha256Fingerprints":$fingerprints}
    """.trimIndent()
    return """
        {"schemaVersion":2,"gameId":"inplacex","distribution":$distribution,"channel":"stable",
        "currentVersionCode":39,"updateAvailable":true,"required":true,"release":$release}
    """.trimIndent()
}

private fun androidFingerprint(): String = List(32) { "AA" }.joinToString(":")

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
