package com.mirkori.inplacex.platform.update

import android.content.Context

internal class AndroidAppUpdatePolicyStore(context: Context) : AppUpdatePolicyStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(): AppUpdatePolicyState = AppUpdatePolicyState(
        nextCheckAfterMs = preferences.getLong(NextCheckAfterKey, 0L),
        dismissedReleaseId = preferences.getString(DismissedReleaseIdKey, null),
        dismissedUntilMs = preferences.getLong(DismissedUntilKey, 0L),
    )

    override fun write(state: AppUpdatePolicyState): Boolean = preferences.edit()
        .putLong(NextCheckAfterKey, state.nextCheckAfterMs)
        .putString(DismissedReleaseIdKey, state.dismissedReleaseId)
        .putLong(DismissedUntilKey, state.dismissedUntilMs)
        .commit()

    private companion object {
        const val PreferencesName = "inplacex_app_update_policy_v1"
        const val NextCheckAfterKey = "next_check_after_ms"
        const val DismissedReleaseIdKey = "dismissed_release_id"
        const val DismissedUntilKey = "dismissed_until_ms"
    }
}
