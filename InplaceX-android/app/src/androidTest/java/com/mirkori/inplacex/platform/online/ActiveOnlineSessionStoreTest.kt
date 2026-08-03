package com.mirkori.inplacex.platform.online

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveOnlineSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun activeSessionSurvivesStoreRecreationAndCanBeCleared() {
        val preferencesName = "active-session-${UUID.randomUUID()}"
        val sessionId = UUID.randomUUID().toString()
        val store = ActiveOnlineSessionStore(context, preferencesName)
        try {
            store.write(sessionId)

            assertEquals(sessionId, ActiveOnlineSessionStore(context, preferencesName).read())

            store.clear()
            assertNull(ActiveOnlineSessionStore(context, preferencesName).read())
        } finally {
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun malformedSessionIdFailsClosedAndIsRemoved() {
        val preferencesName = "active-session-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        try {
            preferences.edit().putString("session_id", "../../foreign-session").commit()

            assertNull(ActiveOnlineSessionStore(context, preferencesName).read())
            assertFalse(preferences.contains("session_id"))
            assertThrows(IllegalArgumentException::class.java) {
                ActiveOnlineSessionStore(context, preferencesName).write("not-a-session")
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
