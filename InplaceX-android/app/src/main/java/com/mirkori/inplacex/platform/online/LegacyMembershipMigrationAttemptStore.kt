package com.mirkori.inplacex.platform.online

import android.content.Context
import java.util.UUID

data class LegacyMembershipMigrationAttempt(
    val sessionId: String,
    val commandId: String,
) {
    init {
        require(sessionId.isCanonicalUuid()) { "sessionId must be a canonical UUID" }
        require(commandId.isCanonicalUuid()) { "commandId must be a canonical UUID" }
    }
}

interface LegacyMembershipMigrationAttemptStore {
    fun read(): LegacyMembershipMigrationAttempt?

    fun write(attempt: LegacyMembershipMigrationAttempt)

    fun clear()
}

/**
 * Stores only a non-secret recovery marker. The legacy credential remains in the
 * Android Keystore-backed store and is never copied into these preferences.
 */
class SharedPreferencesLegacyMembershipMigrationAttemptStore(
    context: Context,
    private val preferencesName: String = DefaultPreferencesName,
) : LegacyMembershipMigrationAttemptStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(): LegacyMembershipMigrationAttempt? {
        val sessionId = preferences.getString(SessionIdKey, null) ?: return null
        val commandId = preferences.getString(CommandIdKey, null) ?: return clearCorruptedAttempt()
        return runCatching { LegacyMembershipMigrationAttempt(sessionId, commandId) }
            .getOrElse { clearCorruptedAttempt() }
    }

    override fun write(attempt: LegacyMembershipMigrationAttempt) {
        check(
            preferences.edit()
                .putString(SessionIdKey, attempt.sessionId)
                .putString(CommandIdKey, attempt.commandId)
                .commit(),
        ) { "Unable to persist legacy membership migration attempt" }
    }

    override fun clear() {
        check(
            preferences.edit()
                .remove(SessionIdKey)
                .remove(CommandIdKey)
                .commit(),
        ) { "Unable to clear legacy membership migration attempt" }
    }

    private fun clearCorruptedAttempt(): LegacyMembershipMigrationAttempt? {
        clear()
        return null
    }

    private companion object {
        const val DefaultPreferencesName = "inplacex_legacy_online_membership_migration"
        const val SessionIdKey = "session_id"
        const val CommandIdKey = "command_id"
    }
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
