package com.mirkori.inplacex.platform.online

import android.content.Context
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.data.local.LocalPlayerProfile
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OnlineRuntime private constructor(
    private val client: HttpClient,
    private val auth: GuestAuthSessionManager,
    private val duel: OnlineDuelClient,
    private val installation: GuestInstallation,
) : AutoCloseable {
    suspend fun createMatch(
        mode: RemoteMatchmakingMode = RemoteMatchmakingMode.CLASSIC,
    ): OnlineClientResult<OnlineMatchTicket> {
        val authenticated = withContext(Dispatchers.IO) {
            auth.sessionWithFreshAccessTokenOrNull()
                ?: when (val result = auth.bootstrap(installation)) {
                    is GuestAuthResult.Authenticated -> result.session
                    GuestAuthResult.Rejected,
                    GuestAuthResult.TemporarilyUnavailable,
                    -> null
                }
        }
        if (authenticated == null) return OnlineClientResult.AuthenticationRequired
        return duel.createMatch(mode)
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

    companion object {
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
            val unauthenticatedTransport = KtorOnlineTransport(
                client = client,
                endpoint = endpoint,
                tokenProvider = EmptyAccessTokenProvider,
            )
            val auth = GuestAuthSessionManager(
                api = KtorGuestAuthApi(unauthenticatedTransport),
                store = AndroidKeystoreGuestSessionStore(context),
            )
            val authenticatedTransport = KtorOnlineTransport(
                client = client,
                endpoint = endpoint,
                tokenProvider = auth,
            )
            return OnlineRuntime(
                client = client,
                auth = auth,
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

private object EmptyAccessTokenProvider : AccessTokenProvider {
    override suspend fun currentAccessToken(): AccessToken? = null

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? = null
}
