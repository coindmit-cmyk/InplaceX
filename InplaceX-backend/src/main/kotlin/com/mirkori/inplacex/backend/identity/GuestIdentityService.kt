package com.mirkori.inplacex.backend.identity

import com.mirkori.inplacex.auth.AuthProvider
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
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    fun bootstrap(command: GuestBootstrapCommand, idempotencyKey: String): GuestBootstrapResult {
        validateBootstrap(command)
        require(idempotencyKey.matches(IdempotencyKeyPattern))
        val now = clock.instant()
        val refreshToken = newOpaqueToken()
        val refreshExpiresAt = now.plus(policy.refreshTtl)
        val installationHash = sha256(command.installationId)
        val result = identities.bootstrapGuestIdempotent(
            idempotencyKey = idempotencyKey,
            requestFingerprint = bootstrapFingerprint(command),
            installationHash = installationHash,
            generatedPlayerId = UUID.randomUUID().toString(),
            platform = command.platform,
            appVersion = command.appVersion,
            locale = command.locale,
            regionHint = command.regionHint,
            familyId = UUID.randomUUID().toString(),
            tokenHash = sha256(refreshToken),
            refreshExpiresAt = refreshExpiresAt,
            now = now,
            createResult = { player ->
                val credentials = credentialsFor(player.playerId, refreshToken, refreshExpiresAt)
                val bootstrap = GuestBootstrapResult(player.playerId, accountKind = "guest", credentials)
                SerializedIdentityResult(bootstrap, encodeBootstrapResult(bootstrap))
            },
            restoreResult = ::decodeBootstrapResult,
        )
        logger.info(
            "GuestIdentity",
            "guest bootstrap completed",
            mapOf("replayed" to result.replayed.toString()),
        )
        return result.value
    }

    fun refresh(refreshToken: String, idempotencyKey: String): RenewableCredentials {
        require(refreshToken.isNotBlank())
        require(idempotencyKey.matches(IdempotencyKeyPattern))
        val now = clock.instant()
        val nextRefreshToken = newOpaqueToken()
        val presentedTokenHash = sha256(refreshToken)
        val result = identities.rotateRefreshTokenIdempotent(
            presentedTokenHash = presentedTokenHash,
            replacementTokenHash = sha256(nextRefreshToken),
            idempotencyKey = idempotencyKey,
            requestFingerprint = presentedTokenHash,
            now = now,
            createResult = { rotation ->
                val credentials = credentialsFor(
                    rotation.playerId,
                    nextRefreshToken,
                    rotation.refreshExpiresAt,
                )
                SerializedIdentityResult(credentials, encodeCredentials(credentials))
            },
            restoreResult = ::decodeCredentials,
        ) ?: run {
            logger.warn("GuestIdentity", "refresh token rejected", mapOf("outcome" to "unauthorized"))
            throw RefreshTokenRejectedException()
        }
        logger.info(
            "GuestIdentity",
            "refresh token rotated",
            mapOf("replayed" to result.replayed.toString()),
        )
        return result.value
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
            accountKind = AuthProvider.GOOGLE.wireName,
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

    private fun encodeCredentials(credentials: RenewableCredentials): String = buildJsonObject {
        put("accessToken", credentials.accessToken)
        put("refreshToken", credentials.refreshToken)
        put("accessExpiresAt", credentials.accessExpiresAt.toString())
        put("refreshExpiresAt", credentials.refreshExpiresAt.toString())
    }.toString()

    private fun encodeBootstrapResult(result: GuestBootstrapResult): String = buildJsonObject {
        put("playerId", result.playerId)
        put("accountKind", result.accountKind)
        put("credentials", Json.parseToJsonElement(encodeCredentials(result.credentials)))
    }.toString()

    private fun decodeBootstrapResult(source: String): GuestBootstrapResult {
        val value = Json.parseToJsonElement(source).jsonObject
        check(value.keys == BootstrapResultFields) { "Invalid stored bootstrap idempotency result" }
        return GuestBootstrapResult(
            playerId = value.getValue("playerId").jsonPrimitive.content,
            accountKind = value.getValue("accountKind").jsonPrimitive.content,
            credentials = decodeCredentials(value.getValue("credentials").toString()),
        )
    }

    private fun decodeCredentials(source: String): RenewableCredentials {
        val value = Json.parseToJsonElement(source).jsonObject
        check(value.keys == CredentialResultFields) { "Invalid stored refresh idempotency result" }
        return RenewableCredentials(
            accessToken = value.getValue("accessToken").jsonPrimitive.content,
            refreshToken = value.getValue("refreshToken").jsonPrimitive.content,
            accessExpiresAt = Instant.parse(value.getValue("accessExpiresAt").jsonPrimitive.content),
            refreshExpiresAt = Instant.parse(value.getValue("refreshExpiresAt").jsonPrimitive.content),
        )
    }

    private fun validateBootstrap(command: GuestBootstrapCommand) {
        require(command.installationId.length in 1..128)
        validateOptional(command.appVersion, 64)
        validateOptional(command.locale, 16, minimum = 2)
        validateOptional(command.regionHint, 16, minimum = 2)
    }

    private fun bootstrapFingerprint(command: GuestBootstrapCommand): String = sha256(
        listOf(
            command.installationId,
            command.platform.name,
            command.appVersion,
            command.locale,
            command.regionHint,
        ).joinToString(separator = "|") { value ->
            if (value == null) "-1:" else "${value.length}:$value"
        },
    )

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
        val IdempotencyKeyPattern = Regex("[A-Za-z0-9._~-]{1,128}")
        val CredentialResultFields = setOf(
            "accessToken",
            "refreshToken",
            "accessExpiresAt",
            "refreshExpiresAt",
        )
        val BootstrapResultFields = setOf("playerId", "accountKind", "credentials")
    }
}

class JdbcGuestIdentityRepository(private val dataSource: DataSource) {
    fun <T> bootstrapGuestIdempotent(
        idempotencyKey: String,
        requestFingerprint: String,
        installationHash: String,
        generatedPlayerId: String,
        platform: GuestPlatform,
        appVersion: String?,
        locale: String?,
        regionHint: String?,
        familyId: String,
        tokenHash: String,
        refreshExpiresAt: Instant,
        now: Instant,
        createResult: (StoredGuestIdentity) -> SerializedIdentityResult<T>,
        restoreResult: (String) -> T,
    ): IdempotentIdentityResult<T> {
        repeat(MaxBootstrapAttempts) {
            try {
                return dataSource.transaction { connection ->
                    existingAuthResult(
                        connection = connection,
                        operation = BootstrapOperation,
                        actorKey = installationHash,
                        idempotencyKey = idempotencyKey,
                        now = now,
                    )?.let { existing ->
                        return@transaction restoreIdentityResult(
                            existing = existing,
                            requestFingerprint = requestFingerprint,
                            restoreResult = restoreResult,
                        )
                    }
                    insertBootstrapReservation(
                        connection = connection,
                        installationHash = installationHash,
                        idempotencyKey = idempotencyKey,
                        requestFingerprint = requestFingerprint,
                        expiresAt = refreshExpiresAt,
                    )
                    val player = findOrCreateGuest(
                        connection = connection,
                        installationHash = installationHash,
                        generatedPlayerId = generatedPlayerId,
                        platform = platform,
                        appVersion = appVersion,
                        locale = locale,
                        regionHint = regionHint,
                    )
                    createRefreshFamily(
                        connection = connection,
                        familyId = familyId,
                        playerId = player.playerId,
                        tokenHash = tokenHash,
                        refreshExpiresAt = refreshExpiresAt,
                    )
                    val created = createResult(player)
                    completeBootstrapReservation(
                        connection,
                        installationHash,
                        idempotencyKey,
                        created.responseJson,
                    )
                    IdempotentIdentityResult(created.value, replayed = false)
                }
            } catch (error: SQLException) {
                if (!error.isConstraintViolation()) throw error
                replayAuthResult(
                    operation = BootstrapOperation,
                    actorKey = installationHash,
                    idempotencyKey = idempotencyKey,
                    requestFingerprint = requestFingerprint,
                    now = now,
                    restoreResult = restoreResult,
                )?.let { return it }
            }
        }
        error("Unable to serialize guest bootstrap")
    }

    private fun findOrCreateGuest(
        connection: Connection,
        installationHash: String,
        generatedPlayerId: String,
        platform: GuestPlatform,
        appVersion: String?,
        locale: String?,
        regionHint: String?,
    ): StoredGuestIdentity {
        findPlayerId(connection, installationHash)?.let { playerId ->
            connection.prepareStatement(
                "UPDATE guest_installations SET last_seen_at = CURRENT_TIMESTAMP, app_version = ? WHERE installation_hash = ?",
            ).use { statement ->
                statement.setString(1, appVersion)
                statement.setString(2, installationHash)
                statement.executeUpdate()
            }
            return StoredGuestIdentity(playerId)
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
        return StoredGuestIdentity(generatedPlayerId)
    }

    fun createRefreshFamily(familyId: String, playerId: String, tokenHash: String, refreshExpiresAt: Instant) =
        dataSource.transaction { connection ->
            createRefreshFamily(connection, familyId, playerId, tokenHash, refreshExpiresAt)
        }

    private fun createRefreshFamily(
        connection: Connection,
        familyId: String,
        playerId: String,
        tokenHash: String,
        refreshExpiresAt: Instant,
    ) {
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

    fun <T> rotateRefreshTokenIdempotent(
        presentedTokenHash: String,
        replacementTokenHash: String,
        idempotencyKey: String,
        requestFingerprint: String,
        now: Instant,
        createResult: (RefreshRotation) -> SerializedIdentityResult<T>,
        restoreResult: (String) -> T,
    ): IdempotentIdentityResult<T>? = dataSource.transaction { connection ->
        val token = tokenRecord(connection, presentedTokenHash, lock = true) ?: return@transaction null
        existingAuthResult(
            connection = connection,
            operation = RefreshOperation,
            actorKey = token.playerId,
            idempotencyKey = idempotencyKey,
            now = now,
        )?.let { existing ->
            if (existing.requestFingerprint != requestFingerprint) {
                throw IdempotencyKeyReusedException()
            }
            return@transaction IdempotentIdentityResult(
                value = restoreResult(existing.responseJson),
                replayed = true,
            )
        }
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
        val created = createResult(RefreshRotation(token.playerId, token.familyExpiresAt))
        connection.prepareStatement(
            """
            INSERT INTO auth_idempotency_results(
                operation, actor_key, idempotency_key, request_fingerprint,
                state, response_json, expires_at
            ) VALUES (?, ?, ?, ?, 'completed', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, RefreshOperation)
            statement.setString(2, token.playerId)
            statement.setString(3, idempotencyKey)
            statement.setString(4, requestFingerprint)
            statement.setString(5, created.responseJson)
            statement.setInstant(6, token.familyExpiresAt)
            statement.executeUpdate()
        }
        IdempotentIdentityResult(created.value, replayed = false)
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
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, AuthProvider.GOOGLE.wireName)
            statement.setString(2, providerSubject)
            statement.setString(3, currentPlayerId)
            statement.setInstant(4, now)
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
            "SELECT player_id FROM player_identities WHERE provider = ? AND provider_subject = ?",
        ).use { statement ->
            statement.setString(1, AuthProvider.GOOGLE.wireName)
            statement.setString(2, providerSubject)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString("player_id") else null
            }
        }

    private fun findGoogleSubjectForPlayer(connection: Connection, playerId: String): String? =
        connection.prepareStatement(
            "SELECT provider_subject FROM player_identities WHERE provider = ? AND player_id = ?",
        ).use { statement ->
            statement.setString(1, AuthProvider.GOOGLE.wireName)
            statement.setString(2, playerId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString("provider_subject") else null
            }
        }

    private fun touchGoogleIdentity(connection: Connection, providerSubject: String, now: Instant) {
        connection.prepareStatement(
            """
            UPDATE player_identities
            SET last_seen_at = ?
            WHERE provider = ? AND provider_subject = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, AuthProvider.GOOGLE.wireName)
            statement.setString(3, providerSubject)
            statement.executeUpdate()
        }
    }

    private fun tokenRecord(
        connection: Connection,
        tokenHash: String,
        lock: Boolean = false,
    ): RefreshTokenRecord? {
        val lockClause = if (lock) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
        SELECT families.id, families.player_id, families.expires_at AS family_expires_at, families.revoked_at,
               tokens.expires_at AS token_expires_at
        FROM refresh_tokens tokens
        JOIN refresh_token_families families ON families.id = tokens.family_id
        WHERE tokens.token_hash = ?
        $lockClause
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
    }

    private fun existingAuthResult(
        connection: Connection,
        operation: String,
        actorKey: String,
        idempotencyKey: String,
        now: Instant,
    ): StoredAuthIdempotencyResult? = connection.prepareStatement(
        """
        SELECT request_fingerprint, response_json
        FROM auth_idempotency_results
        WHERE operation = ? AND actor_key = ? AND idempotency_key = ?
          AND state = 'completed' AND expires_at > ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, operation)
        statement.setString(2, actorKey)
        statement.setString(3, idempotencyKey)
        statement.setInstant(4, now)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            StoredAuthIdempotencyResult(
                requestFingerprint = resultSet.getString("request_fingerprint"),
                responseJson = resultSet.getString("response_json"),
            )
        }
    }

    private fun insertBootstrapReservation(
        connection: Connection,
        installationHash: String,
        idempotencyKey: String,
        requestFingerprint: String,
        expiresAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO auth_idempotency_results(
                operation, actor_key, idempotency_key, request_fingerprint,
                state, response_json, expires_at
            ) VALUES (?, ?, ?, ?, 'completed', '{}', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, BootstrapOperation)
            statement.setString(2, installationHash)
            statement.setString(3, idempotencyKey)
            statement.setString(4, requestFingerprint)
            statement.setInstant(5, expiresAt)
            statement.executeUpdate()
        }
    }

    private fun completeBootstrapReservation(
        connection: Connection,
        installationHash: String,
        idempotencyKey: String,
        responseJson: String,
    ) {
        val changed = connection.prepareStatement(
            """
            UPDATE auth_idempotency_results
            SET response_json = ?
            WHERE operation = ? AND actor_key = ? AND idempotency_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, responseJson)
            statement.setString(2, BootstrapOperation)
            statement.setString(3, installationHash)
            statement.setString(4, idempotencyKey)
            statement.executeUpdate()
        }
        check(changed == 1) { "Missing bootstrap idempotency reservation" }
    }

    private fun <T> replayAuthResult(
        operation: String,
        actorKey: String,
        idempotencyKey: String,
        requestFingerprint: String,
        now: Instant,
        restoreResult: (String) -> T,
    ): IdempotentIdentityResult<T>? = dataSource.transaction { connection ->
        existingAuthResult(connection, operation, actorKey, idempotencyKey, now)?.let { existing ->
            restoreIdentityResult(existing, requestFingerprint, restoreResult)
        }
    }

    private fun <T> restoreIdentityResult(
        existing: StoredAuthIdempotencyResult,
        requestFingerprint: String,
        restoreResult: (String) -> T,
    ): IdempotentIdentityResult<T> {
        if (existing.requestFingerprint != requestFingerprint) {
            throw IdempotencyKeyReusedException()
        }
        return IdempotentIdentityResult(restoreResult(existing.responseJson), replayed = true)
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

    private data class StoredAuthIdempotencyResult(
        val requestFingerprint: String,
        val responseJson: String,
    )

    private companion object {
        const val BootstrapOperation = "bootstrap"
        const val RefreshOperation = "refresh"
        const val MaxBootstrapAttempts = 3
    }
}

data class StoredGuestIdentity(val playerId: String)
data class StoredGoogleIdentity(val playerId: String, val restoredExistingPlayer: Boolean)
data class RefreshRotation(val playerId: String, val refreshExpiresAt: Instant)

data class SerializedIdentityResult<T>(
    val value: T,
    val responseJson: String,
)

data class IdempotentIdentityResult<T>(
    val value: T,
    val replayed: Boolean,
)

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

private fun SQLException.isConstraintViolation(): Boolean = sqlState?.startsWith("23") == true
