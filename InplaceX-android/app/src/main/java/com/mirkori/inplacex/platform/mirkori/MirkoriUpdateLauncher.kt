package com.mirkori.inplacex.platform.mirkori

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.mirkori.inplacex.platform.logging.AppLog

internal object MirkoriUpdateLauncher {
    fun open(activity: Activity, target: MirkoriUpdateTarget): Boolean = when (target) {
        is MirkoriUpdateTarget.DirectApk -> openDirectApk(activity, target.url)
        is MirkoriUpdateTarget.GooglePlay -> openGooglePlay(activity, target.packageName)
    }

    private fun openDirectApk(activity: Activity, value: String): Boolean {
        val uri = Uri.parse(value)
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null
        ) {
            AppLog.warn(LogTag, "Rejected invalid Mirkori update URL")
            return false
        }
        return launch(activity, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun openGooglePlay(activity: Activity, packageName: String): Boolean {
        val market = Uri.parse("market://details?id=$packageName")
        if (launch(activity, Intent(Intent.ACTION_VIEW, market).setPackage("com.android.vending"), log = false)) {
            return true
        }
        val web = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        return launch(activity, Intent(Intent.ACTION_VIEW, web).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun launch(activity: Activity, intent: Intent, log: Boolean = true): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        if (log) AppLog.warn(LogTag, "No handler is available for the Mirkori update target")
        false
    } catch (error: SecurityException) {
        if (log) {
            AppLog.warn(
                tag = LogTag,
                message = "Mirkori update target was rejected by Android",
                throwable = error,
            )
        }
        false
    }

    private const val LogTag = "MirkoriUpdate"
}
