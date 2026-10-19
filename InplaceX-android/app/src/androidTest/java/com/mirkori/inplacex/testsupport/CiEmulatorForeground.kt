package com.mirkori.inplacex.testsupport

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.mirkori.inplacex.MainActivity

internal fun foregroundMainActivityForCiEmulator() {
    if (InstrumentationRegistry.getArguments().getString("ciEmulatorForeground") != "true") return
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
        "Automatic foreground handoff is restricted to the CI emulator"
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val component = "${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
    val output = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $component"),
    ).bufferedReader().use { it.readText() }
    check("Status: ok" in output) { "Unable to foreground ordinary MainActivity: $output" }
    instrumentation.waitForIdleSync()
}
