package com.mirkori.inplacex.platform.feedback

import androidx.compose.runtime.staticCompositionLocalOf

data class AppFeedbackSettings(
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
)

enum class AppSoundCue {
    TAP,
    CONFIRM,
    SUCCESS,
    FAILURE,
    NOTIFICATION,
}

enum class AppHapticCue {
    SELECTION,
    CONFIRM,
    SUCCESS,
    FAILURE,
}

interface AppFeedbackRuntime : AutoCloseable {
    fun updateSettings(settings: AppFeedbackSettings)

    fun playSound(cue: AppSoundCue)

    fun performHaptic(cue: AppHapticCue)

    fun onForeground()

    fun onBackground()

    override fun close()
}

private object NoOpAppFeedbackRuntime : AppFeedbackRuntime {
    override fun updateSettings(settings: AppFeedbackSettings) = Unit
    override fun playSound(cue: AppSoundCue) = Unit
    override fun performHaptic(cue: AppHapticCue) = Unit
    override fun onForeground() = Unit
    override fun onBackground() = Unit
    override fun close() = Unit
}

val LocalAppFeedbackRuntime = staticCompositionLocalOf<AppFeedbackRuntime> {
    NoOpAppFeedbackRuntime
}
