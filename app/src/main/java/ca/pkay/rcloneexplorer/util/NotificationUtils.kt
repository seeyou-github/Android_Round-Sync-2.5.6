package ca.pkay.rcloneexplorer.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

class NotificationUtils {


    companion object {

        @SuppressLint("MissingPermission") // Checked by PermissionManager(context).grantedNotifications()
        @JvmStatic
        fun createNotification(context: Context, notificationId: Int, notification: Notification) {
            val permissionGranted = PermissionManager(context).grantedNotifications()
            val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId else "pre-o"
            val channelState = getChannelState(context, channelId)
            FLog.e(
                "NotificationUtils",
                "createNotification request: id=%d channel=%s permissionGranted=%s notificationsEnabled=%s channelState=%s",
                notificationId,
                channelId,
                permissionGranted.toString(),
                notificationsEnabled.toString(),
                channelState
            )
            if(permissionGranted) {
                try {
                    val notificationManager = NotificationManagerCompat.from(context)
                    notificationManager.notify(notificationId, notification)
                    FLog.e("NotificationUtils", "createNotification notified: id=%d channel=%s", notificationId, channelId)
                } catch (e: SecurityException) {
                    FLog.e("NotificationUtils", "createNotification blocked by SecurityException: id=%d channel=%s", e, notificationId, channelId)
                } catch (e: RuntimeException) {
                    FLog.e("NotificationUtils", "createNotification failed: id=%d channel=%s", e, notificationId, channelId)
                }
            } else {
                FLog.e("NotificationUtils", "createNotification skipped: notification permission denied id=%d channel=%s", notificationId, channelId)
            }
        }


        @SuppressLint("MissingPermission") // Checked by PermissionManager(context).grantedNotifications()
        @JvmStatic
        fun createNotificationChannel(context: Context, channelId: String, channelName: String, importance: Int, description: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Create the NotificationChannel, but only on API 26+ because
                // the NotificationChannel class is new and not in the support library
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    importance
                )
                channel.description = description
                // Register the channel with the system
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                notificationManager?.createNotificationChannel(channel)
                FLog.e(
                    "NotificationUtils",
                    "createNotificationChannel: channel=%s name=%s importance=%d state=%s",
                    channelId,
                    channelName,
                    importance,
                    getChannelState(context, channelId)
                )
            }
        }

        private fun getChannelState(context: Context, channelId: String): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return "not_applicable"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                ?: return "notification_manager_null"
            val channel = notificationManager.getNotificationChannel(channelId)
                ?: return "missing"
            return "importance=${channel.importance},blocked=${channel.importance == NotificationManager.IMPORTANCE_NONE}"
        }
    }

}
