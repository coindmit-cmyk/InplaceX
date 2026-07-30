package com.mirkori.inplacex.platform.ads

import android.app.Activity
import java.lang.ref.WeakReference

class AdActivityHost {
    private var activityReference = WeakReference<Activity>(null)

    @Synchronized
    fun attach(activity: Activity) {
        activityReference = WeakReference(activity)
    }

    @Synchronized
    fun detach(activity: Activity) {
        if (activityReference.get() === activity) {
            activityReference.clear()
        }
    }

    @Synchronized
    fun currentActivity(): Activity? =
        activityReference.get()?.takeUnless {
            it.isFinishing || it.isDestroyed || !it.hasWindowFocus()
        }
}
