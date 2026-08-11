package com.mirkori.inplacex

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortraitOrientationInstrumentedTest {
    @Test
    fun mergedManifestKeepsGameInPortrait() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
        val activityInfo = packageManager.getActivityInfo(
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!.component!!,
            0,
        )

        assertEquals(ApplicationInfo.CATEGORY_GAME, applicationInfo.category)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activityInfo.screenOrientation)
    }
}
