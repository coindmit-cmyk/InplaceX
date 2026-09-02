package com.mirkori.inplacex.platform.mirkori

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.platform.online.AndroidConnectivityGate
import com.mirkori.inplacex.platform.online.AccessToken
import com.mirkori.inplacex.platform.online.AccessTokenProvider
import com.mirkori.inplacex.platform.online.AccessTokenTemporarilyUnavailableException
import com.mirkori.platform.sdk.GameClientPlatform
import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.MirkoriGameSdkConfig
import com.mirkori.platform.sdk.PlatformApiException
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformCallbackRejectedException
import com.mirkori.platform.sdk.PlatformCredentials
import com.mirkori.platform.sdk.PlatformFriendRequest
import com.mirkori.platform.sdk.PlatformProfileConflictException
import com.mirkori.platform.sdk.PlatformProfileConflictResolution
import com.mirkori.platform.sdk.PlatformPublicPlayerProfile
import io.ktor.client.HttpClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MirkoriPlatformRuntime internal constructor(
    internal val sdk: MirkoriGameSdk,
    private val store: SecureMirkoriStateStore,
    private val client: HttpClient? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val bootMarker: () -> Long? = { 0L },
    proConfigured: Boolean = false,
) : AccessTokenProvider, AutoCloseable {
    private val operationMutex = Mutex()
    @Volatile
    private var persistedState: MirkoriPersistedState? = store.read()
    private val mutableAccountState = MutableStateFlow(persistedState.toAccountState())

    val accountState: StateFlow<MirkoriAccountState> = mutableAccountState.asStateFlow()
    val proAccessService: MirkoriProAccessService? by lazy {
        if (proConfigured) MirkoriProAccessService(this) else null
    }

    fun currentAccountState(): MirkoriAccountState = persistedState.toAccountState()

    internal fun currentPersistedState(): MirkoriPersistedState? = persistedState

    internal fun nowMs(): Long = clockMs()

    internal fun serverTimeRevision(): Long = sdk.latestServerTimeObservation()?.revision ?: 0L

    internal fun captureTrustedTimeAfter(revisionBefore: Long): MirkoriTrustedTimeAnchor? {
        val observation = sdk.latestServerTimeObservation()
            ?.takeIf { it.revision > revisionBefore }
            ?: return null
        return newTrustedTimeAnchor(observation.serverEpochMs)
    }

    internal fun newTrustedTimeAnchor(serverEpochMs: Long): MirkoriTrustedTimeAnchor? {
        val currentBoot = bootMarker() ?: return null
        val monotonicNow = monotonicClockMs()
        if (currentBoot < 0 || monotonicNow < 0 || serverEpochMs <= 0) return null
        return MirkoriTrustedTimeAnchor(
            serverEpochMs = serverEpochMs,
            monotonicAtObservationMs = monotonicNow,
            bootMarker = currentBoot,
        )
    }

    internal fun trustedNowMs(anchor: MirkoriTrustedTimeAnchor? = persistedState?.trustedTimeAnchor): Long? {
        anchor ?: return null
        val currentBoot = bootMarker() ?: return null
        val monotonicNow = monotonicClockMs()
        if (currentBoot != anchor.bootMarker || monotonicNow < anchor.monotonicAtObservationMs) return null
        return runCatching {
            Math.addExact(anchor.serverEpochMs, monotonicNow - anchor.monotonicAtObservationMs)
        }.getOrNull()
    }

    internal suspend fun <T> withOperationLock(
        block: suspend MirkoriPlatformRuntime.() -> T,
    ): T = operationMutex.withLock { block() }

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

    suspend fun beginGoogleLogin(): MirkoriLoginResult = operationMutex.withLock {
        try {
            val current = ensureFreshSession()
            val state = requireNotNull(persistedState)
            val pending = sdk.beginAccountLogin(
                profileAccessToken = current.credentials.accessToken,
                installationId = state.installation.installationId,
            )
            persist(state.copy(pendingLogin = pending))
            AppLog.info(
                tag = LogTag,
                message = "Mirkori Games native Google login prepared",
                attributes = mapOf("outcome" to "credential_required"),
            )
            MirkoriLoginResult.GoogleCredentialRequired(pending.state)
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
            persist(state.withSession(linked).copy(pendingLogin = null))
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

    suspend fun completeGoogleLogin(
        idToken: String,
        conflictResolution: PlatformProfileConflictResolution =
            PlatformProfileConflictResolution.KEEP_CURRENT_PROFILE,
    ): MirkoriLoginResult = operationMutex.withLock {
        try {
            val current = ensureFreshSession()
            val state = requireNotNull(persistedState)
            val pending = state.pendingLogin ?: return@withLock MirkoriLoginResult.Rejected
            if (pending.expiresAt.toEpochMilli() <= clockMs()) {
                persist(state.copy(pendingLogin = null))
                return@withLock MirkoriLoginResult.Rejected
            }
            val linked = sdk.completeGoogleAccountLogin(
                profileAccessToken = current.credentials.accessToken,
                idToken = idToken,
                pending = pending,
                conflictResolution = conflictResolution,
            )
            persist(state.withSession(linked).copy(pendingLogin = null))
            AppLog.info(
                tag = LogTag,
                message = "Mirkori Games Google account connected",
                attributes = mapOf("authMode" to linked.authMode.wireName),
            )
            MirkoriLoginResult.Connected(linked.toAccountState())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PlatformProfileConflictException) {
            AppLog.warn(
                tag = LogTag,
                message = "Mirkori Games Google account connection needs confirmation",
                attributes = mapOf(
                    "outcome" to "profile_conflict",
                    "resolution" to conflictResolution.wireName,
                ),
            )
            MirkoriLoginResult.ProfileConflict
        } catch (error: Exception) {
            logFailure(error)
            error.toLoginFailure()
        }
    }

    suspend fun cancelPendingLogin() = operationMutex.withLock {
        persistedState?.let { state ->
            if (state.pendingLogin != null) persist(state.copy(pendingLogin = null))
        }
        AppLog.info(
            tag = LogTag,
            message = "Mirkori Games pending login cancelled",
            attributes = mapOf("outcome" to "kept_current_profile"),
        )
    }

    suspend fun signOutOnDevice(): MirkoriAccountState = operationMutex.withLock {
        store.clear()
        val freshState = MirkoriPersistedState(sdk.newInstallation())
        persist(freshState)
        AppLog.info(
            tag = LogTag,
            message = "Mirkori Games credentials cleared on device",
            attributes = mapOf("outcome" to "local_sign_out"),
        )
        try {
            ensureFreshSession().toAccountState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure(error)
            MirkoriAccountState(MirkoriAccountStateKind.UNAVAILABLE).also {
                mutableAccountState.value = it
            }
        }
    }

    suspend fun loadPublicProfile(): MirkoriPublicProfileResult = operationMutex.withLock {
        try {
            MirkoriPublicProfileResult.Success(
                authenticatedPlatformRequest { accessToken -> sdk.publicProfile(accessToken) }.toAppProfile(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformApiException) {
            logFailure(error)
            if (error.status in 400..499) MirkoriPublicProfileResult.Rejected
            else MirkoriPublicProfileResult.Unavailable
        } catch (error: Exception) {
            logFailure(error)
            MirkoriPublicProfileResult.Unavailable
        }
    }

    suspend fun searchPlayers(query: String): MirkoriPlayerSearchResult = operationMutex.withLock {
        try {
            val players = authenticatedPlatformRequest { accessToken ->
                sdk.searchPlayers(accessToken, query)
            }.map(PlatformPublicPlayerProfile::toAppProfile)
            MirkoriPlayerSearchResult.Success(players)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            MirkoriPlayerSearchResult.Rejected
        } catch (error: Exception) {
            logFailure(error)
            MirkoriPlayerSearchResult.Unavailable
        }
    }

    suspend fun updatePublicHandle(
        handle: String,
        displayName: String,
    ): MirkoriPublicProfileResult = operationMutex.withLock {
        try {
            val profile = authenticatedPlatformRequest { accessToken ->
                sdk.updatePublicProfile(accessToken, handle, displayName)
            }
            MirkoriPublicProfileResult.Success(profile.toAppProfile())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformApiException) {
            when {
                error.status == 409 && error.errorCode == "handle_taken" ->
                    MirkoriPublicProfileResult.HandleTaken
                error.status in 400..499 -> MirkoriPublicProfileResult.Rejected
                else -> {
                    logFailure(error)
                    MirkoriPublicProfileResult.Unavailable
                }
            }
        } catch (error: IllegalArgumentException) {
            MirkoriPublicProfileResult.Rejected
        } catch (error: Exception) {
            logFailure(error)
            MirkoriPublicProfileResult.Unavailable
        }
    }

    suspend fun updatePublicProfile(
        handle: String? = null,
        displayName: String? = null,
        avatarKey: String? = null,
    ): MirkoriPublicProfileResult = operationMutex.withLock {
        try {
            val profile = authenticatedPlatformRequest { accessToken ->
                sdk.updatePublicProfile(
                    profileAccessToken = accessToken,
                    handle = handle,
                    displayName = displayName,
                    avatarKey = avatarKey,
                )
            }
            MirkoriPublicProfileResult.Success(profile.toAppProfile())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformApiException) {
            when {
                error.status == 409 && error.errorCode == "handle_taken" ->
                    MirkoriPublicProfileResult.HandleTaken
                error.status in 400..499 -> MirkoriPublicProfileResult.Rejected
                else -> {
                    logFailure(error)
                    MirkoriPublicProfileResult.Unavailable
                }
            }
        } catch (error: IllegalArgumentException) {
            MirkoriPublicProfileResult.Rejected
        } catch (error: Exception) {
            logFailure(error)
            MirkoriPublicProfileResult.Unavailable
        }
    }

    suspend fun sendFriendRequest(targetGamePlayerId: String): MirkoriFriendOperationResult =
        operationMutex.withLock {
            try {
                val request = authenticatedPlatformRequest { accessToken ->
                    sdk.createFriendRequest(accessToken, targetGamePlayerId)
                }
                MirkoriFriendOperationResult.Success(request.toAppFriendRequest())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PlatformApiException) {
                if (error.status in 400..499) MirkoriFriendOperationResult.Rejected else {
                    logFailure(error)
                    MirkoriFriendOperationResult.Unavailable
                }
            } catch (error: Exception) {
                logFailure(error)
                MirkoriFriendOperationResult.Unavailable
            }
        }

    suspend fun incomingFriendRequests(): MirkoriIncomingFriendRequestsResult = operationMutex.withLock {
        try {
            val requests = authenticatedPlatformRequest { accessToken ->
                sdk.incomingFriendRequests(accessToken)
            }.map(PlatformFriendRequest::toAppFriendRequest)
            MirkoriIncomingFriendRequestsResult.Success(requests)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure(error)
            MirkoriIncomingFriendRequestsResult.Unavailable
        }
    }

    suspend fun acceptFriendRequest(requestId: String): MirkoriFriendOperationResult =
        operationMutex.withLock {
            try {
                val request = authenticatedPlatformRequest { accessToken ->
                    sdk.acceptFriendRequest(accessToken, requestId)
                }
                MirkoriFriendOperationResult.Success(request.toAppFriendRequest())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PlatformApiException) {
                if (error.status in 400..499) MirkoriFriendOperationResult.Rejected else {
                    logFailure(error)
                    MirkoriFriendOperationResult.Unavailable
                }
            } catch (error: Exception) {
                logFailure(error)
                MirkoriFriendOperationResult.Unavailable
            }
        }

    suspend fun friends(): MirkoriFriendsResult = operationMutex.withLock {
        try {
            val players = authenticatedPlatformRequest { accessToken -> sdk.friends(accessToken) }
                .map(PlatformPublicPlayerProfile::toAppProfile)
            MirkoriFriendsResult.Success(players)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure(error)
            MirkoriFriendsResult.Unavailable
        }
    }

    override suspend fun currentAccessToken(): AccessToken? = accessTokenOrNull(forceRefresh = false)

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? =
        operationMutex.withLock {
            try {
                val current = persistedState?.session
                    ?.takeIf { it.credentials.accessExpiresAt.toEpochMilli() > clockMs() }
                    ?.credentials
                    ?.accessToken
                    ?.let(AccessToken::from)
                if (current != null && !current.sameValueAs(rejectedToken)) {
                    return@withLock current
                }
                AccessToken.from(ensureFreshSession(forceRefresh = true).credentials.accessToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logFailure(error)
                throw AccessTokenTemporarilyUnavailableException(error)
            }
        }

    override fun close() {
        client?.close()
    }

    private suspend fun accessTokenOrNull(forceRefresh: Boolean): AccessToken? = operationMutex.withLock {
        try {
            AccessToken.from(ensureFreshSession(forceRefresh).credentials.accessToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure(error)
            throw AccessTokenTemporarilyUnavailableException(error)
        }
    }

    internal suspend fun ensureFreshSession(forceRefresh: Boolean = false): GameIdentitySession {
        var state = persistedState
        if (state == null) {
            state = MirkoriPersistedState(sdk.newInstallation())
            persist(state)
        }
        val current = state.session
        if (
            !forceRefresh && current != null &&
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
        persist(state.withSession(guest).copy(pendingRefresh = null))
        return guest
    }

    private suspend fun <T> authenticatedPlatformRequest(block: suspend (String) -> T): T {
        var session = ensureFreshSession()
        return try {
            block(session.credentials.accessToken)
        } catch (error: PlatformApiException) {
            if (error.status != 401) throw error
            session = ensureFreshSession(forceRefresh = true)
            block(session.credentials.accessToken)
        }
    }

    internal fun persist(state: MirkoriPersistedState) {
        store.write(state)
        persistedState = state
        mutableAccountState.value = state.toAccountState()
    }

    internal fun logFailure(error: Throwable) {
        val apiAttributes = (error as? PlatformApiException)?.let { apiError ->
            mapOf(
                "httpStatus" to apiError.status.toString(),
                "errorCode" to apiError.errorCode,
            )
        }.orEmpty()
        AppLog.warn(
            tag = LogTag,
            message = "Mirkori Games operation unavailable",
            attributes = mapOf("errorClass" to error.javaClass.name) + apiAttributes,
        )
    }

    companion object {
        const val RedirectUri = "https://games.dmit.life/connect/inplacex/callback"

        fun createOrNull(
            context: Context,
            baseUrl: String = BuildConfig.MIRKORI_PLATFORM_BASE_URL,
            allowCleartextLoopback: Boolean = BuildConfig.MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK,
            proEnabled: Boolean = BuildConfig.MIRKORI_PRO_ENABLED,
            proDistributionId: String = BuildConfig.MIRKORI_PRO_DISTRIBUTION_ID,
            proPublicKeys: String = BuildConfig.MIRKORI_PRO_PUBLIC_KEYS,
        ): MirkoriPlatformRuntime? {
            if (baseUrl.isBlank()) return null
            val proConfiguration = MirkoriProClientConfiguration.parseOrNull(
                enabled = proEnabled,
                distributionId = proDistributionId,
                encodedPublicKeys = proPublicKeys,
            )
            val client = createMirkoriHttpClient()
            return try {
                val sdk = MirkoriGameSdk(
                    config = MirkoriGameSdkConfig(
                        platformBaseUrl = baseUrl,
                        gameId = "inplacex",
                        redirectUri = RedirectUri,
                        allowCleartextLoopback = allowCleartextLoopback,
                        distributionId = proConfiguration?.distributionId,
                    ),
                    transport = KtorMirkoriPlatformTransport(
                        client = client,
                        connectivity = AndroidConnectivityGate(context),
                    ),
                    proSnapshotVerifier = proConfiguration?.snapshotVerifier,
                )
                MirkoriPlatformRuntime(
                    sdk = sdk,
                    store = AndroidKeystoreMirkoriStateStore(context),
                    client = client,
                    monotonicClockMs = SystemClock::elapsedRealtime,
                    bootMarker = {
                        runCatching {
                            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
                        }.getOrNull()
                    },
                    proConfigured = proConfiguration != null,
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

private fun PlatformPublicPlayerProfile.toAppProfile(): MirkoriPublicPlayerProfile =
    MirkoriPublicPlayerProfile(
        gamePlayerId = gamePlayerId,
        handle = handle,
        displayName = displayName,
        avatarUrl = avatarUrl,
    )

private fun PlatformFriendRequest.toAppFriendRequest(): MirkoriFriendRequest = MirkoriFriendRequest(
    requestId = requestId,
    player = player.toAppProfile(),
)

private fun MirkoriPersistedState.withSession(newSession: GameIdentitySession): MirkoriPersistedState {
    val sameProfile = session?.let { current ->
        current.accountId == newSession.accountId && current.gamePlayerId == newSession.gamePlayerId
    } ?: false
    return copy(
        session = newSession,
        pendingLogin = pendingLogin.takeIf { sameProfile },
        pendingPurchase = pendingPurchase.takeIf { sameProfile },
        confirmedEntitlements = confirmedEntitlements.takeIf { sameProfile },
        confirmedProAccess = confirmedProAccess.takeIf { sameProfile },
    )
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
    accountIdentity = accountId,
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
