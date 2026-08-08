package com.mirkori.inplacex.ui.update

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.update.AppUpdateRelease
import com.mirkori.inplacex.platform.update.AppUpdateState
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppUpdateDialogSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optionalUpdateCanBeDeferredWithoutBlockingTheGame() {
        var deferred = false
        setContent(required = false, onDismiss = { deferred = true })

        composeRule.onNodeWithTag("optional-update-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Доступно обновление").assertIsDisplayed()
        composeRule.onNodeWithText("Позже").performClick()
        composeRule.runOnIdle { assertTrue(deferred) }
    }

    @Test
    fun requiredUpdateHasOneExplicitDownloadActionAndNoLaterAction() {
        var downloadRequested = false
        setContent(required = true, onDownload = { downloadRequested = true })

        composeRule.onNodeWithTag("required-update-dialog").assertIsDisplayed()
        composeRule.onAllNodesWithText("Позже").assertCountEquals(0)
        composeRule.onNodeWithTag("update-primary-action").performClick()
        composeRule.runOnIdle { assertTrue(downloadRequested) }
    }

    @Test
    fun appDoesNotRequestPackageInstallerPermission() {
        val requestedPermissions = composeRule.activity.packageManager
            .getPackageInfo(
                composeRule.activity.packageName,
                android.content.pm.PackageManager.GET_PERMISSIONS,
            )
            .requestedPermissions
            .orEmpty()

        assertFalse(Manifest.permission.REQUEST_INSTALL_PACKAGES in requestedPermissions)
    }

    private fun setContent(
        required: Boolean,
        onDownload: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU),
            ) {
                InplaceXTheme {
                    AppUpdateDialog(
                        state = if (required) {
                            AppUpdateState.Required(release())
                        } else {
                            AppUpdateState.Optional(release())
                        },
                        onPrimaryAction = onDownload,
                        onDismissOptional = onDismiss,
                    )
                }
            }
        }
    }

    private fun release() = AppUpdateRelease(
        id = "inplacex-1.0.0-100",
        versionName = "1.0.0",
        versionCode = 100,
        minimumSupportedVersionCode = 50,
        minimumAndroidSdk = 29,
        publishedAt = Instant.parse("2026-08-07T12:00:00Z"),
        changelog = "Release notes",
        fileName = "InplaceX-1.0.0.apk",
        sizeBytes = 12_345_678,
        sha256 = "a".repeat(64),
        downloadUrl = "https://games.dmit.life/downloads/inplacex-1.0.0-100/InplaceX-1.0.0.apk",
        packageName = "com.mirkori.inplacex",
        signingCertificateSha256Fingerprints = listOf(List(32) { "AA" }.joinToString(":")),
    )
}
