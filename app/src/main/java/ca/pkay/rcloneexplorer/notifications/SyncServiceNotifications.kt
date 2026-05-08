package ca.pkay.rcloneexplorer.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Activities.CurrentSyncDetailsActivity
import ca.pkay.rcloneexplorer.BroadcastReceivers.SyncRestartAction
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.workmanager.SyncWorker
import ca.pkay.rcloneexplorer.workmanager.SyncWorker.Companion.EXTRA_TASK_ID
import java.util.UUID

class SyncServiceNotifications(var mContext: Context) {


    companion object {
        const val CHANNEL_ID = "ca.pkay.rcexplorer.sync_service"
        const val CHANNEL_SUCCESS_ID = "ca.pkay.rcexplorer.sync_service_success"
        const val CHANNEL_FAIL_ID = "ca.pkay.rcexplorer.sync_service_fail"

        const val GROUP_ID = "ca.pkay.rcexplorer.sync_service.group"

        const val PERSISTENT_NOTIFICATION_ID_FOR_SYNC = 162
        const val CANCEL_ID_NOTSET = "CANCEL_ID_NOTSET"
        const val TAG = "SyncServiceNotifications"
        private const val RESULT_NOTIFICATION_DELAY_MS = 800L

    }

    private val OPERATION_FAILED_GROUP = "ca.pkay.rcexplorer.OPERATION_FAILED_GROUP"


    private var mCancelUnsetId: UUID = UUID.randomUUID()
    private var mCancelId: UUID = mCancelUnsetId

    fun setCancelId(id: UUID) {
        mCancelId = id
    }

    fun showFailedNotificationOrReport(
        _title: String,
        content: String,
        notificationId: Int,
        taskid: Long
    ) {
        FLog.e(TAG, "showFailedNotificationOrReport: notificationId=%d taskId=%d content=%s", notificationId, taskid, content)
        showFailedNotification(content, notificationId, taskid)
    }

    fun showFailedNotification(
        content: String,
        notificationId: Int,
        taskid: Long
    ) {
        val i = Intent(mContext, SyncRestartAction::class.java)
        i.putExtra(EXTRA_TASK_ID, taskid)
        FLog.e(TAG, "showFailedNotification build: notificationId=%d taskId=%d channel=%s", notificationId, taskid, CHANNEL_FAIL_ID)

        val retryPendingIntent = PendingIntent.getService(mContext, taskid.toInt(), i, GenericSyncNotification.getFlags())
        val contentIntent = createSyncLogIntent(notificationId)
        val builder = NotificationCompat.Builder(mContext, CHANNEL_FAIL_ID)
            .setSmallIcon(R.drawable.ic_twotone_cloud_error_24)
            .setContentTitle(mContext.getString(R.string.operation_failed))
            .setContentText(content)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(content)
            )
            .setGroup(OPERATION_FAILED_GROUP)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_refresh,
                mContext.getString(R.string.retry_failed_sync),
                retryPendingIntent
            )
        NotificationUtils.createNotification(mContext, notificationId, builder.build())
    }
    fun showCancelledNotificationOrReport(
        content: String,
        notificationId: Int,
        taskid: Long) {

        showCancelledNotification(content, notificationId, taskid)
    }

    fun showCancelledNotification(
        content: String,
        notificationId: Int,
        taskid: Long
    ) {
        val i = Intent(mContext, SyncRestartAction::class.java)
        i.putExtra(EXTRA_TASK_ID, taskid)

        val retryPendingIntent = PendingIntent.getService(mContext, taskid.toInt(), i, GenericSyncNotification.getFlags())
        val contentIntent = createSyncLogIntent(notificationId)
        val builder = NotificationCompat.Builder(mContext, CHANNEL_FAIL_ID)
            .setSmallIcon(R.drawable.ic_twotone_cloud_error_24)
            .setContentTitle(mContext.getString(R.string.operation_failed_cancelled))
            .setContentText(content)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(content)
            )
            .setGroup(OPERATION_FAILED_GROUP)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_refresh,
                mContext.getString(R.string.retry_failed_sync),
                retryPendingIntent
            )
        NotificationUtils.createNotification(mContext, notificationId, builder.build())
    }

    fun showSuccessNotificationOrReport(
        title: String,
        content: String,
        notificationId: Int
    ) {
        FLog.e(TAG, "showSuccessNotificationOrReport: title=%s notificationId=%d content=%s",
            title,
            notificationId,
            content.replace("\n", " | ")
        )
        showSuccessNotification(title, content, notificationId)
    }
    fun showSuccessNotification(title: String, content: String, notificationId: Int) {
        val contentIntent = createSyncLogIntent(notificationId)
        val summary = getFirstNotificationLine(content)
        FLog.e(TAG, "showSuccessNotification build: title=%s notificationId=%d channel=%s summary=%s delayMs=%d",
            title,
            notificationId,
            CHANNEL_SUCCESS_ID,
            summary,
            RESULT_NOTIFICATION_DELAY_MS
        )
        val builder = NotificationCompat.Builder(mContext, CHANNEL_SUCCESS_ID)
            .setSmallIcon(R.drawable.ic_twotone_cloud_done_24)
            .setContentTitle(mContext.getString(R.string.operation_success, title))
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    content
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
        FLog.e(TAG, "showSuccessNotification scheduled: title=%s notificationId=%d", title, notificationId)
        Handler(Looper.getMainLooper()).postDelayed({
            FLog.e(TAG, "showSuccessNotification posting after delay: title=%s notificationId=%d", title, notificationId)
            NotificationUtils.createNotification(mContext, notificationId, builder.build())
        }, RESULT_NOTIFICATION_DELAY_MS)
    }

    private fun getFirstNotificationLine(content: String): String {
        return content.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: content
    }

    private fun createSyncLogIntent(notificationId: Int): PendingIntent {
        return PendingIntent.getActivity(
            mContext,
            notificationId,
            Intent(mContext, CurrentSyncDetailsActivity::class.java),
            GenericSyncNotification.getFlags()
        )
    }

    @Deprecated("Use with specific notification id")
    fun updateSyncNotification(
        title: String,
        content: String,
        bigTextArray: ArrayList<String>,
        percent: Int
    ): Notification? {
        return updateSyncNotification(
            title,
            content,
            bigTextArray,
            percent,
            PERSISTENT_NOTIFICATION_ID_FOR_SYNC
        )
    }

    fun updateSyncNotification(
        title: String,
        content: String,
        bigTextArray: ArrayList<String>,
        percent: Int,
        notificationId: Int
    ): Notification? {
        return updateSyncNotification(title, content, bigTextArray, percent, notificationId, false)
    }

    fun updateSyncNotification(
        title: String,
        content: String,
        bigTextArray: ArrayList<String>,
        percent: Int,
        notificationId: Int,
        paused: Boolean
    ): Notification? {
        if(content.isBlank()){
            FLog.e(TAG, "Missing notification content!")
            return null
        }
        FLog.e(TAG, "updateSyncNotification build: notificationId=%d channel=%s title=%s content=%s percent=%d paused=%s",
            notificationId,
            CHANNEL_ID,
            title,
            content,
            percent,
            paused.toString()
        )

        val builder = GenericSyncNotification(mContext).updateGenericNotification(
            title,
            content,
            R.drawable.ic_twotone_rounded_cloud_sync_24,
            bigTextArray,
            percent,
            SyncWorker::class.java,
            null,
            CHANNEL_ID
        )

        if(mCancelId != mCancelUnsetId) {
            val pauseIntent = Intent(SyncWorker.ACTION_TOGGLE_PAUSE)
            pauseIntent.setPackage(mContext.packageName)
            pauseIntent.putExtra(SyncWorker.EXTRA_NOTIFICATION_ID, notificationId)
            val pausePendingIntent = PendingIntent.getBroadcast(
                mContext,
                notificationId,
                pauseIntent,
                GenericSyncNotification.getFlags()
            )

            val intent = WorkManager.getInstance(mContext)
                .createCancelPendingIntent(mCancelId)

            builder.clearActions()
            builder.addAction(
                R.drawable.ic_round_av_timer_24,
                mContext.getString(if (paused) R.string.sync_action_resume else R.string.sync_action_pause),
                pausePendingIntent
            )
            builder.addAction(
                R.drawable.ic_cancel_download,
                mContext.getString(R.string.cancel),
                intent
            )
        }

        return builder.build()
    }

    fun cancelSyncNotification(notificationId: Int) {
        FLog.e(TAG, "cancelSyncNotification: notificationId=%d", notificationId)
        val notificationManagerCompat = NotificationManagerCompat.from(mContext)
        notificationManagerCompat.cancel(notificationId)
    }
}
