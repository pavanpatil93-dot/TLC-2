package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object TeamNotificationManager {
    const val EXTRA_ALERT_ID = "extra_tlc_alert_id"
    const val EXTRA_TARGET_TYPE = "extra_tlc_target_type"
    const val EXTRA_TARGET_ID = "extra_tlc_target_id"

    fun ensureChannelsCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        TeamAlertChannel.entries.forEach { ch ->
            if (manager.getNotificationChannel(ch.channelId) == null) {
                val channel = NotificationChannel(
                    ch.channelId,
                    ch.displayName,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = ch.description
                    enableVibration(true)
                    setShowBadge(true)
                    setSound(defaultSound, audioAttributes)
                }
                runCatching { manager.createNotificationChannel(channel) }
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching { NotificationManagerCompat.from(appContext).areNotificationsEnabled() }
            .getOrDefault(true)
    }

    fun openChannelSoundSettings(context: Context, channel: TeamAlertChannel) {
        ensureChannelsCreated(context)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channel.channelId)
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra("app_package", context.packageName)
                putExtra("app_uid", context.applicationInfo.uid)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun postAlertNotification(context: Context, alert: TeamAlertItem) {
        val appContext = context.applicationContext
        ensureChannelsCreated(appContext)
        if (!hasNotificationPermission(appContext)) return

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_ALERT_ID, alert.id)
            putExtra(EXTRA_TARGET_TYPE, alert.targetType.name)
            putExtra(EXTRA_TARGET_ID, alert.targetId)
        }
        val requestCode = alert.id.hashCode()
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(appContext, requestCode, launchIntent, pendingFlags)

        val builder = NotificationCompat.Builder(appContext, alert.channel.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        runCatching {
            NotificationManagerCompat.from(appContext).notify(requestCode, builder.build())
        }
    }
}
