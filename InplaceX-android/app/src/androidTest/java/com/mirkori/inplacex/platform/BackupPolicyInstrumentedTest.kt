package com.mirkori.inplacex.platform

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPolicyInstrumentedTest {
    @Test
    fun platformBackupIsDisabledUntilCloudReconciliationIsAuthoritative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
