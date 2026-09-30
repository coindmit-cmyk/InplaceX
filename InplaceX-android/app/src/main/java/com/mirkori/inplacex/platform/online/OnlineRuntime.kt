package com.mirkori.inplacex.platform.online

import android.content.Context
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.data.local.LocalPlayerProfile
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class OnlineRuntime private constructor(
    private val client: HttpClient,
    private val auth: GuestAuthSessionManager,
    private val googleAuth: KtorGoogleAuthApi,
    private val duel: OnlineDuelClient,
    private val installation: GuestInstallation,
) : AutoCloseable {
    suspend fun createMatch(
        mode: RemoteMatchmakingMode = RemoteMatchmakingMode.CLASSIC,
    ): OnlineClientResult<OnlineMatchTicket> {
        ensureAuthenticatedSession().onlineFailureOrNull()?.let { return it }
        val created = duel.createMatch(mode)
        if (created !is OnlineClientResult.Success) return created
        if (created.value.status == OnlineMatchStatus.MATCHED) return created

        repeat(MaximumTicketPolls) {
            delay(TicketPollDelayMillis)
            when (val ticket = duel.readTicket(created.value.ticketId)) {
                is OnlineClientResult.Success -> {
                    if (ticket.value.status == OnlineMatchStatus.MATCHED) return ticket
                }
                OnlineClientResult.TemporarilyUnavailable -> Unit
                else -> return ticket
            }
        }
        return OnlineClientResult.TemporarilyUnavailable
    }

    suspend fun createGoogleChallenge(): GoogleChallengeResult {
        when (ensureAuthenticatedSession()) {
            is GuestAuthResult.Authenticated -> Unit
            GuestAuthResult.Rejected -> return GoogleChallengeResult.AuthenticationRequired
            GuestAuthResult.Offline,
            GuestAuthResult.TemporarilyUnavailable,
            -> return GoogleChallengeResult.TemporarilyUnavailable
        }
        return withContext(Dispatchers.IO) { googleAuth.challenge() }
    }

    suspend fun createFriendInvite(
        playStyle: RemoteFriendPlayStyle,
        codeLength: Int,
    ): OnlineClientResult<OnlineFriendInvite> {
        ensureAuthenticatedSession().onlineFailureOrNull()?.let { return it }
        return duel.createFriendInvite(playStyle, codeLength)
    }

    suspend fun readFriendInvite(
        inviteCode: String,
    ): OnlineClientResult<OnlineFriendInvite> {
        ensureAuthenticatedSession().onlineFailureOrNull()?.let { return it }
        return duel.readFriendInvite(inviteCode)
    }

    suspend fun acceptFriendInvite(
        inviteCode: String,
    ): OnlineClientResult<OnlineFriendInvite> {
        ensureAuthenticatedSession().onlineFailureOrNull()?.let { return it }
        return duel.acceptFriendInvite(inviteCode)
    }

    suspend fun authenticateWithGoogle(
        idToken: String,
        nonce: String,
    ): GuestAuthResult = withContext(Dispatchers.IO) {
        auth.acceptProviderResult(googleAuth.authenticate(idToken = idToken, nonce = nonce))
    }

    fun signOut() {
        auth.clear()
    }

    suspend fun readSession(sessionId: String): OnlineClientResult<OnlineDuelSnapshotState> =
        duel.readSession(sessionId)

    suspend fun submitSecret(
        sessionId: String,
        revision: Long,
        secret: String,
    ): OnlineClientResult<OnlineDuelSnapshotState> =
        duel.submitSecret(sessionId, revision, secret)

    suspend fun submitGuess(
        sessionId: String,
        revision: Long,
        guess: String,
    ): OnlineClientResult<OnlineDuelSnapshotState> =
        duel.submitGuess(sessionId, revision, guess)

    override fun close() {
        client.close()
    }

    private suspend fun ensureAuthenticatedSession(): GuestAuthResult =
        withContext(Dispatchers.IO) {
            auth.sessionWithFreshAccessTokenOrNull()
                ?.let(GuestAuthResult::Authenticated)
                ?: auth.bootstrap(installation)
        }

    companion object {
        private const val TicketPollDelayMillis = 500L
        private const val MaximumTicketPolls = 40

        fun createOrNull(
            context: Context,
            profile: LocalPlayerProfile,
            locale: String,
            regionCode: String,
            baseUrl: String = BuildConfig.ONLINE_BASE_URL,
            allowCleartextLoopback: Boolean = BuildConfig.ONLINE_ALLOW_CLEARTEXT_LOOPBACK,
        ): OnlineRuntime? {
            if (baseUrl.isBlank()) return null
            val endpoint = OnlineEndpoint(baseUrl, allowCleartextLoopback)
            val client = createOnlineHttpClient()
            val connectivity = AndroidConnectivityGate(context)
            val unauthenticatedTransport = KtorOnlineTransport(
                client = client,
                endpoint = endpoint,
                tokenProvider = EmptyAccessTokenProvider,
                connectivity = connectivity,
            )
            val auth = GuestAuthSessionManager(
                api = KtorGuestAuthApi(unauthenticatedTransport),
                store = AndroidKeystoreGuestSessionStore(context),
            )
            val authenticatedTransport = KtorOnlineTransport(
                client = client,
                endpoint = endpoint,
                tokenProvider = auth,
                connectivity = connectivity,
            )
            return OnlineRuntime(
                client = client,
                auth = auth,
                googleAuth = KtorGoogleAuthApi(authenticatedTransport),
                duel = OnlineDuelClient(authenticatedTransport),
                installation = GuestInstallation(
                    installationId = profile.installationId,
                    locale = locale,
                    regionCode = regionCode,
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
        }
    }
}

private fun GuestAuthResult.onlineFailureOrNull(): OnlineClientResult<Nothing>? = when (this) {
    is GuestAuthResult.Authenticated -> null
    GuestAuthResult.Rejected -> OnlineClientResult.AuthenticationRequired
    GuestAuthResult.Offline -> OnlineClientResult.Offline
    GuestAuthResult.TemporarilyUnavailable -> OnlineClientResult.TemporarilyUnavailable
}

private object EmptyAccessTokenProvider : AccessTokenProvider {
    override suspend fun currentAccessToken(): AccessToken? = null

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? = null
}
