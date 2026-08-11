package com.mirkori.inplacex

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitOrientationContractTest {
    @Test
    fun mainActivityIsLockedToPortrait() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("InplaceX-android/app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Cannot locate AndroidManifest.xml")
        val mainActivity = manifest
            .substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        val application = manifest
            .substringAfter("<application")
            .substringBefore("<activity")

        assertTrue(application.contains("android:appCategory=\"game\""))
        assertTrue(mainActivity.contains("android:screenOrientation=\"portrait\""))
    }
}
