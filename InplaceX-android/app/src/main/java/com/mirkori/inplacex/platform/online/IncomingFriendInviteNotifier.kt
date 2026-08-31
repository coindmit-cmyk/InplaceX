package com.mirkori.inplacex.platform.online

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mirkori.inplacex.MainActivity
import com.mirkori.inplacex.R

class IncomingFriendInviteNotifier(context: Context) {
    private val applicationContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(applicationContext)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    ChannelId,
                    applicationContext.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun post(invite: OnlineFriendInvite, title: String, message: String) {
        post(invite.inviteCode, title, message)
    }

    @SuppressLint("MissingPermission")
    fun postFriendRequest(requestId: String, title: String, message: String) {
        post("friend-request:$requestId", title, message)
    }

    @SuppressLint("MissingPermission")
    private fun post(notificationKey: String, title: String, message: String) {
        if (!canPostNotifications()) return
        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                action = OpenSocialAction
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setSmallIcon(R.drawable.ic_game_invite_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()
        try {
            notificationManager.notify(notificationKey.hashCode(), notification)
        } catch (_: SecurityException) {
            // The permission may be revoked between the explicit check and this call.
        }
    }

    companion object {
        const val OpenSocialAction = "com.mirkori.inplacex.OPEN_SOCIAL_INVITATIONS"
        private const val ChannelId = "friend_game_invites"
    }
}
