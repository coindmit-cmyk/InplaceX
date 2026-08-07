package com.mirkori.inplacex.platform.mirkori

import android.content.Context
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.platform.online.AndroidConnectivityGate
import com.mirkori.platform.sdk.GameClientPlatform
import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.MirkoriGameSdkConfig
import com.mirkori.platform.sdk.PlatformApiException
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformCallbackRejectedException
import com.mirkori.platform.sdk.PlatformCredentials
import com.mirkori.platform.sdk.PlatformProfileConflictException
import io.ktor.client.HttpClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MirkoriPlatformRuntime internal constructor(
    private val sdk: MirkoriGameSdk,
    private val store: SecureMirkoriStateStore,
    private val client: HttpClient? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val operationMutex = Mutex()
    private var persistedState: MirkoriPersistedState? = store.read()

    fun currentAccountState(): MirkoriAccountState = persistedState.toAccountState()

    suspend fun restoreOrBootstrap(): MirkoriAccountState = operationMutex.withLock {
        try {
            ensureFreshSession().toAccountState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure(error)
            persistedState.toAccountState(fallbackUnavailable = true)
        }
    }

    suspend fun beginLogin(): MirkoriLoginResult = operationMutex.withLock {
        try {
            val current = ensureFreshSession()
            if (current.authMode != PlatformAuthMode.GUEST) return@withLock MirkoriLoginResult.AlreadyConnected
            val state = requireNotNull(persistedState)
            val pending = sdk.beginAccountLogin(
                profileAccessToken = current.credentials.accessToken,
                installationId = state.installation.installationId,
            )
            persist(state.copy(pendingLogin = pending))
            AppLog.info(
                tag = LogTag,
                message = "Mirkori Games browser login prepared",
                attributes = mapOf("outcome" to "browser_ready"),
            )
            MirkoriLoginResult.BrowserReady(pending.connectUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure(error)
            error.toLoginFailure()
        }
    }

    suspend fun completeLogin(callbackUrl: String): MirkoriLoginResult = operationMutex.withLock {
        val state = persistedState ?: return@withLock MirkoriLoginResult.Rejected
        val pending = state.pendingLogin ?: return@withLock MirkoriLoginResult.Rejected
        if (pending.expiresAt.toEpochMilli() <= clockMs()) {
            persist(state.copy(pendingLogin = null))
            return@withLock MirkoriLoginResult.Rejected
        }
        try {
            val linked = sdk.completeAccountLogin(callbackUrl, pending)
            persist(state.copy(session = linked, pendingLogin = null))
            AppLog.info(
                tag = LogTag,
                message = "Mirkori Games account connected",
                attributes = mapOf("authMode" to linked.authMode.wireName),
            )
            MirkoriLoginResult.Connected(linked.toAccountState())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PlatformProfileConflictException) {
            persist(state.copy(pendingLogin = null))
            AppLog.warn(
                tag = LogTag,
                message = "Mirkori Games account connection rejected",
                attributes = mapOf("outcome" to "profile_conflict"),
            )
            MirkoriLoginResult.ProfileConflict
        } catch (error: Exception) {
            logFailure(error)
            error.toLoginFailure()
        }
    }

    override fun close() {
        client?.close()
    }

    private suspend fun ensureFreshSession(): GameIdentitySession {
        var state = persistedState
        if (state == null) {
            state = MirkoriPersistedState(sdk.newInstallation())
            persist(state)
        }
        val current = state.session
        if (
            current != null &&
            state.pendingRefresh == null &&
            current.credentials.accessExpiresAt.toEpochMilli() > clockMs() + RefreshSkewMs
        ) {
            return current
        }
        if (
            current != null &&
            (state.pendingRefresh != null || current.credentials.refreshExpiresAt.toEpochMilli() > clockMs())
        ) {
            val pendingRefresh = state.pendingRefresh ?: PendingMirkoriRefresh(
                refreshToken = current.credentials.refreshToken,
                idempotencyKey = sdk.newIdempotencyKey(),
            ).also { pending ->
                state = state.copy(pendingRefresh = pending)
                persist(state)
            }
            try {
                val credentials = sdk.refresh(
                    refreshToken = pendingRefresh.refreshToken,
                    idempotencyKey = pendingRefresh.idempotencyKey,
                )
                return current.withCredentials(credentials).also { refreshed ->
                    persist(state.copy(session = refreshed, pendingRefresh = null))
                }
            } catch (error: PlatformApiException) {
                if (error.status != 401) throw error
                state = state.copy(pendingRefresh = null)
                persist(state)
            }
        }
        val guest = sdk.bootstrapGuest(
            installation = state.installation,
            platform = GameClientPlatform.ANDROID,
            appVersion = BuildConfig.VERSION_NAME,
        )
        persist(state.copy(session = guest, pendingRefresh = null))
        return guest
    }

    private fun persist(state: MirkoriPersistedState) {
        store.write(state)
        persistedState = state
    }

    private fun logFailure(error: Throwable) {
        AppLog.warn(
            tag = LogTag,
            message = "Mirkori Games operation unavailable",
            attributes = mapOf("errorClass" to error.javaClass.name),
        )
    }

    companion object {
        const val RedirectUri = "https://games.dmit.life/connect/inplacex/callback"

        fun createOrNull(
            context: Context,
            baseUrl: String = BuildConfig.MIRKORI_PLATFORM_BASE_URL,
            allowCleartextLoopback: Boolean = BuildConfig.MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK,
        ): MirkoriPlatformRuntime? {
            if (baseUrl.isBlank()) return null
            val client = createMirkoriHttpClient()
            return try {
                val sdk = MirkoriGameSdk(
                    config = MirkoriGameSdkConfig(
                        platformBaseUrl = baseUrl,
                        gameId = "inplacex",
                        redirectUri = RedirectUri,
                        allowCleartextLoopback = allowCleartextLoopback,
                    ),
                    transport = KtorMirkoriPlatformTransport(
                        client = client,
                        connectivity = AndroidConnectivityGate(context),
                    ),
                )
                MirkoriPlatformRuntime(
                    sdk = sdk,
                    store = AndroidKeystoreMirkoriStateStore(context),
                    client = client,
                )
            } catch (error: Exception) {
                client.close()
                throw error
            }
        }

        private const val RefreshSkewMs = 30_000L
        private const val LogTag = "MirkoriPlatform"
    }
}

private fun GameIdentitySession.withCredentials(credentials: PlatformCredentials): GameIdentitySession =
    GameIdentitySession(
        accountId = accountId,
        gamePlayerId = gamePlayerId,
        gameId = gameId,
        installationId = installationId,
        authMode = authMode,
        credentials = credentials,
    )

private fun GameIdentitySession.toAccountState(): MirkoriAccountState = MirkoriAccountState(
    kind = if (authMode == PlatformAuthMode.GUEST) MirkoriAccountStateKind.GUEST else MirkoriAccountStateKind.LINKED,
    gamePlayerId = gamePlayerId,
    authMode = authMode,
)

private fun MirkoriPersistedState?.toAccountState(fallbackUnavailable: Boolean = false): MirkoriAccountState = when {
    this?.session != null -> session.toAccountState()
    fallbackUnavailable -> MirkoriAccountState(MirkoriAccountStateKind.UNAVAILABLE)
    else -> MirkoriAccountState(MirkoriAccountStateKind.INITIALIZING)
}

private fun Throwable.toLoginFailure(): MirkoriLoginResult = when (this) {
    is PlatformCallbackRejectedException -> MirkoriLoginResult.Rejected
    is PlatformApiException -> if (status in 400..499) MirkoriLoginResult.Rejected else MirkoriLoginResult.Unavailable
    is IOException -> MirkoriLoginResult.Unavailable
    else -> MirkoriLoginResult.Unavailable
}
