package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.InstallationIdentity
import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.MirkoriGameSdkConfig
import com.mirkori.platform.sdk.PendingGameLogin
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformCredentials
import com.mirkori.platform.sdk.PlatformHttpRequest
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformIdempotencyKey
import com.mirkori.platform.sdk.PlatformTransport
import com.mirkori.platform.sdk.SecureEntropy
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MirkoriPlatformRuntimeTest {
    @Test
    fun protectedStateCodecRoundTripsCredentialsAndPendingLogin() {
        val state = MirkoriPersistedState(
            installation = InstallationIdentity(InstallationId, "I".repeat(43)),
            session = GameIdentitySession(
                accountId = AccountId,
                gamePlayerId = PlayerId,
                gameId = "inplacex",
                installationId = InstallationId,
                authMode = PlatformAuthMode.LOCAL,
                credentials = credentials("stored"),
            ),
            pendingLogin = PendingGameLogin(
                session = "S".repeat(64),
                state = "T".repeat(43),
                codeVerifier = "V".repeat(43),
                connectUrl = "https://games.dmit.life/connect?session=${"S".repeat(64)}",
                expiresAt = Instant.ofEpochMilli(ExpiresAtMs),
            ),
            pendingRefresh = PendingMirkoriRefresh(
                refreshToken = credentials("stored").refreshToken,
                idempotencyKey = PlatformIdempotencyKey("refresh-attempt-1"),
            ),
        )

        val encoded = MirkoriStateCodec.encode(state)
        val decoded = MirkoriStateCodec.decode(encoded)

        assertEquals(state.installation.installationSecret, decoded.installation.installationSecret)
        assertEquals(PlatformAuthMode.LOCAL, decoded.session?.authMode)
        assertEquals(state.session?.credentials?.refreshToken, decoded.session?.credentials?.refreshToken)
        assertEquals(state.pendingLogin?.codeVerifier, decoded.pendingLogin?.codeVerifier)
        assertEquals(state.pendingRefresh?.refreshToken, decoded.pendingRefresh?.refreshToken)
        assertEquals(state.pendingRefresh?.idempotencyKey?.value, decoded.pendingRefresh?.idempotencyKey?.value)
        assertFalse(decoded.pendingRefresh.toString().contains(state.session?.credentials?.refreshToken.orEmpty()))
        assertThrows(IllegalArgumentException::class.java) {
            MirkoriStateCodec.decode(encoded + 1)
        }
    }

    @Test
    fun protectedStateCodecReadsLegacyVersionWithoutPendingRefresh() {
        val encoded = MirkoriStateCodec.encode(
            MirkoriPersistedState(InstallationIdentity(InstallationId, "I".repeat(43))),
        )
        val legacy = encoded.copyOf(encoded.size - 1).also { ByteBuffer.wrap(it).putInt(1) }

        val decoded = MirkoriStateCodec.decode(legacy)

        assertEquals(InstallationId, decoded.installation.installationId)
        assertNull(decoded.pendingRefresh)
    }

    @Test
    fun runtimeBootstrapsGuestPersistsPkceAndCompletesLinkedAccount() {
        val sessionHandle = "S".repeat(64)
        val transport = QueueTransport(
            ok(bootstrapJson()),
            ok(
                """{"session":"$sessionHandle","connectUrl":"https://games.dmit.life/connect?session=$sessionHandle","expiresAtEpochMs":$ExpiresAtMs}""",
            ),
            ok(exchangeJson()),
        )
        val store = installationStore()
        val runtime = runtime(transport, store)

        val restored = runSuspend { runtime.restoreOrBootstrap() }
        assertEquals(MirkoriAccountStateKind.GUEST, restored.kind)
        assertEquals(PlayerId, store.value?.session?.gamePlayerId)
        assertEquals(InstallationId, store.value?.installation?.installationId)

        val started = runSuspend { runtime.beginLogin() } as MirkoriLoginResult.BrowserReady
        assertEquals("https://games.dmit.life/connect?session=$sessionHandle", started.connectUrl)
        val pending = requireNotNull(store.value?.pendingLogin)
        assertFalse(started.connectUrl.contains(pending.codeVerifier))

        val completed = runSuspend {
            runtime.completeLogin(
                "https://games.dmit.life/connect/inplacex/callback?session=$sessionHandle&state=${pending.state}",
            )
        } as MirkoriLoginResult.Connected

        assertEquals(MirkoriAccountStateKind.LINKED, completed.accountState.kind)
        assertEquals(PlatformAuthMode.LOCAL, completed.accountState.authMode)
        assertEquals(PlatformAuthMode.LOCAL, store.value?.session?.authMode)
        assertNull(store.value?.pendingLogin)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[1].headers["Authorization"].orEmpty().startsWith("Bearer "))
        assertFalse(transport.requests[2].headers.containsKey("Authorization"))
    }

    @Test
    fun profileConflictClearsPendingStateWithoutCallingExchange() {
        val sessionHandle = "Q".repeat(64)
        val transport = QueueTransport(
            ok(bootstrapJson()),
            ok(
                """{"session":"$sessionHandle","connectUrl":"https://games.dmit.life/connect?session=$sessionHandle","expiresAtEpochMs":$ExpiresAtMs}""",
            ),
        )
        val store = installationStore()
        val runtime = runtime(transport, store)
        runSuspend { runtime.restoreOrBootstrap() }
        runSuspend { runtime.beginLogin() }
        val pending = requireNotNull(store.value?.pendingLogin)

        val result = runSuspend {
            runtime.completeLogin(
                "https://games.dmit.life/connect/inplacex/callback?session=$sessionHandle&state=${pending.state}&error=profile_conflict",
            )
        }

        assertEquals(MirkoriLoginResult.ProfileConflict, result)
        assertNull(store.value?.pendingLogin)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun expiredPendingLoginIsClearedWithoutCallingExchange() {
        val sessionHandle = "E".repeat(64)
        val transport = QueueTransport()
        val store = MemoryStore(
            MirkoriPersistedState(
                installation = InstallationIdentity(InstallationId, "I".repeat(43)),
                session = GameIdentitySession(
                    accountId = AccountId,
                    gamePlayerId = PlayerId,
                    gameId = "inplacex",
                    installationId = InstallationId,
                    authMode = PlatformAuthMode.GUEST,
                    credentials = credentials("guest"),
                ),
                pendingLogin = PendingGameLogin(
                    session = sessionHandle,
                    state = "T".repeat(43),
                    codeVerifier = "V".repeat(43),
                    connectUrl = "https://games.dmit.life/connect?session=$sessionHandle",
                    expiresAt = Instant.ofEpochMilli(NowMs),
                ),
            ),
        )
        val runtime = runtime(transport, store)

        val result = runSuspend {
            runtime.completeLogin(
                "https://games.dmit.life/connect/inplacex/callback?session=$sessionHandle&state=${"T".repeat(43)}",
            )
        }

        assertEquals(MirkoriLoginResult.Rejected, result)
        assertNull(store.value?.pendingLogin)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun refreshReusesPersistedIdempotencyKeyAfterAmbiguousRestart() {
        val store = MemoryStore(
            MirkoriPersistedState(
                installation = InstallationIdentity(InstallationId, "I".repeat(43)),
                session = GameIdentitySession(
                    accountId = AccountId,
                    gamePlayerId = PlayerId,
                    gameId = "inplacex",
                    installationId = InstallationId,
                    authMode = PlatformAuthMode.LOCAL,
                    credentials = expiredCredentials("linked"),
                ),
            ),
        )
        val firstTransport = ThrowingTransport(IOException("response was not received"))

        val unavailable = runSuspend { runtime(firstTransport, store).restoreOrBootstrap() }
        val firstKey = requireNotNull(store.value?.pendingRefresh).idempotencyKey.value

        assertEquals(MirkoriAccountStateKind.LINKED, unavailable.kind)
        assertEquals(firstKey, firstTransport.requests.single().headers["Idempotency-Key"])

        val retryTransport = QueueTransport(ok(credentialsJson("refreshed")))
        val restored = runSuspend { runtime(retryTransport, store).restoreOrBootstrap() }

        assertEquals(MirkoriAccountStateKind.LINKED, restored.kind)
        assertEquals(firstKey, retryTransport.requests.single().headers["Idempotency-Key"])
        assertTrue(store.value?.session?.credentials?.accessToken.orEmpty().startsWith("refreshed."))
        assertNull(store.value?.pendingRefresh)
    }

    private fun runtime(transport: PlatformTransport, store: MemoryStore): MirkoriPlatformRuntime {
        val sdk = MirkoriGameSdk(
            config = MirkoriGameSdkConfig(
                platformBaseUrl = "https://games.dmit.life",
                gameId = "inplacex",
                redirectUri = MirkoriPlatformRuntime.RedirectUri,
            ),
            transport = transport,
            entropy = FixedEntropy,
        )
        return MirkoriPlatformRuntime(sdk, store, clockMs = { NowMs })
    }

    private fun installationStore(): MemoryStore = MemoryStore(
        MirkoriPersistedState(InstallationIdentity(InstallationId, "I".repeat(43))),
    )

    private fun bootstrapJson(): String =
        """{"accountId":"$AccountId","gamePlayerId":"$PlayerId","gameId":"inplacex","installationId":"$InstallationId","credentials":${credentialsJson("guest")}}"""

    private fun exchangeJson(): String =
        """{"accountId":"$AccountId","gamePlayerId":"$PlayerId","gameId":"inplacex","authMode":"local","credentials":${credentialsJson("linked")}}"""

    private companion object {
        const val AccountId = "00000000-0000-4000-8000-000000000801"
        const val PlayerId = "00000000-0000-4000-8000-000000000802"
        const val InstallationId = "00000000-0000-4000-8000-000000000803"
        const val NowMs = 1_786_000_000_000L
        const val ExpiresAtMs = 1_786_032_600_000L
    }
}

private class MemoryStore(initial: MirkoriPersistedState? = null) : SecureMirkoriStateStore {
    var value: MirkoriPersistedState? = initial
    override fun read(): MirkoriPersistedState? = value
    override fun write(state: MirkoriPersistedState) {
        value = state
    }
    override fun clear() {
        value = null
    }
}

private class QueueTransport(vararg responses: PlatformHttpResponse) : PlatformTransport {
    private val queued = responses.toMutableList()
    val requests = mutableListOf<PlatformHttpRequest>()
    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        requests += request
        return queued.removeFirstOrNull() ?: error("No queued platform response")
    }
}

private class ThrowingTransport(private val error: IOException) : PlatformTransport {
    val requests = mutableListOf<PlatformHttpRequest>()
    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        requests += request
        throw error
    }
}

private object FixedEntropy : SecureEntropy {
    override fun bytes(count: Int): ByteArray = ByteArray(count) { 7 }
}

private fun credentials(prefix: String) = PlatformCredentials(
    accessToken = "$prefix.${"a".repeat(43)}",
    refreshToken = "$prefix-${"r".repeat(43)}",
    accessExpiresAt = Instant.ofEpochMilli(1_786_032_600_000L),
    refreshExpiresAt = Instant.ofEpochMilli(1_788_624_600_000L),
)

private fun expiredCredentials(prefix: String) = PlatformCredentials(
    accessToken = "$prefix.${"a".repeat(43)}",
    refreshToken = "$prefix-${"r".repeat(43)}",
    accessExpiresAt = Instant.ofEpochMilli(1_785_999_999_999L),
    refreshExpiresAt = Instant.ofEpochMilli(1_788_624_600_000L),
)

private fun credentialsJson(prefix: String): String =
    """{"accessToken":"$prefix.${"a".repeat(43)}","refreshToken":"$prefix-${"r".repeat(43)}","accessExpiresAtEpochMs":1786032600000,"refreshExpiresAtEpochMs":1788624600000}"""

private fun ok(body: String) = PlatformHttpResponse(200, body)

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
    check(latch.await(10, TimeUnit.SECONDS))
    return requireNotNull(outcome).getOrThrow()
}
