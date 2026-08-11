package com.mirkori.inplacex.platform.feedback

import android.content.Context
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class AppFeedbackSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    fun read(): AppFeedbackSettings = AppFeedbackSettings(
        vibrationEnabled = preferences.getBoolean(VibrationKey, true),
        soundEnabled = preferences.getBoolean(SoundKey, true),
        musicEnabled = preferences.getBoolean(MusicKey, true),
    )

    fun write(settings: AppFeedbackSettings) {
        preferences.edit()
            .putBoolean(VibrationKey, settings.vibrationEnabled)
            .putBoolean(SoundKey, settings.soundEnabled)
            .putBoolean(MusicKey, settings.musicEnabled)
            .apply()
    }

    private companion object {
        const val PreferencesName = "inplacex_feedback_settings"
        const val VibrationKey = "vibration_enabled"
        const val SoundKey = "sound_enabled"
        const val MusicKey = "music_enabled"
    }
}

class AndroidAppFeedbackRuntime(
    context: Context,
    private val musicResourceId: Int? = null,
) : AppFeedbackRuntime {
    private val applicationContext = context.applicationContext
    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, TestToneVolumePercent)
    }.getOrNull()
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        applicationContext.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private var settings = AppFeedbackSettings()
    private var foreground = false
    private var musicPlayer: MediaPlayer? = null

    override fun updateSettings(settings: AppFeedbackSettings) {
        this.settings = settings
        synchronizeMusic()
    }

    override fun playSound(cue: AppSoundCue) {
        if (!settings.soundEnabled) return
        val (tone, durationMs) = when (cue) {
            AppSoundCue.TAP -> ToneGenerator.TONE_PROP_BEEP to 45
            AppSoundCue.CONFIRM -> ToneGenerator.TONE_PROP_ACK to 90
            AppSoundCue.SUCCESS -> ToneGenerator.TONE_PROP_ACK to 180
            AppSoundCue.FAILURE -> ToneGenerator.TONE_PROP_NACK to 180
            AppSoundCue.NOTIFICATION -> ToneGenerator.TONE_PROP_PROMPT to 140
        }
        toneGenerator?.startTone(tone, durationMs)
    }

    override fun performHaptic(cue: AppHapticCue) {
        if (!settings.vibrationEnabled || !vibrator.hasVibrator()) return
        val durationMs = when (cue) {
            AppHapticCue.SELECTION -> 18L
            AppHapticCue.CONFIRM -> 32L
            AppHapticCue.SUCCESS -> 55L
            AppHapticCue.FAILURE -> 75L
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    override fun onForeground() {
        foreground = true
        synchronizeMusic()
    }

    override fun onBackground() {
        foreground = false
        musicPlayer?.pause()
    }

    override fun close() {
        toneGenerator?.release()
        musicPlayer?.release()
        musicPlayer = null
    }

    private fun synchronizeMusic() {
        val resourceId = musicResourceId
        if (!foreground || !settings.musicEnabled || resourceId == null) {
            musicPlayer?.pause()
            return
        }
        val player = musicPlayer ?: runCatching {
            MediaPlayer.create(applicationContext, resourceId)?.also {
                it.isLooping = true
                it.setVolume(MusicVolume, MusicVolume)
                musicPlayer = it
            }
        }.getOrNull()
        if (player != null && !player.isPlaying) player.start()
    }

    private companion object {
        const val TestToneVolumePercent = 55
        const val MusicVolume = 0.35f
    }
}
