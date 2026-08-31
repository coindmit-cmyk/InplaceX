package com.mirkori.inplacex.platform.online

import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.online.AuthoritativeOnlineDuelService
import com.mirkori.inplacex.backend.online.configureOnlineRoutes
import com.mirkori.inplacex.platform.mirkori.MirkoriPersistedState
import com.mirkori.inplacex.platform.mirkori.MirkoriPlatformRuntime
import com.mirkori.inplacex.platform.mirkori.SecureMirkoriStateStore
import com.mirkori.platform.sdk.InstallationIdentity
import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.MirkoriGameSdkConfig
import com.mirkori.platform.sdk.PlatformHttpRequest
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformTransport
import io.ktor.server.testing.testApplication
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineVerticalSliceE2ETest {
    @Test
    fun `Google and local principals receive and accept invite then finish the same duel`() = testApplication {
        val now = Instant.parse("2026-08-26T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val firstPlayer = UUID.randomUUID().toString()
        val secondPlayer = UUID.randomUUID().toString()
        application {
            configureOnlineRoutes(
                verifier = JwtAccessTokenVerifier(
                    keys.public,
                    JwtVerificationPolicy.platformGame(TokenIssuer, TokenAudience, GameId),
                    clock,
                ),
                service = AuthoritativeOnlineDuelService(clock = clock),
            )
        }
        fun player(id: String, authMode: String): OnlineDuelClient {
            val token = AccessToken.from(platformToken(keys.private, UUID.randomUUID().toString(), id, now, authMode))
            return OnlineDuelClient(KtorOnlineTransport(
                client,
                OnlineEndpoint("http://localhost", allowCleartextLoopback = true),
                object : AccessTokenProvider {
                    override suspend fun currentAccessToken() = token
                    override suspend fun refreshAccessToken(rejectedToken: AccessToken) = token
                },
            ))
        }
        val host = player(firstPlayer, "google")
        val guest = player(secondPlayer, "local")
        val outsider = player(UUID.randomUUID().toString(), "google")
        val invite = (host.createFriendInvite(RemoteFriendPlayStyle.TURN_BASED, 4, secondPlayer)
            as OnlineClientResult.Success).value
        val incoming = (guest.listIncomingFriendInvites() as OnlineClientResult.Success).value
        assertEquals(invite.inviteCode, incoming.single().inviteCode)
        assertTrue((outsider.listIncomingFriendInvites() as OnlineClientResult.Success).value.isEmpty())
        val accepted = (guest.acceptFriendInvite(invite.inviteCode) as OnlineClientResult.Success).value
        val repeated = (guest.acceptFriendInvite(invite.inviteCode) as OnlineClientResult.Success).value
        val sessionId = requireNotNull(accepted.sessionId)
        assertEquals(sessionId, repeated.sessionId)
        assertEquals(sessionId, (host.readFriendInvite(invite.inviteCode) as OnlineClientResult.Success).value.sessionId)
        assertTrue((guest.listIncomingFriendInvites() as OnlineClientResult.Success).value.isEmpty())
        assertEquals(OnlineClientResult.MembershipRejected, outsider.readSession(sessionId))

        val setup = (host.readSession(sessionId) as OnlineClientResult.Success).value
        host.submitSecret(sessionId, setup.revision, "1234") as OnlineClientResult.Success
        val guestSetup = (guest.readSession(sessionId) as OnlineClientResult.Success).value
        guest.submitSecret(sessionId, guestSetup.revision, "5678") as OnlineClientResult.Success
        val active = (host.readSession(sessionId) as OnlineClientResult.Success).value
        assertEquals("active", active.phase)
        if (active.currentTurn == "player") {
            host.submitGuess(sessionId, active.revision, "5678") as OnlineClientResult.Success
        } else {
            guest.submitGuess(sessionId, active.revision, "1234") as OnlineClientResult.Success
        }
        val hostEnd = (host.readSession(sessionId) as OnlineClientResult.Success).value
        val guestEnd = (guest.readSession(sessionId) as OnlineClientResult.Success).value
        assertEquals("finished", hostEnd.phase)
        assertEquals("finished", guestEnd.phase)
        assertEquals(hostEnd.revision, guestEnd.revision)
        assertEquals(if (hostEnd.winner == "player") "opponent" else "player", guestEnd.winner)
        assertTrue(hostEnd.attempts.filter { it.actor == "opponent" }.all { it.ownGuess == null })
        assertTrue(guestEnd.attempts.filter { it.actor == "opponent" }.all { it.ownGuess == null })
    }

    @Test
    fun `fresh Mirkori guest token authorizes the first online request and authoritative turn`() = testApplication {
        val now = Instant.parse("2026-08-07T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val gamePlayerId = UUID.randomUUID().toString()
        val accountId = UUID.randomUUID().toString()
        val accessToken = platformToken(
            signingKey = keys.private,
            accountId = accountId,
            gamePlayerId = gamePlayerId,
            now = now,
        )
        val platformTransport = BootstrapPlatformTransport(
            PlatformHttpResponse(
                200,
                """{"accountId":"$accountId","gamePlayerId":"$gamePlayerId","gameId":"$GameId","installationId":"$InstallationId","credentials":{"accessToken":"$accessToken","refreshToken":"${"r".repeat(64)}","accessExpiresAtEpochMs":${now.plusSeconds(900).toEpochMilli()},"refreshExpiresAtEpochMs":${now.plus(Duration.ofDays(30)).toEpochMilli()}}}""",
            ),
        )
        val platformRuntime = MirkoriPlatformRuntime(
            sdk = MirkoriGameSdk(
                config = MirkoriGameSdkConfig(
                    platformBaseUrl = "https://games.dmit.life",
                    gameId = GameId,
                    redirectUri = MirkoriPlatformRuntime.RedirectUri,
                ),
                transport = platformTransport,
            ),
            store = MemoryMirkoriStore(
                MirkoriPersistedState(
                    InstallationIdentity(InstallationId, "i".repeat(43)),
                ),
            ),
            clockMs = now::toEpochMilli,
        )
        val matchmakingClock = E2EMutableClock(now)
        val online = AuthoritativeOnlineDuelService(
            clock = matchmakingClock,
            botFallbackDelay = Duration.ofSeconds(5),
        )
        application {
            configureOnlineRoutes(
                verifier = JwtAccessTokenVerifier(
                    verificationKey = keys.public,
                    policy = JwtVerificationPolicy.platformGame(
                        issuer = TokenIssuer,
                        audience = TokenAudience,
                        gameId = GameId,
                    ),
                    clock = clock,
                ),
                service = online,
            )
        }

        val duel = OnlineDuelClient(
            KtorOnlineTransport(
                client = client,
                endpoint = OnlineEndpoint("http://localhost", allowCleartextLoopback = true),
                tokenProvider = platformRuntime,
            ),
        )
        val searching = duel.createMatch(
            mode = RemoteMatchmakingMode.CLASSIC,
            playStyle = RemoteFriendPlayStyle.RACE,
            codeLength = 6,
        )
        val createdTicket = (searching as OnlineClientResult.Success).value

        assertEquals(OnlineMatchStatus.SEARCHING, createdTicket.status)
        assertEquals(1, platformTransport.requests.size)
        assertTrue(platformTransport.requests.single().url.endsWith("/api/v1/auth/guest/bootstrap"))

        matchmakingClock.advance(Duration.ofSeconds(5))
        val ticket = duel.readTicket(createdTicket.ticketId) as OnlineClientResult.Success
        assertEquals(OnlineMatchStatus.MATCHED, ticket.value.status)
        assertTrue(ticket.value.matchedWithBot)
        val sessionId = requireNotNull(ticket.value.sessionId)
        val initial = duel.readSession(sessionId) as OnlineClientResult.Success
        val active = duel.submitSecret(
            sessionId,
            initial.value.revision,
            "111234",
        ) as OnlineClientResult.Success
        val turn = duel.submitGuess(
            sessionId,
            active.value.revision,
            "001001",
        ) as OnlineClientResult.Success

        assertEquals("active", active.value.phase)
        assertTrue(turn.value.revision > active.value.revision)
        assertTrue(turn.value.attempts.any { it.actor == "player" })
        assertTrue(turn.value.attempts.none { it.exactMatches !in 0..turn.value.codeLength })
        assertEquals(1, platformTransport.requests.size)
    }

    private fun platformToken(
        signingKey: PrivateKey,
        accountId: String,
        gamePlayerId: String,
        now: Instant,
        authMode: String = "guest",
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"iss":"$TokenIssuer","aud":"$TokenAudience","sub":"$accountId","pid":"$gamePlayerId","gid":"$GameId","sid":"${UUID.randomUUID()}","amr":"$authMode","iat":${now.epochSecond},"exp":${now.plusSeconds(900).epochSecond},"jti":"${UUID.randomUUID()}","platform_features":["online-v1"]}"""
                .toByteArray(StandardCharsets.UTF_8),
        )
        val unsigned = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey)
            update(unsigned.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return "$unsigned.${encoder.encodeToString(signature)}"
    }

    private companion object {
        const val TokenIssuer = "mirkori-platform"
        const val TokenAudience = "mirkori-games"
        const val GameId = "inplacex"
        const val InstallationId = "00000000-0000-4000-8000-000000000801"
    }
}

private class E2EMutableClock(
    @Volatile private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = E2EMutableClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

private class MemoryMirkoriStore(
    private var state: MirkoriPersistedState?,
) : SecureMirkoriStateStore {
    override fun read(): MirkoriPersistedState? = state

    override fun write(state: MirkoriPersistedState) {
        this.state = state
    }

    override fun clear() {
        state = null
    }
}

private class BootstrapPlatformTransport(
    private val response: PlatformHttpResponse,
) : PlatformTransport {
    val requests = mutableListOf<PlatformHttpRequest>()

    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        requests += request
        return response
    }
}
