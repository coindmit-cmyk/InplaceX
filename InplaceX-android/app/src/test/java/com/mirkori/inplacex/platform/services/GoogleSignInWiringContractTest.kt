package com.mirkori.inplacex.platform.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInWiringContractTest {
    @Test
    fun googleProfileActionUsesNativeCredentialFlowInsteadOfBrowserLogin() {
        val source = listOf(
            File("src/main/java/com/mirkori/inplacex/MainActivity.kt"),
            File("InplaceX-android/app/src/main/java/com/mirkori/inplacex/MainActivity.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Cannot locate MainActivity.kt")
        val action = source.substringAfter("onGooglePlaySignIn = {")
            .substringBefore("onGooglePlaySignOut = {")

        assertTrue(action.contains("runtime.beginGoogleLogin()"))
        assertTrue(action.contains("googleCredentialSignIn.signIn("))
        assertTrue(action.contains("runtime.completeGoogleLogin("))
        assertTrue(action.contains("MirkoriLoginResult.ProfileConflict ->"))
        assertTrue(action.contains("\"profile.mirkori.conflict\""))
        assertTrue(action.contains("pendingGoogleProfileConflict"))
        assertTrue(source.contains("PlatformProfileConflictResolution.USE_EXISTING_PROFILE"))
        assertTrue(source.contains("runtime?.cancelPendingLogin()"))
        assertFalse(action.contains("Intent.ACTION_VIEW"))
    }
}
