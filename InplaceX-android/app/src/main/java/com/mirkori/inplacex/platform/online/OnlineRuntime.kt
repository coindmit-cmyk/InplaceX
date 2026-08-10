package com.mirkori.inplacex.platform.online

import android.content.Context
import com.mirkori.inplacex.BuildConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay

class OnlineRuntime private constructor(
    private val client: HttpClient,
    private val duel: OnlineDuelClient,
    private val legacySessionRecovery: LegacyOnlineSessionRecovery,
) : AutoCloseable {
    suspend fun createMatch(
        mode: RemoteMatchmakingMode = RemoteMatchmakingMode.CLASSIC,
        playStyle: RemoteFriendPlayStyle,
        codeLength: Int,
    ): OnlineClientResult<OnlineMatchTicket> {
        val created = duel.createMatch(mode, playStyle, codeLength)
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

    suspend fun createFriendInvite(
        playStyle: RemoteFriendPlayStyle,
        codeLength: Int,
        targetPlayerId: String? = null,
    ): OnlineClientResult<OnlineFriendInvite> {
        return duel.createFriendInvite(playStyle, codeLength, targetPlayerId)
    }

    suspend fun listIncomingFriendInvites(): OnlineClientResult<List<OnlineFriendInvite>> =
        duel.listIncomingFriendInvites()

    suspend fun readFriendInvite(
        inviteCode: String,
    ): OnlineClientResult<OnlineFriendInvite> {
        return duel.readFriendInvite(inviteCode)
    }

    suspend fun acceptFriendInvite(
        inviteCode: String,
    ): OnlineClientResult<OnlineFriendInvite> {
        return duel.acceptFriendInvite(inviteCode)
    }

    suspend fun readSession(sessionId: String): OnlineClientResult<OnlineDuelSnapshotState> {
        return legacySessionRecovery.readSession(sessionId)
    }

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
        private const val TicketPollDelayMillis = 500L
        private const val MaximumTicketPolls = 40

        fun createOrNull(
            context: Context,
            accessTokenProvider: AccessTokenProvider,
            baseUrl: String = BuildConfig.ONLINE_BASE_URL,
            allowCleartextLoopback: Boolean = BuildConfig.ONLINE_ALLOW_CLEARTEXT_LOOPBACK,
        ): OnlineRuntime? {
            if (baseUrl.isBlank()) return null
            val endpoint = OnlineEndpoint(baseUrl, allowCleartextLoopback)
            val client = createOnlineHttpClient()
            val connectivity = AndroidConnectivityGate(context)
            val authenticatedTransport = KtorOnlineTransport(
                client = client,
                endpoint = endpoint,
                tokenProvider = accessTokenProvider,
                connectivity = connectivity,
            )
            val duel = OnlineDuelClient(authenticatedTransport)
            return OnlineRuntime(
                client = client,
                duel = duel,
                legacySessionRecovery = LegacyOnlineSessionRecovery(
                    duel = duel,
                    legacyStore = AndroidKeystoreGuestSessionStore(context),
                    attemptStore = SharedPreferencesLegacyMembershipMigrationAttemptStore(context),
                ),
            )
        }
    }
}
