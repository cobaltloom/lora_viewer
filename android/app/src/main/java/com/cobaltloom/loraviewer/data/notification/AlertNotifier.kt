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
        const val TURNPOINT_CHANNEL_ID = "turnpoint_passage"
        const val PROXIMITY_CHANNEL_ID = "proximity_alerts"
    }

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "高度アラート", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(TURNPOINT_CHANNEL_ID, "旋回点通過", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(PROXIMITY_CHANNEL_ID, "機体接近", NotificationManager.IMPORTANCE_HIGH),
        )
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

    /**
     * Fired once each time a glider newly enters a competition turnpoint's sector, per JSAL's
     * rule 43 - simplified here to "within its 2,000m radius" rather than the exact 90° wedge,
     * matching how the sector is drawn on the map.
     */
    fun notifyTurnpointPassage(gliderName: String, turnpointName: String, altitudeM: Double?) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val altitudeText = altitudeM?.let { "${it.toInt()}m" } ?: "高度不明"
        val notification = NotificationCompat.Builder(context, TURNPOINT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("旋回点通過")
            .setContentText("$gliderName: $turnpointName($altitudeText)")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((gliderName + turnpointName).hashCode(), notification)
    }

    /** Fired when two gliders are closing within the proximity warning distance - see [com.cobaltloom.loraviewer.data.alert.ProximityAlertSettings]. */
    fun notifyProximity(gliderName: String, otherGliderName: String, distanceM: Double, altitudeDifferenceM: Double) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, PROXIMITY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("機体接近")
            .setContentText("$gliderName と $otherGliderName: 水平${distanceM.toInt()}m・高度差${altitudeDifferenceM.toInt()}m")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((gliderName + otherGliderName).hashCode(), notification)
    }
}
