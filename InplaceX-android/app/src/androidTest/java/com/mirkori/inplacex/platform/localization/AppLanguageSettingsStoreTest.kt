package com.mirkori.inplacex.platform.localization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppLanguageSettingsStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withPreferences(block: (String) -> Unit) {
        val name = "test_language_${UUID.randomUUID()}"
        try {
            block(name)
        } finally {
            // This name is unique to this test; production and owner preferences are untouched.
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun missingValueDefaultsToRussianWithoutWriting() = withPreferences { name ->
        val preferences = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        assertEquals(AppLanguage.RU, AppLanguageSettingsStore(context, name).read())
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun russianAndEnglishRoundTripAcrossStoreInstances() = withPreferences { name ->
        val first = AppLanguageSettingsStore(context, name)
        first.write(AppLanguage.EN)
        assertEquals(AppLanguage.EN, AppLanguageSettingsStore(context, name).read())
        AppLanguageSettingsStore(context, name).write(AppLanguage.RU)
        assertEquals(AppLanguage.RU, AppLanguageSettingsStore(context, name).read())
    }

    @Test
    fun unknownAndWrongTypeValuesFallBackToRussian() = withPreferences { name ->
        val preferences = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        val store = AppLanguageSettingsStore(context, name)
        preferences.edit().putString("app_language", "FR").commit()
        assertEquals(AppLanguage.RU, store.read())
        preferences.edit().putInt("app_language", 7).commit()
        assertEquals(AppLanguage.RU, store.read())
        assertTrue(preferences.contains("app_language"))
    }

    @Test
    fun languageWritesPreserveUnrelatedAndSeparatePreferences() = withPreferences { name ->
        val preferences = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        preferences.edit().putString("unrelated", "keep").commit()
        val otherPreferencesName = "test_owner_${UUID.randomUUID()}"
        try {
            val otherPreferences = context.getSharedPreferences(
                otherPreferencesName,
                android.content.Context.MODE_PRIVATE,
            )
            otherPreferences.edit().putBoolean("sound_enabled", false)
                .putBoolean("music_enabled", true).putString("privacy", "keep").commit()

            AppLanguageSettingsStore(context, name).write(AppLanguage.EN)

            assertEquals("keep", preferences.getString("unrelated", null))
            assertEquals(AppLanguage.EN, AppLanguageSettingsStore(context, name).read())
            assertFalse(otherPreferences.getBoolean("sound_enabled", true))
            assertTrue(otherPreferences.getBoolean("music_enabled", false))
            assertEquals("keep", otherPreferences.getString("privacy", null))
        } finally {
            // The owner-like preference file is also uniquely created by this test.
            context.getSharedPreferences(otherPreferencesName, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }
}
