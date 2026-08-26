package com.mirkori.inplacex.platform.online

import android.content.Context
import com.mirkori.inplacex.platform.logging.AppLog
import java.util.UUID

class ActiveOnlineSessionStore(
    context: Context,
    private val preferencesName: String = DefaultPreferencesName,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun read(): String? {
        val sessionId = preferences.getString(SessionIdKey, null) ?: return null
        if (!sessionId.isCanonicalUuid()) {
            clear()
            return null
        }
        return sessionId
    }

    fun write(sessionId: String) {
        require(sessionId.isCanonicalUuid()) { "sessionId must be a canonical UUID" }
        check(preferences.edit().putString(SessionIdKey, sessionId).commit()) {
            "Unable to persist active online session"
        }
    }

    fun clear() {
        preferences.edit().remove(SessionIdKey).commit()
    }

    fun readPendingInvite(playerId: String): String? {
        require(playerId.isCanonicalUuid())
        val code = preferences.getString("invite_$playerId", null) ?: return null
        if (!code.matches(InviteCodePattern)) {
            clearPendingInvite(playerId)
            return null
        }
        return code
    }

    fun writePendingInvite(playerId: String, code: String) {
        require(playerId.isCanonicalUuid())
        require(code.matches(InviteCodePattern))
        check(preferences.edit().putString("invite_$playerId", code).commit()) {
            "Unable to persist pending online invitation"
        }
        AppLog.info(tag = "OnlineRecovery", message = "Pending invitation saved")
    }

    fun clearPendingInvite(playerId: String) {
        require(playerId.isCanonicalUuid())
        preferences.edit().remove("invite_$playerId").commit()
    }

    private fun String.isCanonicalUuid(): Boolean =
        runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

    private companion object {
        const val DefaultPreferencesName = "inplacex_active_online_session"
        const val SessionIdKey = "session_id"
        val InviteCodePattern = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}")
    }
}
