package com.mirkori.inplacex.backend.identity

import com.mirkori.inplacex.backend.persistence.IdempotencyKeyReusedException
import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
import com.mirkori.inplacex.backend.persistence.RevisionConflictException
import com.mirkori.inplacex.backend.persistence.StoredSaveSnapshot
import com.mirkori.inplacex.backend.persistence.transaction
import com.mirkori.inplacex.logging.InplaceXLogger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

enum class GuestPlatform { ANDROID, IOS, DESKTOP, UNKNOWN }

data class GuestBootstrapCommand(
    val installationId: String,
    val platform: GuestPlatform,
    val appVersion: String? = null,
    val locale: String? = null,
    val regionHint: String? = null,
)

data class PlayerProfile(
    val playerId: String,
    val displayName: String,
    val locale: String?,
    val regionHint: String?,
    val revision: Long,
    val updatedAt: Instant,
)

data class ProfileUpdateCommand(
    val expectedRevision: Long,
    val locale: String?,
    val regionHint: String?,
)

sealed class ProfileUpdateResult {
    data class Applied(val profile: PlayerProfile) : ProfileUpdateResult()
    data class Conflict(val expectedRevision: Long, val current: PlayerProfile) : ProfileUpdateResult()
}

data class CloudSaveSnapshot(
    val saveSchemaVersion: Int,
    val revision: Long,
    val stateJson: String,
    val updatedAt: Instant,
)

data class CloudSavePutCommand(
    val commandId: String,
    val expectedRevision: Long,
    val saveSchemaVersion: Int,
    val stateJson: String,
)

sealed class CloudSaveWriteResult {
    data class Applied(val snapshot: CloudSaveSnapshot, val replayed: Boolean) : CloudSaveWriteResult()
    data class Conflict(val expectedRevision: Long, val current: CloudSaveSnapshot) : CloudSaveWriteResult()
}

class RenewableCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
) {
    override fun toString(): String = "RenewableCredentials([redacted])"
}

data class GuestBootstrapResult(
    val playerId: String,
    val accountKind: String,
    val credentials: RenewableCredentials,
)

class RefreshTokenRejectedException : IllegalStateException("Refresh token is not accepted")
class GoogleIdentityRejectedException : IllegalStateException("Google identity is not accepted")
class GoogleIdentityConflictException : IllegalStateException("Player already has a different Google identity")
class GoogleIdentityUnavailableException : IllegalStateException("Google identity provider is unavailable")

data class GoogleAuthChallenge(
    val nonce: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "GoogleAuthChallenge([redacted])"
}

data class CredentialPolicy(
    val issuer: String,
    val audience: String,
    val accessTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(30),
) {
    init {
        require(issuer.isNotBlank())
        require(audience.isNotBlank())
        require(!accessTtl.isNegative && !accessTtl.isZero)
        require(!refreshTtl.isNegative && !refreshTtl.isZero)
        require(refreshTtl >= accessTtl)
    }
}

fun interface AccessTokenIssuer {
    fun issue(playerId: String, issuedAt: Instant, expiresAt: Instant): String
}

/**
 * Доменный сервис без HTTP-привязки. Маршруты передают ему уже проверенные
 * транспортные данные и не получают доступ к хранимым хешам токенов.
 */
class GuestIdentityService(
    private val identities: JdbcGuestIdentityRepository,
    private val saves: JdbcSaveRepository,
    private val policy: CredentialPolicy,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val googleIdentityVerifier: GoogleIdentityVerifier? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val logger: InplaceXLogger = InplaceXLogger(),
) {
    fun bootstrap(command: GuestBootstrapCommand): GuestBootstrapResult {
        validateBootstrap(command)
        val player = identities.findOrCreateGuest(
            installationHash = sha256(command.installationId),
            generatedPlayerId = UUID.randomUUID().toString(),
            platform = command.platform,
            appVersion = command.appVersion,
            locale = command.locale,
            regionHint = command.regionHint,
        )
        val credentials = issueCredentials(player.playerId)
        logger.info("GuestIdentity", "guest bootstrap completed", mapOf("playerId" to player.playerId))
        return GuestBootstrapResult(player.playerId, accountKind = "guest", credentials = credentials)
    }

    fun refresh(refreshToken: String): RenewableCredentials {
        require(refreshToken.isNotBlank())
        val now = clock.instant()
        val nextRefreshToken = newOpaqueToken()
        val rotation = identities.rotateRefreshToken(
            presentedTokenHash = sha256(refreshToken),
            replacementTokenHash = sha256(nextRefreshToken),
            now = now,
        ) ?: run {
            logger.warn("GuestIdentity", "refresh token rejected", mapOf("outcome" to "unauthorized"))
            throw RefreshTokenRejectedException()
        }
        val credentials = credentialsFor(rotation.playerId, nextRefreshToken, rotation.refreshExpiresAt)
        logger.info("GuestIdentity", "refresh token rotated", mapOf("playerId" to rotation.playerId))
        return credentials
    }

    fun createGoogleChallenge(playerId: String): GoogleAuthChallenge {
        requireCanonicalPlayerId(playerId)
        if (googleIdentityVerifier == null) throw GoogleIdentityUnavailableException()
        val nonce = newNonce()
        val expiresAt = clock.instant().plus(GoogleChallengeTtl)
        identities.createGoogleChallenge(
            playerId = playerId,
            nonceHash = sha256(nonce),
            expiresAt = expiresAt,
        )
        logger.info(
            "GuestIdentity",
            "Google auth challenge created",
            mapOf("playerId" to playerId),
        )
        return GoogleAuthChallenge(nonce = nonce, expiresAt = expiresAt)
    }

    fun authenticateWithGoogle(
        currentPlayerId: String,
        idToken: String,
        nonce: String,
    ): GuestBootstrapResult {
        requireCanonicalPlayerId(currentPlayerId)
        require(idToken.length in 1..MaximumGoogleIdTokenCharacters)
        require(nonce.matches(GoogleNoncePattern))
        val verifier = googleIdentityVerifier ?: throw GoogleIdentityUnavailableException()
        val verified = verifier.verify(idToken, nonce) ?: throw GoogleIdentityRejectedException()
        val now = clock.instant()
        if (!identities.consumeGoogleChallenge(currentPlayerId, sha256(nonce), now)) {
            throw GoogleIdentityRejectedException()
        }
        val player = identities.resolveOrLinkGoogleIdentity(
            currentPlayerId = currentPlayerId,
            providerSubject = verified.subject,
            displayName = verified.displayName,
            now = now,
        )
        val credentials = issueCredentials(player.playerId)
        logger.info(
            "GuestIdentity",
            "Google identity authenticated",
            mapOf(
                "playerId" to player.playerId,
                "restoredExistingPlayer" to player.restoredExistingPlayer.toString(),
            ),
        )
        return GuestBootstrapResult(
            playerId = player.playerId,
            accountKind = "google",
            credentials = credentials,
        )
    }

    fun profile(playerId: String): PlayerProfile = identities.profile(playerId)

    fun updateProfile(playerId: String, command: ProfileUpdateCommand): ProfileUpdateResult {
        validateProfile(command)
        val updated = identities.updateProfile(playerId, command)
        if (updated == null) {
            val current = identities.profile(playerId)
            logger.info("GuestIdentity", "profile revision conflict", mapOf("playerId" to playerId))
            return ProfileUpdateResult.Conflict(command.expectedRevision, current)
        }
        logger.info("GuestIdentity", "profile updated", mapOf("playerId" to playerId))
        return ProfileUpdateResult.Applied(updated)
    }

    fun cloudSave(playerId: String): CloudSaveSnapshot = saves.read(playerId).toCloudSaveSnapshot()

    fun putCloudSave(playerId: String, command: CloudSavePutCommand): CloudSaveWriteResult {
        validateSave(command)
        val fingerprint = sha256("${command.expectedRevision}:${command.saveSchemaVersion}:${command.stateJson}")
        return try {
            val result = saves.writeIdempotent(
                playerId = playerId,
                commandId = command.commandId,
                expectedRevision = command.expectedRevision,
                payloadJson = command.stateJson,
                schemaVersion = command.saveSchemaVersion,
                fingerprint = fingerprint,
            )
            logger.info(
                "GuestIdentity",
                "cloud save stored",
                mapOf("playerId" to playerId, "replayed" to result.replayed.toString()),
            )
            CloudSaveWriteResult.Applied(result.snapshot.toCloudSaveSnapshot(), result.replayed)
        } catch (_: RevisionConflictException) {
            val current = cloudSave(playerId)
            logger.info("GuestIdentity", "cloud save revision conflict", mapOf("playerId" to playerId))
            CloudSaveWriteResult.Conflict(command.expectedRevision, current)
        }
    }

    private fun issueCredentials(playerId: String): RenewableCredentials {
        val refreshToken = newOpaqueToken()
        val now = clock.instant()
        val refreshExpiresAt = now.plus(policy.refreshTtl)
        identities.createRefreshFamily(
            familyId = UUID.randomUUID().toString(),
            playerId = playerId,
            tokenHash = sha256(refreshToken),
            refreshExpiresAt = refreshExpiresAt,
        )
        return credentialsFor(playerId, refreshToken, refreshExpiresAt)
    }

    private fun credentialsFor(playerId: String, refreshToken: String, refreshExpiresAt: Instant): RenewableCredentials {
        val now = clock.instant()
        val accessExpiresAt = now.plus(policy.accessTtl)
        return RenewableCredentials(
            accessToken = accessTokenIssuer.issue(playerId, now, accessExpiresAt),
            refreshToken = refreshToken,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
        )
    }

    private fun newOpaqueToken(): String = ByteArray(48).also(random::nextBytes).let { bytes ->
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun newNonce(): String = ByteArray(32).also(random::nextBytes).let { bytes ->
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun validateBootstrap(command: GuestBootstrapCommand) {
        require(command.installationId.length in 1..128)
        validateOptional(command.appVersion, 64)
        validateOptional(command.locale, 16, minimum = 2)
        validateOptional(command.regionHint, 16, minimum = 2)
    }

    private fun validateProfile(command: ProfileUpdateCommand) {
        require(command.expectedRevision >= 0)
        validateOptional(command.locale, 16, minimum = 2)
        validateOptional(command.regionHint, 16, minimum = 2)
    }

    private fun validateSave(command: CloudSavePutCommand) {
        UUID.fromString(command.commandId)
        require(command.expectedRevision >= 0)
        require(command.saveSchemaVersion > 0)
        require(command.stateJson.isNotBlank())
    }

    private fun validateOptional(value: String?, maximum: Int, minimum: Int = 1) {
        if (value != null) require(value.length in minimum..maximum)
    }

    private fun requireCanonicalPlayerId(playerId: String) {
        require(runCatching { UUID.fromString(playerId).toString() == playerId }.getOrDefault(false)) {
            "playerId must be a canonical UUID"
        }
    }

    private companion object {
        val GoogleChallengeTtl: Duration = Duration.ofMinutes(5)
        const val MaximumGoogleIdTokenCharacters = 8_192
        val GoogleNoncePattern = Regex("[A-Za-z0-9_-]{32,128}")
    }
}

class JdbcGuestIdentityRepository(private val dataSource: DataSource) {
    fun findOrCreateGuest(
        installationHash: String,
        generatedPlayerId: String,
        platform: GuestPlatform,
        appVersion: String?,
        locale: String?,
        regionHint: String?,
    ): StoredGuestIdentity = dataSource.transaction { connection ->
        findPlayerId(connection, installationHash)?.let { playerId ->
            connection.prepareStatement(
                "UPDATE guest_installations SET last_seen_at = CURRENT_TIMESTAMP, app_version = ? WHERE installation_hash = ?",
            ).use { statement ->
                statement.setString(1, appVersion)
                statement.setString(2, installationHash)
                statement.executeUpdate()
            }
            return@transaction StoredGuestIdentity(playerId)
        }
        connection.prepareStatement("INSERT INTO players(id, display_name) VALUES (?, ?)").use { statement ->
            statement.setString(1, generatedPlayerId)
            statement.setString(2, "Guest ${generatedPlayerId.take(8)}")
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO save_heads(player_id, latest_revision) VALUES (?, 0)").use { statement ->
            statement.setString(1, generatedPlayerId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO player_profiles(player_id, revision, locale, region_hint) VALUES (?, 0, ?, ?)",
        ).use { statement ->
            statement.setString(1, generatedPlayerId)
            statement.setString(2, locale)
            statement.setString(3, regionHint)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO guest_installations(installation_hash, player_id, platform, app_version) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, installationHash)
            statement.setString(2, generatedPlayerId)
            statement.setString(3, platform.name.lowercase())
            statement.setString(4, appVersion)
            statement.executeUpdate()
        }
        StoredGuestIdentity(generatedPlayerId)
    }

    fun createRefreshFamily(familyId: String, playerId: String, tokenHash: String, refreshExpiresAt: Instant) =
        dataSource.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO refresh_token_families(id, player_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, familyId)
                statement.setString(2, playerId)
                statement.setInstant(3, refreshExpiresAt)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO refresh_tokens(token_hash, family_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setString(2, familyId)
                statement.setInstant(3, refreshExpiresAt)
                statement.executeUpdate()
            }
        }

    fun rotateRefreshToken(
        presentedTokenHash: String,
        replacementTokenHash: String,
        now: Instant,
    ): RefreshRotation? = dataSource.transaction { connection ->
        val token = tokenRecord(connection, presentedTokenHash) ?: return@transaction null
        if (token.revokedAt != null || token.familyExpiresAt <= now || token.tokenExpiresAt <= now) {
            revokeFamily(connection, token.familyId, now)
            return@transaction null
        }
        val consumed = connection.prepareStatement(
            "UPDATE refresh_tokens SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL",
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, presentedTokenHash)
            statement.executeUpdate()
        }
        if (consumed != 1) {
            revokeFamily(connection, token.familyId, now)
            return@transaction null
        }
        connection.prepareStatement(
            "INSERT INTO refresh_tokens(token_hash, family_id, expires_at) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, replacementTokenHash)
            statement.setString(2, token.familyId)
            statement.setInstant(3, token.familyExpiresAt)
            statement.executeUpdate()
        }
        RefreshRotation(token.playerId, token.familyExpiresAt)
    }

    fun createGoogleChallenge(
        playerId: String,
        nonceHash: String,
        expiresAt: Instant,
    ) = dataSource.transaction { connection ->
        connection.prepareStatement(
            """
            INSERT INTO google_auth_challenges(nonce_hash, player_id, expires_at)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, nonceHash)
            statement.setString(2, playerId)
            statement.setInstant(3, expiresAt)
            statement.executeUpdate()
        }
    }

    fun consumeGoogleChallenge(
        playerId: String,
        nonceHash: String,
        now: Instant,
    ): Boolean = dataSource.transaction { connection ->
        connection.prepareStatement(
            """
            UPDATE google_auth_challenges
            SET consumed_at = ?
            WHERE nonce_hash = ? AND player_id = ? AND consumed_at IS NULL AND expires_at > ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, nonceHash)
            statement.setString(3, playerId)
            statement.setInstant(4, now)
            statement.executeUpdate() == 1
        }
    }

    fun resolveOrLinkGoogleIdentity(
        currentPlayerId: String,
        providerSubject: String,
        displayName: String?,
        now: Instant,
    ): StoredGoogleIdentity = dataSource.transaction { connection ->
        findGooglePlayerId(connection, providerSubject)?.let { linkedPlayerId ->
            touchGoogleIdentity(connection, providerSubject, now)
            connection.prepareStatement(
                "UPDATE players SET account_kind = 'google' WHERE id = ?",
            ).use { statement ->
                statement.setString(1, linkedPlayerId)
                statement.executeUpdate()
            }
            return@transaction StoredGoogleIdentity(
                playerId = linkedPlayerId,
                restoredExistingPlayer = linkedPlayerId != currentPlayerId,
            )
        }

        val existingSubject = findGoogleSubjectForPlayer(connection, currentPlayerId)
        if (existingSubject != null && existingSubject != providerSubject) {
            throw GoogleIdentityConflictException()
        }
        val playerExists = connection.prepareStatement(
            "SELECT 1 FROM players WHERE id = ?",
        ).use { statement ->
            statement.setString(1, currentPlayerId)
            statement.executeQuery().use { it.next() }
        }
        check(playerExists) { "Unknown player" }
        connection.prepareStatement(
            """
            INSERT INTO player_identities(provider, provider_subject, player_id, last_seen_at)
            VALUES ('google', ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, providerSubject)
            statement.setString(2, currentPlayerId)
            statement.setInstant(3, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE players
            SET account_kind = 'google',
                display_name = CASE WHEN ? IS NULL THEN display_name ELSE ? END
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, displayName)
            statement.setString(2, displayName)
            statement.setString(3, currentPlayerId)
            statement.executeUpdate()
        }
        StoredGoogleIdentity(playerId = currentPlayerId, restoredExistingPlayer = false)
    }

    fun profile(playerId: String): PlayerProfile = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT players.display_name, profiles.locale, profiles.region_hint, profiles.revision, profiles.updated_at
            FROM players JOIN player_profiles profiles ON profiles.player_id = players.id
            WHERE players.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Unknown player" }
                resultSet.toProfile(playerId)
            }
        }
    }

    fun updateProfile(playerId: String, command: ProfileUpdateCommand): PlayerProfile? = dataSource.transaction { connection ->
        val changed = connection.prepareStatement(
            """
            UPDATE player_profiles
            SET locale = ?, region_hint = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE player_id = ? AND revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.locale)
            statement.setString(2, command.regionHint)
            statement.setString(3, playerId)
            statement.setLong(4, command.expectedRevision)
            statement.executeUpdate()
        }
        if (changed != 1) return@transaction null
        profile(connection, playerId)
    }

    private fun findPlayerId(connection: Connection, installationHash: String): String? = connection.prepareStatement(
        "SELECT player_id FROM guest_installations WHERE installation_hash = ?",
    ).use { statement ->
        statement.setString(1, installationHash)
        statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString("player_id") else null }
    }

    private fun findGooglePlayerId(connection: Connection, providerSubject: String): String? =
        connection.prepareStatement(
            "SELECT player_id FROM player_identities WHERE provider = 'google' AND provider_subject = ?",
        ).use { statement ->
            statement.setString(1, providerSubject)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString("player_id") else null
            }
        }

    private fun findGoogleSubjectForPlayer(connection: Connection, playerId: String): String? =
        connection.prepareStatement(
            "SELECT provider_subject FROM player_identities WHERE provider = 'google' AND player_id = ?",
        ).use { statement ->
            statement.setString(1, playerId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString("provider_subject") else null
            }
        }

    private fun touchGoogleIdentity(connection: Connection, providerSubject: String, now: Instant) {
        connection.prepareStatement(
            """
            UPDATE player_identities
            SET last_seen_at = ?
            WHERE provider = 'google' AND provider_subject = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, providerSubject)
            statement.executeUpdate()
        }
    }

    private fun tokenRecord(connection: Connection, tokenHash: String): RefreshTokenRecord? = connection.prepareStatement(
        """
        SELECT families.id, families.player_id, families.expires_at AS family_expires_at, families.revoked_at,
               tokens.expires_at AS token_expires_at
        FROM refresh_tokens tokens
        JOIN refresh_token_families families ON families.id = tokens.family_id
        WHERE tokens.token_hash = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, tokenHash)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            RefreshTokenRecord(
                familyId = resultSet.getString("id"),
                playerId = resultSet.getString("player_id"),
                familyExpiresAt = resultSet.instant("family_expires_at"),
                tokenExpiresAt = resultSet.instant("token_expires_at"),
                revokedAt = resultSet.getObject("revoked_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            )
        }
    }

    private fun revokeFamily(connection: Connection, familyId: String, now: Instant) {
        connection.prepareStatement(
            "UPDATE refresh_token_families SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, familyId)
            statement.executeUpdate()
        }
    }

    private fun profile(connection: Connection, playerId: String): PlayerProfile = connection.prepareStatement(
        """
        SELECT players.display_name, profiles.locale, profiles.region_hint, profiles.revision, profiles.updated_at
        FROM players JOIN player_profiles profiles ON profiles.player_id = players.id
        WHERE players.id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, playerId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Unknown player" }
            resultSet.toProfile(playerId)
        }
    }

    private fun java.sql.ResultSet.toProfile(playerId: String) = PlayerProfile(
        playerId = playerId,
        displayName = getString("display_name"),
        locale = getString("locale"),
        regionHint = getString("region_hint"),
        revision = getLong("revision"),
        updatedAt = instant("updated_at"),
    )

    private fun java.sql.ResultSet.instant(column: String): Instant =
        getObject(column, java.time.OffsetDateTime::class.java).toInstant()

    private data class RefreshTokenRecord(
        val familyId: String,
        val playerId: String,
        val familyExpiresAt: Instant,
        val tokenExpiresAt: Instant,
        val revokedAt: Instant?,
    )
}

data class StoredGuestIdentity(val playerId: String)
data class StoredGoogleIdentity(val playerId: String, val restoredExistingPlayer: Boolean)
data class RefreshRotation(val playerId: String, val refreshExpiresAt: Instant)

private fun StoredSaveSnapshot.toCloudSaveSnapshot() = CloudSaveSnapshot(
    saveSchemaVersion = schemaVersion,
    revision = revision,
    stateJson = payloadJson,
    updatedAt = updatedAt,
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC))
}
