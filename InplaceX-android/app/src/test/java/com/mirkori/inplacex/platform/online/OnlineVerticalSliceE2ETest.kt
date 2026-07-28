package com.mirkori.inplacex.platform.online

import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.identity.CredentialPolicy
import com.mirkori.inplacex.backend.identity.GuestIdentityService
import com.mirkori.inplacex.backend.identity.JdbcGuestIdentityRepository
import com.mirkori.inplacex.backend.identity.Rs256AccessTokenIssuer
import com.mirkori.inplacex.backend.identity.configureIdentityRoutes
import com.mirkori.inplacex.backend.online.AuthoritativeOnlineDuelService
import com.mirkori.inplacex.backend.online.configureOnlineRoutes
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineVerticalSliceE2ETest {
    @Test
    fun `Android transport bootstraps identity and plays server-authoritative turn`() = testApplication {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val policy = CredentialPolicy("inplacex-identity", "inplacex-game-api")
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:android-online-e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        JdbcMigrationRunner().migrate(dataSource)
        val identity = GuestIdentityService(
            identities = JdbcGuestIdentityRepository(dataSource),
            saves = JdbcSaveRepository(dataSource),
            policy = policy,
            accessTokenIssuer = Rs256AccessTokenIssuer(keys.private, policy),
            clock = clock,
        )
        val online = AuthoritativeOnlineDuelService(clock)
        application {
            configureIdentityRoutes(identity)
            configureOnlineRoutes(
                verifier = JwtAccessTokenVerifier(
                    verificationKey = keys.public,
                    policy = JwtVerificationPolicy(
                        issuer = policy.issuer,
                        audience = policy.audience,
                        maximumTokenLifetime = policy.accessTtl,
                    ),
                    clock = clock,
                ),
                service = online,
            )
        }

        val endpoint = OnlineEndpoint("http://localhost", allowCleartextLoopback = true)
        val unauthenticatedTransport = KtorOnlineTransport(
            client = client,
            endpoint = endpoint,
            tokenProvider = NoAccessTokenProvider,
        )
        val store = MemoryGuestSessionStore()
        val auth = GuestAuthSessionManager(
            api = KtorGuestAuthApi(unauthenticatedTransport),
            store = store,
            clockMs = { now.toEpochMilli() },
        )

        val bootstrap = auth.bootstrap(
            GuestInstallation(
                installationId = "integration-installation",
                locale = "ru-RU",
                regionCode = "RU",
                appVersion = "1.0",
            ),
        )
        assertTrue(bootstrap is GuestAuthResult.Authenticated)

        val authenticatedTransport = KtorOnlineTransport(
            client = client,
            endpoint = endpoint,
            tokenProvider = auth,
        )
        val duel = OnlineDuelClient(authenticatedTransport)
        val ticket = duel.createMatch(RemoteMatchmakingMode.CLASSIC)
        val sessionId = requireNotNull((ticket as OnlineClientResult.Success).value.sessionId)
        val initial = duel.readSession(sessionId) as OnlineClientResult.Success
        val active = duel.submitSecret(
            sessionId,
            initial.value.revision,
            "1234",
        ) as OnlineClientResult.Success
        val turn = duel.submitGuess(
            sessionId,
            active.value.revision,
            "0123",
        ) as OnlineClientResult.Success

        assertEquals("active", active.value.phase)
        assertTrue(turn.value.revision > active.value.revision)
        assertTrue(turn.value.attempts.any { it.actor == "player" })
        assertTrue(turn.value.attempts.none { it.exactMatches !in 0..turn.value.codeLength })
    }

    private class MemoryGuestSessionStore : SecureGuestSessionStore {
        private var session: GuestSession? = null

        override fun read(): GuestSession? = session

        override fun write(session: GuestSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private object NoAccessTokenProvider : AccessTokenProvider {
        override suspend fun currentAccessToken(): AccessToken? = null

        override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? = null
    }
}
