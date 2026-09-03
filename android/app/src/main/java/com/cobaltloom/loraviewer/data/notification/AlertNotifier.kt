package com.cobaltloom.loraviewer.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cobaltloom.loraviewer.data.alert.GliderAlertReason
import com.cobaltloom.loraviewer.data.alert.overallSeverity

/**
 * Fires a notification when a glider newly enters an alerting state. Only fires while this app
 * process is alive (foreground, or briefly backgrounded) - there's no server pushing these.
 */
class AlertNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "altitude_alerts"
    }

    init {
        val channel = NotificationChannel(CHANNEL_ID, "高度アラート", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notify(gliderName: String, reasons: List<GliderAlertReason>) {
        val severity = reasons.overallSeverity() ?: return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("高度アラート(${severity.label})")
            .setContentText("$gliderName: ${reasons.joinToString("・") { it.label }}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(gliderName.hashCode(), notification)
    }
}
