package com.mirkori.inplacex.platform.ads

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
            val lifecycleState = (it as? LifecycleOwner)?.lifecycle?.currentState
            !isAdPresentationHostUsable(
                isFinishing = it.isFinishing,
                isDestroyed = it.isDestroyed,
                isResumed = lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true,
            )
        }
}

/**
 * Full-screen ads may be requested from a modal dialog. In that state the activity can
 * temporarily lose window focus even though it is still the valid presentation host.
 */
internal fun isAdPresentationHostUsable(
    isFinishing: Boolean,
    isDestroyed: Boolean,
    isResumed: Boolean,
): Boolean = !isFinishing && !isDestroyed && isResumed
