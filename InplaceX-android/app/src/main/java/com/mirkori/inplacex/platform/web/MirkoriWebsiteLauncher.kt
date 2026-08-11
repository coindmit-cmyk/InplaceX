package com.mirkori.inplacex.platform.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.logging.AppLog

enum class MirkoriWebsitePage(val path: String) {
    CONTACT("/contact"),
    TERMS("/terms"),
    PRIVACY("/privacy"),
    ABOUT("/about"),
    OPEN_SOURCE_LICENSES("/open-source-licenses"),
}

object MirkoriWebsiteLauncher {
    fun open(activity: Activity, page: MirkoriWebsitePage) {
        val uri = websiteUri(page) ?: run {
            AppLog.warn(
                tag = "MirkoriWebsiteLauncher",
                message = "Rejected invalid Mirkori Games website origin",
            )
            return
        }
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure { error ->
                AppLog.warn(
                    tag = "MirkoriWebsiteLauncher",
                    message = "No browser is available for the Mirkori Games website",
                    throwable = error,
                )
            }
        }
    }

    private fun websiteUri(page: MirkoriWebsitePage): Uri? {
        val base = Uri.parse(BuildConfig.MIRKORI_PLATFORM_BASE_URL.trimEnd('/'))
        val schemeAllowed = base.scheme.equals("https", ignoreCase = true) ||
            (BuildConfig.MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK && base.isLoopback())
        if (!schemeAllowed || base.host.isNullOrBlank() || base.userInfo != null) return null
        return base.buildUpon()
            .path(page.path)
            .clearQuery()
            .fragment(null)
            .build()
    }

    private fun Uri.isLoopback(): Boolean =
        scheme.equals("http", ignoreCase = true) &&
            host?.lowercase() in setOf("127.0.0.1", "localhost", "10.0.2.2")
}
