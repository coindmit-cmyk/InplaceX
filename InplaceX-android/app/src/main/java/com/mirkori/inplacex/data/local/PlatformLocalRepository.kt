package com.mirkori.inplacex.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.Locale
import java.util.UUID

enum class LocalAuthProvider {
    GUEST,
    GOOGLE_PLAY,
    FACEBOOK,
    APPLE,
    EMAIL,
}

enum class LocalRelationshipType {
    FRIEND,
    BLOCKED,
    PARTY_MEMBER,
    INVITE_OUTGOING,
    INVITE_INCOMING,
}

enum class LocalRelationshipStatus {
    PENDING,
    ACTIVE,
    MUTED,
    BLOCKED,
    REMOVED,
}

enum class LocalRoomVisibility {
    PRIVATE,
    FRIENDS_ONLY,
    PUBLIC,
}

enum class LocalRoomStatus {
    DRAFT,
    WAITING,
    READY,
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}

enum class LocalRoomMemberRole {
    HOST,
    MEMBER,
    SPECTATOR,
    BOT,
}

enum class LocalRoomMemberStatus {
    JOINED,
    READY,
    LEFT,
    DISCONNECTED,
    BANNED,
}

enum class LocalMatchStatus {
    CREATED,
    ACTIVE,
    FINISHED,
    CANCELLED,
}

enum class SyncOperationType {
    UPSERT_PROFILE,
    PULL_PROGRESS,
    PUSH_PROGRESS,
    UPSERT_ROOM,
    JOIN_ROOM,
    LEAVE_ROOM,
    UPSERT_MATCH,
    SUBMIT_TURN,
}

enum class SyncOperationStatus {
    PENDING,
    IN_FLIGHT,
    COMPLETED,
    FAILED,
}

data class LocalPlayerProfile(
    val profileId: Int = GameProgressDbHelper.PROFILE_ID,
    val playerId: String,
    val installationId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val authProvider: LocalAuthProvider = LocalAuthProvider.GUEST,
    val isGuest: Boolean = true,
    val isOnline: Boolean = false,
    val locale: String = Locale.getDefault().toLanguageTag(),
    val regionCode: String = Locale.getDefault().country,
    val cloudRevision: Long = 0,
    val lastSeenAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class LocalIdentityLink(
    val id: String = UUID.randomUUID().toString(),
    val provider: LocalAuthProvider,
    val providerSubject: String,
    val playerId: String,
    val displayName: String? = null,
    val email: String? = null,
    val isPrimary: Boolean = false,
    val linkedAt: Long = 0,
    val lastRefreshedAt: Long = 0,
)

data class LocalSocialRelationship(
    val id: String = UUID.randomUUID().toString(),
    val playerId: String,
    val targetPlayerId: String,
    val targetDisplayName: String,
    val relationshipType: LocalRelationshipType,
    val status: LocalRelationshipStatus,
    val source: String = "local",
    val note: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class LocalOnlineRoom(
    val roomId: String = UUID.randomUUID().toString(),
    val gameSlug: String = DEFAULT_GAME_SLUG,
    val roomName: String,
    val inviteCode: String? = null,
    val visibility: LocalRoomVisibility = LocalRoomVisibility.PRIVATE,
    val hostPlayerId: String,
    val status: LocalRoomStatus = LocalRoomStatus.WAITING,
    val maxMembers: Int = 2,
    val configJson: String = "{}",
    val serverRevision: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class LocalRoomMember(
    val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val playerId: String,
    val displayName: String,
    val role: LocalRoomMemberRole = LocalRoomMemberRole.MEMBER,
    val status: LocalRoomMemberStatus = LocalRoomMemberStatus.JOINED,
    val seatNo: Int? = null,
    val joinedAt: Long = 0,
    val updatedAt: Long = 0,
)

data class LocalMatchRecord(
    val matchId: String = UUID.randomUUID().toString(),
    val roomId: String? = null,
    val gameSlug: String = DEFAULT_GAME_SLUG,
    val localPlayerId: String,
    val opponentPlayerId: String? = null,
    val status: LocalMatchStatus = LocalMatchStatus.CREATED,
    val mode: String,
    val codeLength: Int,
    val allowDuplicates: Boolean,
    val attemptLimit: Int,
    val turnTimeLimitSec: Int? = null,
    val playerSecretHash: String? = null,
    val opponentSecretHash: String? = null,
    val localResult: String? = null,
    val remoteResult: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val updatedAt: Long = 0,
)

data class LocalMatchTurn(
    val id: String = UUID.randomUUID().toString(),
    val matchId: String,
    val playerId: String,
    val turnIndex: Int,
    val guess: String,
    val score: Int,
    val serverAcknowledged: Boolean = false,
    val createdAt: Long = 0,
)

data class PendingSyncOperation(
    val id: String = UUID.randomUUID().toString(),
    val scope: String,
    val entityId: String? = null,
    val operationType: SyncOperationType,
    val payloadJson: String,
    val endpointPath: String,
    val method: String,
    val idempotencyKey: String? = null,
    val status: SyncOperationStatus = SyncOperationStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class LocalPlatformSnapshot(
    val playerProfile: LocalPlayerProfile,
    val gameProgress: GameProgressState,
    val campaignProgress: List<CampaignLevelProgress>,
    val identityLinks: List<LocalIdentityLink>,
    val relationships: List<LocalSocialRelationship>,
    val rooms: List<LocalOnlineRoom>,
    val matches: List<LocalMatchRecord>,
    val pendingSyncOperations: List<PendingSyncOperation>,
)

class PlatformLocalRepository(
    context: Context,
    private val databaseConfig: LocalDatabaseConfig = LocalDatabaseConfig(),
) {
    private val helper = GameProgressDbHelper(context.applicationContext, databaseConfig)
    private val gameProgressRepository = GameProgressRepository(context.applicationContext, databaseConfig)

    fun loadPlatformSnapshot(
        gameSlug: String = DEFAULT_GAME_SLUG,
        campaignUpperBound: Int? = null,
    ): LocalPlatformSnapshot {
        val db = helper.writableDatabase
        val progress = gameProgressRepository.loadState()
        val profile = loadPlayerProfile(db, progress)
        val upperBound = campaignUpperBound ?: maxOf(40, progress.highestUnlockedCampaignLevel + 20)
        return LocalPlatformSnapshot(
            playerProfile = profile,
            gameProgress = progress,
            campaignProgress = gameProgressRepository.loadCampaignProgressRange(1, upperBound),
            identityLinks = loadIdentityLinks(db, profile.playerId),
            relationships = loadRelationships(db, profile.playerId),
            rooms = loadRooms(db, gameSlug),
            matches = loadMatches(db, profile.playerId, gameSlug),
            pendingSyncOperations = loadPendingSyncOperations(db),
        )
    }

    fun loadPlayerProfile(): LocalPlayerProfile {
        val db = helper.writableDatabase
        return loadPlayerProfile(db, gameProgressRepository.loadState())
    }

    fun upsertPlayerProfile(profile: LocalPlayerProfile): LocalPlayerProfile {
        val db = helper.writableDatabase
        val normalized = profile.normalizeTimestamps(databaseConfig.nowMs())
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_PLAYER_PROFILE,
            null,
            normalized.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return normalized
    }

    fun loadIdentityLinks(): List<LocalIdentityLink> {
        return loadIdentityLinks(helper.readableDatabase, loadPlayerProfile().playerId)
    }

    fun replaceIdentityLinks(playerId: String, links: List<LocalIdentityLink>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                GameProgressDbHelper.TABLE_IDENTITY_LINKS,
                "$COL_PLAYER_ID = ?",
                arrayOf(playerId),
            )
            links.forEach { link ->
                db.insertWithOnConflict(
                    GameProgressDbHelper.TABLE_IDENTITY_LINKS,
                    null,
                    link.normalizeTimestamps(databaseConfig.nowMs()).toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertRelationship(relationship: LocalSocialRelationship): LocalSocialRelationship {
        val db = helper.writableDatabase
        val normalized = relationship.normalizeTimestamps(databaseConfig.nowMs())
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_SOCIAL_RELATIONSHIPS,
            null,
            normalized.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return normalized
    }

    fun upsertRelationships(relationships: List<LocalSocialRelationship>): List<LocalSocialRelationship> {
        if (relationships.isEmpty()) return emptyList()
        val db = helper.writableDatabase
        val nowMs = databaseConfig.nowMs()
        val normalized = relationships.map { it.normalizeTimestamps(nowMs) }
        db.beginTransaction()
        try {
            normalized.forEach { relationship ->
                db.insertWithOnConflict(
                    GameProgressDbHelper.TABLE_SOCIAL_RELATIONSHIPS,
                    null,
                    relationship.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return normalized
    }

    fun replaceRelationships(
        playerId: String,
        relationshipType: LocalRelationshipType,
        relationships: List<LocalSocialRelationship>,
    ): List<LocalSocialRelationship> {
        require(relationships.all { relationship ->
            relationship.playerId == playerId && relationship.relationshipType == relationshipType
        })
        val db = helper.writableDatabase
        val nowMs = databaseConfig.nowMs()
        val normalized = relationships.map { it.normalizeTimestamps(nowMs) }
        db.beginTransaction()
        try {
            db.delete(
                GameProgressDbHelper.TABLE_SOCIAL_RELATIONSHIPS,
                "$COL_PLAYER_ID = ? AND $COL_RELATIONSHIP_TYPE = ?",
                arrayOf(playerId, relationshipType.name),
            )
            normalized.forEach { relationship ->
                db.insertWithOnConflict(
                    GameProgressDbHelper.TABLE_SOCIAL_RELATIONSHIPS,
                    null,
                    relationship.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return normalized
    }

    fun deleteRelationship(
        playerId: String,
        targetPlayerId: String,
        relationshipType: LocalRelationshipType,
    ): Boolean {
        val deleted = helper.writableDatabase.delete(
            GameProgressDbHelper.TABLE_SOCIAL_RELATIONSHIPS,
            "$COL_PLAYER_ID = ? AND $COL_TARGET_PLAYER_ID = ? AND $COL_RELATIONSHIP_TYPE = ?",
            arrayOf(playerId, targetPlayerId, relationshipType.name),
        )
        return deleted > 0
    }

    fun loadRelationships(status: LocalRelationshipStatus? = null): List<LocalSocialRelationship> {
        val db = helper.readableDatabase
        val profile = loadPlayerProfile()
        return loadRelationships(db, profile.playerId, status)
    }

    fun upsertRoom(room: LocalOnlineRoom): LocalOnlineRoom {
        val db = helper.writableDatabase
        val normalized = room.normalizeTimestamps(databaseConfig.nowMs())
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_ONLINE_ROOMS,
            null,
            normalized.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return normalized
    }

    fun replaceRoomMembers(roomId: String, members: List<LocalRoomMember>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                GameProgressDbHelper.TABLE_ONLINE_ROOM_MEMBERS,
                "$COL_ROOM_ID = ?",
                arrayOf(roomId),
            )
            members.forEach { member ->
                db.insertWithOnConflict(
                    GameProgressDbHelper.TABLE_ONLINE_ROOM_MEMBERS,
                    null,
                    member.normalizeTimestamps(databaseConfig.nowMs()).toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadRooms(status: LocalRoomStatus? = null): List<LocalOnlineRoom> {
        return loadRooms(helper.readableDatabase, DEFAULT_GAME_SLUG, status)
    }

    fun loadRoomMembers(roomId: String): List<LocalRoomMember> {
        val db = helper.readableDatabase
        val cursor = db.query(
            GameProgressDbHelper.TABLE_ONLINE_ROOM_MEMBERS,
            ROOM_MEMBER_COLUMNS,
            "$COL_ROOM_ID = ?",
            arrayOf(roomId),
            null,
            null,
            "$COL_SEAT_NO ASC, $COL_JOINED_AT ASC",
        )
        return cursor.useRows { memberFromCursor(it) }
    }

    fun upsertMatch(match: LocalMatchRecord): LocalMatchRecord {
        val db = helper.writableDatabase
        val normalized = match.normalizeTimestamps(databaseConfig.nowMs())
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_ONLINE_MATCHES,
            null,
            normalized.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return normalized
    }

    fun loadMatches(status: LocalMatchStatus? = null): List<LocalMatchRecord> {
        val db = helper.readableDatabase
        val profile = loadPlayerProfile()
        return loadMatches(db, profile.playerId, DEFAULT_GAME_SLUG, status)
    }

    fun recordMatchTurn(turn: LocalMatchTurn): LocalMatchTurn {
        val db = helper.writableDatabase
        val normalized = turn.normalizeTimestamps(databaseConfig.nowMs())
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_ONLINE_MATCH_TURNS,
            null,
            normalized.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return normalized
    }

    fun loadMatchTurns(matchId: String): List<LocalMatchTurn> {
        val db = helper.readableDatabase
        val cursor = db.query(
            GameProgressDbHelper.TABLE_ONLINE_MATCH_TURNS,
            MATCH_TURN_COLUMNS,
            "$COL_MATCH_ID = ?",
            arrayOf(matchId),
            null,
            null,
            "$COL_TURN_INDEX ASC",
        )
        return cursor.useRows { matchTurnFromCursor(it) }
    }

    fun enqueueSyncOperation(operation: PendingSyncOperation): PendingSyncOperation {
        val db = helper.writableDatabase
        val normalized = operation.normalizeTimestamps(databaseConfig.nowMs())
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_SYNC_QUEUE,
            null,
            normalized.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return normalized
    }

    fun loadPendingSyncOperations(status: SyncOperationStatus? = null): List<PendingSyncOperation> {
        val db = helper.readableDatabase
        return loadPendingSyncOperations(db, status)
    }

    fun updateSyncOperationStatus(
        operationId: String,
        status: SyncOperationStatus,
        lastError: String? = null,
        incrementRetryCount: Boolean = false,
    ) {
        val db = helper.writableDatabase
        val current = loadPendingSyncOperations(db).firstOrNull { it.id == operationId } ?: return
        val values = ContentValues().apply {
            put(COL_STATUS, status.name)
            put(COL_LAST_ERROR, lastError)
            put(COL_UPDATED_AT, databaseConfig.nowMs())
            if (incrementRetryCount) {
                put(COL_RETRY_COUNT, current.retryCount + 1)
            }
        }
        db.update(
            GameProgressDbHelper.TABLE_SYNC_QUEUE,
            values,
            "$COL_ID = ?",
            arrayOf(operationId),
        )
    }

    private fun loadPlayerProfile(
        db: SQLiteDatabase,
        progressState: GameProgressState,
    ): LocalPlayerProfile {
        val cursor = db.query(
            GameProgressDbHelper.TABLE_PLAYER_PROFILE,
            PLAYER_PROFILE_COLUMNS,
            "$COL_PROFILE_ID = ?",
            arrayOf(GameProgressDbHelper.PROFILE_ID.toString()),
            null,
            null,
            null,
        )
        cursor.use {
            if (it.moveToFirst()) {
                val stored = playerProfileFromCursor(it)
                return synchronizeProfile(db, stored, progressState)
            }
        }

        val created = LocalPlayerProfile(
            playerId = UUID.randomUUID().toString(),
            installationId = UUID.randomUUID().toString(),
            displayName = progressState.playerDisplayName,
            authProvider = if (progressState.googlePlaySignedIn) LocalAuthProvider.GOOGLE_PLAY else LocalAuthProvider.GUEST,
            isGuest = !progressState.googlePlaySignedIn,
            isOnline = false,
            lastSeenAt = databaseConfig.nowMs(),
            createdAt = databaseConfig.nowMs(),
            updatedAt = databaseConfig.nowMs(),
        )
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_PLAYER_PROFILE,
            null,
            created.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return created
    }

    private fun synchronizeProfile(
        db: SQLiteDatabase,
        stored: LocalPlayerProfile,
        progressState: GameProgressState,
    ): LocalPlayerProfile {
        val expectedProvider = if (progressState.googlePlaySignedIn) LocalAuthProvider.GOOGLE_PLAY else LocalAuthProvider.GUEST
        if (
            stored.displayName == progressState.playerDisplayName &&
            stored.authProvider == expectedProvider &&
            stored.isGuest == !progressState.googlePlaySignedIn
        ) {
            return stored
        }

        val updated = stored.copy(
            displayName = progressState.playerDisplayName,
            authProvider = expectedProvider,
            isGuest = !progressState.googlePlaySignedIn,
            updatedAt = databaseConfig.nowMs(),
        )
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_PLAYER_PROFILE,
            null,
            updated.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return updated
    }

    private fun loadIdentityLinks(
        db: SQLiteDatabase,
        playerId: String,
    ): List<LocalIdentityLink> {
        val cursor = db.query(
            GameProgressDbHelper.TABLE_IDENTITY_LINKS,
            IDENTITY_LINK_COLUMNS,
            "$COL_PLAYER_ID = ?",
            arrayOf(playerId),
            null,
            null,
            "$COL_IS_PRIMARY DESC, $COL_LINKED_AT ASC",
        )
        return cursor.useRows { identityLinkFromCursor(it) }
    }

    private fun loadRelationships(
        db: SQLiteDatabase,
        playerId: String,
        status: LocalRelationshipStatus? = null,
    ): List<LocalSocialRelationship> {
        val selection = buildString {
            append("$COL_PLAYER_ID = ?")
            if (status != null) append(" AND $COL_STATUS = ?")
        }
        val selectionArgs = if (status == null) {
            arrayOf(playerId)
        } else {
            arrayOf(playerId, status.name)
        }
        val cursor = db.query(
            GameProgressDbHelper.TABLE_SOCIAL_RELATIONSHIPS,
            RELATIONSHIP_COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_UPDATED_AT DESC",
        )
        return cursor.useRows { relationshipFromCursor(it) }
    }

    private fun loadRooms(
        db: SQLiteDatabase,
        gameSlug: String,
        status: LocalRoomStatus? = null,
    ): List<LocalOnlineRoom> {
        val selection = buildString {
            append("$COL_GAME_SLUG = ?")
            if (status != null) append(" AND $COL_STATUS = ?")
        }
        val selectionArgs = if (status == null) {
            arrayOf(gameSlug)
        } else {
            arrayOf(gameSlug, status.name)
        }
        val cursor = db.query(
            GameProgressDbHelper.TABLE_ONLINE_ROOMS,
            ROOM_COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_UPDATED_AT DESC",
        )
        return cursor.useRows { roomFromCursor(it) }
    }

    private fun loadMatches(
        db: SQLiteDatabase,
        playerId: String,
        gameSlug: String,
        status: LocalMatchStatus? = null,
    ): List<LocalMatchRecord> {
        val selection = buildString {
            append("$COL_LOCAL_PLAYER_ID = ? AND $COL_GAME_SLUG = ?")
            if (status != null) append(" AND $COL_STATUS = ?")
        }
        val selectionArgs = if (status == null) {
            arrayOf(playerId, gameSlug)
        } else {
            arrayOf(playerId, gameSlug, status.name)
        }
        val cursor = db.query(
            GameProgressDbHelper.TABLE_ONLINE_MATCHES,
            MATCH_COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_UPDATED_AT DESC",
        )
        return cursor.useRows { matchFromCursor(it) }
    }

    private fun loadPendingSyncOperations(
        db: SQLiteDatabase,
        status: SyncOperationStatus? = null,
    ): List<PendingSyncOperation> {
        val cursor = db.query(
            GameProgressDbHelper.TABLE_SYNC_QUEUE,
            SYNC_COLUMNS,
            if (status == null) null else "$COL_STATUS = ?",
            if (status == null) null else arrayOf(status.name),
            null,
            null,
            "$COL_CREATED_AT ASC",
        )
        return cursor.useRows { syncOperationFromCursor(it) }
    }
}

private fun LocalPlayerProfile.normalizeTimestamps(now: Long): LocalPlayerProfile {
    val created = if (createdAt == 0L) now else createdAt
    val updated = if (updatedAt == 0L) now else updatedAt
    val lastSeen = if (lastSeenAt == 0L) now else lastSeenAt
    return copy(createdAt = created, updatedAt = updated, lastSeenAt = lastSeen)
}

private fun LocalIdentityLink.normalizeTimestamps(now: Long): LocalIdentityLink {
    val linked = if (linkedAt == 0L) now else linkedAt
    val refreshed = if (lastRefreshedAt == 0L) now else lastRefreshedAt
    return copy(linkedAt = linked, lastRefreshedAt = refreshed)
}

private fun LocalSocialRelationship.normalizeTimestamps(now: Long): LocalSocialRelationship {
    val created = if (createdAt == 0L) now else createdAt
    val updated = if (updatedAt == 0L) now else updatedAt
    return copy(createdAt = created, updatedAt = updated)
}

private fun LocalOnlineRoom.normalizeTimestamps(now: Long): LocalOnlineRoom {
    val created = if (createdAt == 0L) now else createdAt
    val updated = if (updatedAt == 0L) now else updatedAt
    return copy(createdAt = created, updatedAt = updated)
}

private fun LocalRoomMember.normalizeTimestamps(now: Long): LocalRoomMember {
    val joined = if (joinedAt == 0L) now else joinedAt
    val updated = if (updatedAt == 0L) now else updatedAt
    return copy(joinedAt = joined, updatedAt = updated)
}

private fun LocalMatchRecord.normalizeTimestamps(now: Long): LocalMatchRecord {
    val updated = if (updatedAt == 0L) now else updatedAt
    val started = if (status == LocalMatchStatus.ACTIVE && startedAt == 0L) now else startedAt
    val finished = if (status == LocalMatchStatus.FINISHED && finishedAt == 0L) now else finishedAt
    return copy(startedAt = started, finishedAt = finished, updatedAt = updated)
}

private fun LocalMatchTurn.normalizeTimestamps(now: Long): LocalMatchTurn {
    return if (createdAt == 0L) copy(createdAt = now) else this
}

private fun PendingSyncOperation.normalizeTimestamps(now: Long): PendingSyncOperation {
    val created = if (createdAt == 0L) now else createdAt
    val updated = if (updatedAt == 0L) now else updatedAt
    return copy(createdAt = created, updatedAt = updated)
}

private fun LocalPlayerProfile.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_PROFILE_ID, profileId)
        put(COL_PLAYER_ID, playerId)
        put(COL_INSTALLATION_ID, installationId)
        put(COL_DISPLAY_NAME, displayName)
        put(COL_AVATAR_URL, avatarUrl)
        put(COL_AUTH_PROVIDER, authProvider.name)
        put(COL_IS_GUEST, isGuest.toDbInt())
        put(COL_IS_ONLINE, isOnline.toDbInt())
        put(COL_LOCALE, locale)
        put(COL_REGION_CODE, regionCode)
        put(COL_CLOUD_REVISION, cloudRevision)
        put(COL_LAST_SEEN_AT, lastSeenAt)
        put(COL_CREATED_AT, createdAt)
        put(COL_UPDATED_AT, updatedAt)
    }
}

private fun LocalIdentityLink.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_ID, id)
        put(COL_PROVIDER, provider.name)
        put(COL_PROVIDER_SUBJECT, providerSubject)
        put(COL_PLAYER_ID, playerId)
        put(COL_DISPLAY_NAME, displayName)
        put(COL_EMAIL, email)
        put(COL_IS_PRIMARY, isPrimary.toDbInt())
        put(COL_LINKED_AT, linkedAt)
        put(COL_LAST_REFRESHED_AT, lastRefreshedAt)
    }
}

private fun LocalSocialRelationship.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_ID, id)
        put(COL_PLAYER_ID, playerId)
        put(COL_TARGET_PLAYER_ID, targetPlayerId)
        put(COL_TARGET_DISPLAY_NAME, targetDisplayName)
        put(COL_RELATIONSHIP_TYPE, relationshipType.name)
        put(COL_STATUS, status.name)
        put(COL_SOURCE, source)
        put(COL_NOTE, note)
        put(COL_CREATED_AT, createdAt)
        put(COL_UPDATED_AT, updatedAt)
    }
}

private fun LocalOnlineRoom.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_ROOM_ID, roomId)
        put(COL_GAME_SLUG, gameSlug)
        put(COL_ROOM_NAME, roomName)
        put(COL_INVITE_CODE, inviteCode)
        put(COL_VISIBILITY, visibility.name)
        put(COL_HOST_PLAYER_ID, hostPlayerId)
        put(COL_STATUS, status.name)
        put(COL_MAX_MEMBERS, maxMembers)
        put(COL_CONFIG_JSON, configJson)
        put(COL_SERVER_REVISION, serverRevision)
        put(COL_CREATED_AT, createdAt)
        put(COL_UPDATED_AT, updatedAt)
    }
}

private fun LocalRoomMember.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_ID, id)
        put(COL_ROOM_ID, roomId)
        put(COL_PLAYER_ID, playerId)
        put(COL_DISPLAY_NAME, displayName)
        put(COL_ROLE, role.name)
        put(COL_STATUS, status.name)
        put(COL_SEAT_NO, seatNo)
        put(COL_JOINED_AT, joinedAt)
        put(COL_UPDATED_AT, updatedAt)
    }
}

private fun LocalMatchRecord.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_MATCH_ID, matchId)
        put(COL_ROOM_ID, roomId)
        put(COL_GAME_SLUG, gameSlug)
        put(COL_LOCAL_PLAYER_ID, localPlayerId)
        put(COL_OPPONENT_PLAYER_ID, opponentPlayerId)
        put(COL_STATUS, status.name)
        put(COL_MODE, mode)
        put(COL_CODE_LENGTH, codeLength)
        put(COL_ALLOW_DUPLICATES, allowDuplicates.toDbInt())
        put(COL_ATTEMPT_LIMIT, attemptLimit)
        put(COL_TURN_TIME_LIMIT_SEC, turnTimeLimitSec)
        put(COL_PLAYER_SECRET_HASH, playerSecretHash)
        put(COL_OPPONENT_SECRET_HASH, opponentSecretHash)
        put(COL_LOCAL_RESULT, localResult)
        put(COL_REMOTE_RESULT, remoteResult)
        put(COL_STARTED_AT, startedAt)
        put(COL_FINISHED_AT, finishedAt)
        put(COL_UPDATED_AT, updatedAt)
    }
}

private fun LocalMatchTurn.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_ID, id)
        put(COL_MATCH_ID, matchId)
        put(COL_PLAYER_ID, playerId)
        put(COL_TURN_INDEX, turnIndex)
        put(COL_GUESS, guess)
        put(COL_SCORE, score)
        put(COL_SERVER_ACKNOWLEDGED, serverAcknowledged.toDbInt())
        put(COL_CREATED_AT, createdAt)
    }
}

private fun PendingSyncOperation.toContentValues(): ContentValues {
    return ContentValues().apply {
        put(COL_ID, id)
        put(COL_SCOPE, scope)
        put(COL_ENTITY_ID, entityId)
        put(COL_OPERATION_TYPE, operationType.name)
        put(COL_PAYLOAD_JSON, payloadJson)
        put(COL_ENDPOINT_PATH, endpointPath)
        put(COL_METHOD, method)
        put(COL_IDEMPOTENCY_KEY, idempotencyKey)
        put(COL_STATUS, status.name)
        put(COL_RETRY_COUNT, retryCount)
        put(COL_LAST_ERROR, lastError)
        put(COL_CREATED_AT, createdAt)
        put(COL_UPDATED_AT, updatedAt)
    }
}

private fun playerProfileFromCursor(cursor: android.database.Cursor): LocalPlayerProfile {
    return LocalPlayerProfile(
        profileId = cursor.getInt(0),
        playerId = cursor.getString(1),
        installationId = cursor.getString(2),
        displayName = cursor.getString(3),
        avatarUrl = cursor.getStringOrNull(4),
        authProvider = cursor.getEnum(5, LocalAuthProvider.GUEST),
        isGuest = cursor.getInt(6) != 0,
        isOnline = cursor.getInt(7) != 0,
        locale = cursor.getStringOrNull(8) ?: Locale.getDefault().toLanguageTag(),
        regionCode = cursor.getStringOrNull(9) ?: Locale.getDefault().country,
        cloudRevision = cursor.getLong(10),
        lastSeenAt = cursor.getLong(11),
        createdAt = cursor.getLong(12),
        updatedAt = cursor.getLong(13),
    )
}

private fun identityLinkFromCursor(cursor: android.database.Cursor): LocalIdentityLink {
    return LocalIdentityLink(
        id = cursor.getString(0),
        provider = cursor.getEnum(1, LocalAuthProvider.GUEST),
        providerSubject = cursor.getString(2),
        playerId = cursor.getString(3),
        displayName = cursor.getStringOrNull(4),
        email = cursor.getStringOrNull(5),
        isPrimary = cursor.getInt(6) != 0,
        linkedAt = cursor.getLong(7),
        lastRefreshedAt = cursor.getLong(8),
    )
}

private fun relationshipFromCursor(cursor: android.database.Cursor): LocalSocialRelationship {
    return LocalSocialRelationship(
        id = cursor.getString(0),
        playerId = cursor.getString(1),
        targetPlayerId = cursor.getString(2),
        targetDisplayName = cursor.getString(3),
        relationshipType = cursor.getEnum(4, LocalRelationshipType.FRIEND),
        status = cursor.getEnum(5, LocalRelationshipStatus.PENDING),
        source = cursor.getString(6),
        note = cursor.getStringOrNull(7),
        createdAt = cursor.getLong(8),
        updatedAt = cursor.getLong(9),
    )
}

private fun roomFromCursor(cursor: android.database.Cursor): LocalOnlineRoom {
    return LocalOnlineRoom(
        roomId = cursor.getString(0),
        gameSlug = cursor.getString(1),
        roomName = cursor.getString(2),
        inviteCode = cursor.getStringOrNull(3),
        visibility = cursor.getEnum(4, LocalRoomVisibility.PRIVATE),
        hostPlayerId = cursor.getString(5),
        status = cursor.getEnum(6, LocalRoomStatus.WAITING),
        maxMembers = cursor.getInt(7),
        configJson = cursor.getString(8),
        serverRevision = cursor.getLong(9),
        createdAt = cursor.getLong(10),
        updatedAt = cursor.getLong(11),
    )
}

private fun memberFromCursor(cursor: android.database.Cursor): LocalRoomMember {
    return LocalRoomMember(
        id = cursor.getString(0),
        roomId = cursor.getString(1),
        playerId = cursor.getString(2),
        displayName = cursor.getString(3),
        role = cursor.getEnum(4, LocalRoomMemberRole.MEMBER),
        status = cursor.getEnum(5, LocalRoomMemberStatus.JOINED),
        seatNo = cursor.getIntOrNull(6),
        joinedAt = cursor.getLong(7),
        updatedAt = cursor.getLong(8),
    )
}

private fun matchFromCursor(cursor: android.database.Cursor): LocalMatchRecord {
    return LocalMatchRecord(
        matchId = cursor.getString(0),
        roomId = cursor.getStringOrNull(1),
        gameSlug = cursor.getString(2),
        localPlayerId = cursor.getString(3),
        opponentPlayerId = cursor.getStringOrNull(4),
        status = cursor.getEnum(5, LocalMatchStatus.CREATED),
        mode = cursor.getString(6),
        codeLength = cursor.getInt(7),
        allowDuplicates = cursor.getInt(8) != 0,
        attemptLimit = cursor.getInt(9),
        turnTimeLimitSec = cursor.getIntOrNull(10),
        playerSecretHash = cursor.getStringOrNull(11),
        opponentSecretHash = cursor.getStringOrNull(12),
        localResult = cursor.getStringOrNull(13),
        remoteResult = cursor.getStringOrNull(14),
        startedAt = cursor.getLong(15),
        finishedAt = cursor.getLong(16),
        updatedAt = cursor.getLong(17),
    )
}

private fun matchTurnFromCursor(cursor: android.database.Cursor): LocalMatchTurn {
    return LocalMatchTurn(
        id = cursor.getString(0),
        matchId = cursor.getString(1),
        playerId = cursor.getString(2),
        turnIndex = cursor.getInt(3),
        guess = cursor.getString(4),
        score = cursor.getInt(5),
        serverAcknowledged = cursor.getInt(6) != 0,
        createdAt = cursor.getLong(7),
    )
}

private fun syncOperationFromCursor(cursor: android.database.Cursor): PendingSyncOperation {
    return PendingSyncOperation(
        id = cursor.getString(0),
        scope = cursor.getString(1),
        entityId = cursor.getStringOrNull(2),
        operationType = cursor.getEnum(3, SyncOperationType.PUSH_PROGRESS),
        payloadJson = cursor.getString(4),
        endpointPath = cursor.getString(5),
        method = cursor.getString(6),
        idempotencyKey = cursor.getStringOrNull(7),
        status = cursor.getEnum(8, SyncOperationStatus.PENDING),
        retryCount = cursor.getInt(9),
        lastError = cursor.getStringOrNull(10),
        createdAt = cursor.getLong(11),
        updatedAt = cursor.getLong(12),
    )
}

private inline fun <T> android.database.Cursor.useRows(
    transform: (android.database.Cursor) -> T,
): List<T> {
    val items = mutableListOf<T>()
    use {
        while (it.moveToNext()) {
            items += transform(it)
        }
    }
    return items
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getIntOrNull(index: Int): Int? {
    return if (isNull(index)) null else getInt(index)
}

private inline fun <reified T : Enum<T>> android.database.Cursor.getEnum(
    index: Int,
    defaultValue: T,
): T {
    val raw = getStringOrNull(index) ?: return defaultValue
    return enumValues<T>().firstOrNull { it.name == raw } ?: defaultValue
}

private fun Boolean.toDbInt(): Int = if (this) 1 else 0

private const val DEFAULT_GAME_SLUG = "inplacex"

private const val COL_ID = "id"
private const val COL_PROFILE_ID = "profile_id"
private const val COL_PLAYER_ID = "player_id"
private const val COL_INSTALLATION_ID = "installation_id"
private const val COL_DISPLAY_NAME = "display_name"
private const val COL_AVATAR_URL = "avatar_url"
private const val COL_AUTH_PROVIDER = "auth_provider"
private const val COL_IS_GUEST = "is_guest"
private const val COL_IS_ONLINE = "is_online"
private const val COL_LOCALE = "locale"
private const val COL_REGION_CODE = "region_code"
private const val COL_CLOUD_REVISION = "cloud_revision"
private const val COL_LAST_SEEN_AT = "last_seen_at"
private const val COL_CREATED_AT = "created_at"
private const val COL_UPDATED_AT = "updated_at"
private const val COL_PROVIDER = "provider"
private const val COL_PROVIDER_SUBJECT = "provider_subject"
private const val COL_EMAIL = "email"
private const val COL_IS_PRIMARY = "is_primary"
private const val COL_LINKED_AT = "linked_at"
private const val COL_LAST_REFRESHED_AT = "last_refreshed_at"
private const val COL_TARGET_PLAYER_ID = "target_player_id"
private const val COL_TARGET_DISPLAY_NAME = "target_display_name"
private const val COL_RELATIONSHIP_TYPE = "relationship_type"
private const val COL_STATUS = "status"
private const val COL_SOURCE = "source"
private const val COL_NOTE = "note"
private const val COL_ROOM_ID = "room_id"
private const val COL_GAME_SLUG = "game_slug"
private const val COL_ROOM_NAME = "room_name"
private const val COL_INVITE_CODE = "invite_code"
private const val COL_VISIBILITY = "visibility"
private const val COL_HOST_PLAYER_ID = "host_player_id"
private const val COL_MAX_MEMBERS = "max_members"
private const val COL_CONFIG_JSON = "config_json"
private const val COL_SERVER_REVISION = "server_revision"
private const val COL_ROLE = "role"
private const val COL_SEAT_NO = "seat_no"
private const val COL_JOINED_AT = "joined_at"
private const val COL_MATCH_ID = "match_id"
private const val COL_LOCAL_PLAYER_ID = "local_player_id"
private const val COL_OPPONENT_PLAYER_ID = "opponent_player_id"
private const val COL_MODE = "mode"
private const val COL_CODE_LENGTH = "code_length"
private const val COL_ALLOW_DUPLICATES = "allow_duplicates"
private const val COL_ATTEMPT_LIMIT = "attempt_limit"
private const val COL_TURN_TIME_LIMIT_SEC = "turn_time_limit_sec"
private const val COL_PLAYER_SECRET_HASH = "player_secret_hash"
private const val COL_OPPONENT_SECRET_HASH = "opponent_secret_hash"
private const val COL_LOCAL_RESULT = "local_result"
private const val COL_REMOTE_RESULT = "remote_result"
private const val COL_STARTED_AT = "started_at"
private const val COL_FINISHED_AT = "finished_at"
private const val COL_TURN_INDEX = "turn_index"
private const val COL_GUESS = "guess"
private const val COL_SCORE = "score"
private const val COL_SERVER_ACKNOWLEDGED = "server_acknowledged"
private const val COL_SCOPE = "scope"
private const val COL_ENTITY_ID = "entity_id"
private const val COL_OPERATION_TYPE = "operation_type"
private const val COL_PAYLOAD_JSON = "payload_json"
private const val COL_ENDPOINT_PATH = "endpoint_path"
private const val COL_METHOD = "method"
private const val COL_IDEMPOTENCY_KEY = "idempotency_key"
private const val COL_RETRY_COUNT = "retry_count"
private const val COL_LAST_ERROR = "last_error"

private val PLAYER_PROFILE_COLUMNS = arrayOf(
    COL_PROFILE_ID,
    COL_PLAYER_ID,
    COL_INSTALLATION_ID,
    COL_DISPLAY_NAME,
    COL_AVATAR_URL,
    COL_AUTH_PROVIDER,
    COL_IS_GUEST,
    COL_IS_ONLINE,
    COL_LOCALE,
    COL_REGION_CODE,
    COL_CLOUD_REVISION,
    COL_LAST_SEEN_AT,
    COL_CREATED_AT,
    COL_UPDATED_AT,
)

private val IDENTITY_LINK_COLUMNS = arrayOf(
    COL_ID,
    COL_PROVIDER,
    COL_PROVIDER_SUBJECT,
    COL_PLAYER_ID,
    COL_DISPLAY_NAME,
    COL_EMAIL,
    COL_IS_PRIMARY,
    COL_LINKED_AT,
    COL_LAST_REFRESHED_AT,
)

private val RELATIONSHIP_COLUMNS = arrayOf(
    COL_ID,
    COL_PLAYER_ID,
    COL_TARGET_PLAYER_ID,
    COL_TARGET_DISPLAY_NAME,
    COL_RELATIONSHIP_TYPE,
    COL_STATUS,
    COL_SOURCE,
    COL_NOTE,
    COL_CREATED_AT,
    COL_UPDATED_AT,
)

private val ROOM_COLUMNS = arrayOf(
    COL_ROOM_ID,
    COL_GAME_SLUG,
    COL_ROOM_NAME,
    COL_INVITE_CODE,
    COL_VISIBILITY,
    COL_HOST_PLAYER_ID,
    COL_STATUS,
    COL_MAX_MEMBERS,
    COL_CONFIG_JSON,
    COL_SERVER_REVISION,
    COL_CREATED_AT,
    COL_UPDATED_AT,
)

private val ROOM_MEMBER_COLUMNS = arrayOf(
    COL_ID,
    COL_ROOM_ID,
    COL_PLAYER_ID,
    COL_DISPLAY_NAME,
    COL_ROLE,
    COL_STATUS,
    COL_SEAT_NO,
    COL_JOINED_AT,
    COL_UPDATED_AT,
)

private val MATCH_COLUMNS = arrayOf(
    COL_MATCH_ID,
    COL_ROOM_ID,
    COL_GAME_SLUG,
    COL_LOCAL_PLAYER_ID,
    COL_OPPONENT_PLAYER_ID,
    COL_STATUS,
    COL_MODE,
    COL_CODE_LENGTH,
    COL_ALLOW_DUPLICATES,
    COL_ATTEMPT_LIMIT,
    COL_TURN_TIME_LIMIT_SEC,
    COL_PLAYER_SECRET_HASH,
    COL_OPPONENT_SECRET_HASH,
    COL_LOCAL_RESULT,
    COL_REMOTE_RESULT,
    COL_STARTED_AT,
    COL_FINISHED_AT,
    COL_UPDATED_AT,
)

private val MATCH_TURN_COLUMNS = arrayOf(
    COL_ID,
    COL_MATCH_ID,
    COL_PLAYER_ID,
    COL_TURN_INDEX,
    COL_GUESS,
    COL_SCORE,
    COL_SERVER_ACKNOWLEDGED,
    COL_CREATED_AT,
)

private val SYNC_COLUMNS = arrayOf(
    COL_ID,
    COL_SCOPE,
    COL_ENTITY_ID,
    COL_OPERATION_TYPE,
    COL_PAYLOAD_JSON,
    COL_ENDPOINT_PATH,
    COL_METHOD,
    COL_IDEMPOTENCY_KEY,
    COL_STATUS,
    COL_RETRY_COUNT,
    COL_LAST_ERROR,
    COL_CREATED_AT,
    COL_UPDATED_AT,
)
