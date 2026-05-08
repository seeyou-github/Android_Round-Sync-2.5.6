package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process as AndroidProcess
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.GenericSyncNotification
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications.Companion.GROUP_ID
import ca.pkay.rcloneexplorer.notifications.support.StatusObject
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.CurrentSyncDetails
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import kotlinx.serialization.json.Json
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException

class SyncWorker (private var mContext: Context, workerParams: WorkerParameters): Worker(mContext, workerParams) {

    companion object {
        const val TASK_ID = "TASK_ID"
        const val TASK_EPHEMERAL = "TASK_EPHEMERAL"
        private const val TAG = "SyncWorker"

        //those Extras do not follow the above schema, because they are exposed to external applications
        //That means shorter values make it easier to use. There is no other technical reason
        const val TASK_SYNC_ACTION = "START_TASK"
        const val TASK_CANCEL_ACTION = "CANCEL_TASK"
        const val EXTRA_TASK_ID = "task"
        const val ACTION_TOGGLE_PAUSE = "ca.pkay.rcloneexplorer.action.TOGGLE_SYNC_PAUSE"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        // Todo: Allow SyncWorker to run in silent mode, or remove this!
        const val EXTRA_TASK_SILENT = "notification"
    }



    internal enum class FAILURE_REASON {
        NO_FAILURE, NO_UNMETERED, NO_CONNECTION, RCLONE_ERROR, CONNECTIVITY_CHANGED, CANCELLED, NO_TASK
    }

    // Objects
    private var mRclone = Rclone(mContext)
    private var mDatabase = DatabaseHandler(mContext)
    private var mNotificationManager = SyncServiceNotifications(mContext)
    // States
    private var sConnectivityChanged = false

    private var sRcloneProcess: java.lang.Process? = null
    private val statusObject = StatusObject(mContext)
    private var failureReason = FAILURE_REASON.NO_FAILURE
    private var endNotificationAlreadyPosted = false
    private var silentRun = false
    private var isPaused = false
    private var syncLogFinished = false
    private val notificationBaseId = stableNotificationBaseId()
    private val ongoingNotificationID = notificationBaseId
    private val resultNotificationID = notificationBaseId xor 0x40000000


    // Task
    private lateinit var mTask: Task
    private var mTitle: String = mContext.getString(R.string.sync_service_notification_startingsync)



    override fun doWork(): Result {
        FLog.e(TAG, "doWork start: workId=%s ongoingNotificationId=%d resultNotificationId=%d inputKeys=%s",
            id.toString(),
            ongoingNotificationID,
            resultNotificationID,
            inputData.keyValueMap.keys.toString()
        )

        prepareNotifications()
        registerBroadcastReceivers()

        updateForegroundNotification(mNotificationManager.updateSyncNotification(
            mTitle,
            mTitle,
            ArrayList(),
            0,
            ongoingNotificationID,
            isPaused
        ))


        var ephemeralTask: Task? = null

        if(inputData.keyValueMap.containsKey(TASK_ID)){
            val id = inputData.getLong(TASK_ID, -1)
            ephemeralTask = mDatabase.getTask(id)
        }

        if(inputData.keyValueMap.containsKey(TASK_EPHEMERAL)){
            val taskString = inputData.getString(TASK_EPHEMERAL) ?: ""
            if(taskString.isNotEmpty()) {
                try {
                    ephemeralTask = Json.decodeFromString<Task>(taskString)
                } catch (e: Exception) {
                    log("Could not deserialize")
                }
            }
        }

        if (ephemeralTask != null) {
            mTask = ephemeralTask
            FLog.e(TAG, "doWork task resolved: taskId=%d title=%s direction=%d", mTask.id, mTask.title, mTask.direction)
            handleTask()
            FLog.e(TAG, "doWork task handled: taskId=%d failureReason=%s endNotificationAlreadyPosted=%s",
                mTask.id,
                failureReason.name,
                endNotificationAlreadyPosted.toString()
            )
            postSync()
        } else {
            FLog.e(TAG, "doWork no task resolved; posting failure notification")
            failureReason = FAILURE_REASON.NO_TASK
            postSync()
            return Result.failure()
        }

        // Indicate whether the work finished successfully with the Result
        FLog.e(TAG, "doWork end: task=%s failureReason=%s endNotificationAlreadyPosted=%s",
            mTitle,
            failureReason.name,
            endNotificationAlreadyPosted.toString()
        )
        return Result.success()
    }

    override fun onStopped() {
        super.onStopped()
        FLog.e(TAG, "onStopped: task=%s currentFailureReason=%s endNotificationAlreadyPosted=%s",
            mTitle,
            failureReason.name,
            endNotificationAlreadyPosted.toString()
        )
        failureReason = FAILURE_REASON.CANCELLED
        finishWork()
    }

    private fun finishWork() {
        FLog.e(TAG, "finishWork: task=%s processAlive=%s syncLogFinished=%s endNotificationAlreadyPosted=%s",
            mTitle,
            (sRcloneProcess != null).toString(),
            syncLogFinished.toString(),
            endNotificationAlreadyPosted.toString()
        )
        sRcloneProcess?.destroy()
        try {
            mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
            mContext.unregisterReceiver(syncActionBroadcastReceiver)
        } catch (e: IllegalArgumentException) {
            FLog.e(TAG, "Receiver already unregistered", e)
        }
        finishSyncLog()
        postSync()
    }

    private fun handleTask() {
        mTitle = mTask.title
        mNotificationManager.setCancelId(id)
        val remoteItem = RemoteItem(mTask.remoteId, mTask.remoteType, "")

        if (mTask.title == "") {
            mTitle = mTask.remotePath
        }
        FLog.e(TAG, "handleTask start: taskId=%d title=%s direction=%d localPath=%s remotePath=%s remoteId=%d",
            mTask.id,
            mTitle,
            mTask.direction,
            mTask.localPath,
            mTask.remotePath,
            mTask.remoteId
        )
        CurrentSyncDetails.startTask(mContext, mTitle, mTask.direction, mTask.localPath)
        updateForegroundNotification(mNotificationManager.updateSyncNotification(
            statusObject.getTaskTransferNotificationTitle(mTitle, mTask.direction),
            mContext.getString(R.string.current_sync_details_started),
            ArrayList(),
            0,
            ongoingNotificationID,
            isPaused
        ))
        val preconditionsMet = arePreconditionsMet()
        FLog.e(TAG, "handleTask preconditions: task=%s met=%s failureReason=%s",
            mTitle,
            preconditionsMet.toString(),
            failureReason.name
        )
        if(preconditionsMet) {
            sRcloneProcess = mRclone.sync(
                remoteItem,
                mTask.localPath,
                mTask.remotePath,
                mTask.direction,
                mTask.md5sum
            )
            FLog.e(TAG, "handleTask rclone started: task=%s processCreated=%s", mTitle, (sRcloneProcess != null).toString())
            handleSync(mTitle)
            FLog.e(TAG, "handleTask sync completed: task=%s sending upload finished broadcast", mTitle)
            sendUploadFinishedBroadcast(remoteItem.name, mTask.remotePath)
        }
    }

    private fun handleSync(title: String) {
        FLog.e(TAG, "handleSync start: task=%s hasProcess=%s", title, (sRcloneProcess != null).toString())
        if (sRcloneProcess != null) {
            val localProcessReference = sRcloneProcess!!
            try {
                val reader = BufferedReader(InputStreamReader(localProcessReference.errorStream))
                val iterator = reader.lineSequence().iterator()
                while(iterator.hasNext()) {
                    val line = iterator.next()
                    CurrentSyncDetails.appendRcloneLine(mContext, mTitle, mTask.direction, mTask.localPath, line)
                    try {
                        val logline = JSONObject(line)
                        //todo: migrate this to StatusObject, so that we can handle everything properly.
                        if (logline.getString("level") == "error") {
                            statusObject.parseLoglineToStatusObject(logline)
                        } else if (logline.getString("level") == "warning") {
                            statusObject.parseLoglineToStatusObject(logline)
                        } else if (logline.has("stats")) {
                            statusObject.parseLoglineToStatusObject(logline)
                        }

                        val notificationTitle = statusObject.getTaskTransferNotificationTitle(mTitle, mTask.direction)
                        val notificationContent = statusObject.getTaskTransferNotificationContent(mTitle, mTask.direction)
                        updateForegroundNotification(mNotificationManager.updateSyncNotification(
                            notificationTitle,
                            notificationContent,
                            statusObject.getTaskTransferNotificationLines(mTitle, mTask.direction),
                            statusObject.getTaskTransferNotificationPercent(),
                            ongoingNotificationID,
                            isPaused
                        ))
                    } catch (e: JSONException) {
                        FLog.e(TAG, "SyncService-Error: the offending line: $line")
                        //FLog.e(TAG, "onHandleIntent: error reading json", e)
                    }
                }
            } catch (e: InterruptedIOException) {
                FLog.e(TAG, "onHandleIntent: I/O interrupted, stream closed", e)
            } catch (e: IOException) {
                FLog.e(TAG, "onHandleIntent: error reading stdout", e)
            }
            try {
                localProcessReference.waitFor()
                FLog.e(TAG, "handleSync process finished: task=%s exitCode=%d", title, localProcessReference.exitValue())
                CurrentSyncDetails.appendTaskLine(mContext, mTitle, "rclone exit code: ${localProcessReference.exitValue()}")
            } catch (e: InterruptedException) {
                FLog.e(TAG, "onHandleIntent: error waiting for process", e)
            }
        } else {
            log("Sync: No Rclone Process!")
        }
        finishSyncLog()
        FLog.e(TAG, "handleSync cancel ongoing notification: task=%s ongoingNotificationId=%d", title, ongoingNotificationID)
        mNotificationManager.cancelSyncNotification(ongoingNotificationID)
    }

    private fun postSync() {
        FLog.e(TAG, "postSync enter: task=%s failureReason=%s endNotificationAlreadyPosted=%s silentRun=%s resultNotificationId=%d",
            mTitle,
            failureReason.name,
            endNotificationAlreadyPosted.toString(),
            silentRun.toString(),
            resultNotificationID
        )
        if (endNotificationAlreadyPosted) {
            FLog.e(TAG, "postSync skip: notification already posted for task=%s", mTitle)
            return
        }
        if (silentRun) {
            FLog.e(TAG, "postSync skip: silent run for task=%s", mTitle)
            return
        }

        val notificationId = resultNotificationID

        var content = mContext.getString(R.string.operation_failed_unknown, mTitle)
        when (failureReason) {
            FAILURE_REASON.NO_FAILURE -> {
                FLog.e(TAG, "postSync success branch: task=%s notificationId=%d", mTitle, notificationId)
                showSuccessNotification(notificationId)
                endNotificationAlreadyPosted = true
                return
            }
            FAILURE_REASON.CANCELLED -> {
                FLog.e(TAG, "postSync cancelled branch: task=%s notificationId=%d", mTitle, notificationId)
                showCancelledNotification(notificationId)
                endNotificationAlreadyPosted = true
                return
            }
            FAILURE_REASON.NO_TASK -> {
                content = getString(R.string.operation_failed_notask)
            }
            FAILURE_REASON.CONNECTIVITY_CHANGED -> {
                content = mContext.getString(R.string.operation_failed_data_change, mTitle)
            }
            FAILURE_REASON.NO_UNMETERED -> {
                content = mContext.getString(R.string.operation_failed_no_unmetered, mTitle)
            }
            FAILURE_REASON.NO_CONNECTION -> {
                content = mContext.getString(R.string.operation_failed_no_connection, mTitle)
            }
            FAILURE_REASON.RCLONE_ERROR -> {
                content = mContext.getString(R.string.operation_failed_unknown_rclone_error, mTitle)
            }
        }
        FLog.e(TAG, "postSync failure branch: task=%s notificationId=%d content=%s", mTitle, notificationId, content)
        showFailNotification(notificationId, content)
        endNotificationAlreadyPosted = true
        finishWork()
    }

    private fun showCancelledNotification(notificationId: Int) {
        mNotificationManager.showCancelledNotificationOrReport(
            mTitle,
            notificationId,
            mTask.id
        )
    }

    private fun showSuccessNotification(notificationId: Int) {
        val message = CurrentSyncDetails.getNotificationSummary(mContext, mTitle)
        FLog.e(TAG, "showSuccessNotification: task=%s notificationId=%d message=%s",
            mTitle,
            notificationId,
            message.replace("\n", " | ")
        )
        mNotificationManager.showSuccessNotificationOrReport(
            mTitle,
            message,
            notificationId
        )
    }

    private fun showFailNotification(notificationId: Int, content: String, wasCancelled: Boolean = false) {
        var text = content
        //Todo: check if we should also add errors on success
        statusObject.printErrors()
        val errors = statusObject.getAllErrorMessages()
        if (errors.isNotEmpty()) {
            text += """
                        
                        
                        
                        ${statusObject.getAllErrorMessages()}
                        """.trimIndent()
        }

        var notifyTitle = mContext.getString(R.string.operation_failed)
        if (wasCancelled) {
            notifyTitle = mContext.getString(R.string.operation_failed_cancelled)
        }
        mNotificationManager.showFailedNotificationOrReport(
            mTitle,
            text,
            notificationId,
            mTask.id
        )
    }

    private fun arePreconditionsMet(): Boolean {
        val connection = WifiConnectivitiyUtil.dataConnection(this.applicationContext)
        if (mTask.wifionly && connection === WifiConnectivitiyUtil.Connection.METERED) {
            failureReason = FAILURE_REASON.NO_UNMETERED
            return false
        } else if (connection === WifiConnectivitiyUtil.Connection.DISCONNECTED || connection === WifiConnectivitiyUtil.Connection.NOT_AVAILABLE) {
            failureReason = FAILURE_REASON.NO_CONNECTION
            return false
        }

        return true
    }

    private fun prepareNotifications() {

        GenericSyncNotification(mContext).setNotificationChannel(
                SyncServiceNotifications.CHANNEL_ID,
                getString(R.string.sync_service_notification_channel_title),
                getString(R.string.sync_service_notification_channel_description),
                GROUP_ID,
                getString(R.string.sync_service_notification_group)
        )
        GenericSyncNotification(mContext).setNotificationChannel(
            SyncServiceNotifications.CHANNEL_SUCCESS_ID,
            getString(R.string.sync_service_notification_channel_success_title),
            getString(R.string.sync_service_notification_channel_success_description),
                GROUP_ID,
                getString(R.string.sync_service_notification_group)
        )
        GenericSyncNotification(mContext).setNotificationChannel(
            SyncServiceNotifications.CHANNEL_FAIL_ID,
            getString(R.string.sync_service_notification_channel_fail_title),
            getString(R.string.sync_service_notification_channel_fail_description),
                GROUP_ID,
                getString(R.string.sync_service_notification_group)
        )
    }

    private fun sendUploadFinishedBroadcast(remote: String, path: String?) {
        val intent = Intent()
        intent.action = getString(R.string.background_service_broadcast)
        intent.putExtra(getString(R.string.background_service_broadcast_data_remote), remote)
        intent.putExtra(getString(R.string.background_service_broadcast_data_path), path)
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent)
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun updateForegroundNotification(notification: Notification?) {
        notification?.let {
            FLog.e(TAG, "updateForegroundNotification: task=%s ongoingNotificationId=%d", mTitle, ongoingNotificationID)
            setForegroundAsync(ForegroundInfo(ongoingNotificationID, it, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }
    }

    private fun stableNotificationBaseId(): Int {
        val value = id.hashCode() and 0x3fffffff
        return if (value == 0) SyncServiceNotifications.PERSISTENT_NOTIFICATION_ID_FOR_SYNC else value
    }


    private fun log(message: String) {
        FLog.e(TAG, "SyncWorker: $message")
    }

    private fun getString(@StringRes resId: Int): String {
        return mContext.getString(resId)
    }

    private fun registerBroadcastReceivers() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)
        mContext.registerReceiver(connectivityChangeBroadcastReceiver, intentFilter)
        val syncActionFilter = IntentFilter()
        syncActionFilter.addAction(ACTION_TOGGLE_PAUSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(syncActionBroadcastReceiver, syncActionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            mContext.registerReceiver(syncActionBroadcastReceiver, syncActionFilter)
        }
    }

    private val connectivityChangeBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(endNotificationAlreadyPosted){
                    return
                }
                sConnectivityChanged = true
                failureReason = FAILURE_REASON.CONNECTIVITY_CHANGED
            }
        }

    private val syncActionBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_NOTIFICATION_ID, ongoingNotificationID) != ongoingNotificationID) {
                    return
                }
                if (ACTION_TOGGLE_PAUSE == intent.action) {
                    togglePause()
                }
            }
        }

    private fun togglePause() {
        val process = sRcloneProcess ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            CurrentSyncDetails.appendTaskLine(mContext, mTitle, getString(R.string.sync_pause_not_supported))
            return
        }
        val processId = getProcessId(process)
        if (processId == null) {
            CurrentSyncDetails.appendTaskLine(mContext, mTitle, getString(R.string.sync_pause_not_supported))
            return
        }
        val signal = if (isPaused) 18 else 19
        AndroidProcess.sendSignal(processId, signal)
        isPaused = !isPaused
        val content = getString(if (isPaused) R.string.sync_paused else R.string.sync_resumed)
        CurrentSyncDetails.appendTaskLine(mContext, mTitle, content)
        updateForegroundNotification(mNotificationManager.updateSyncNotification(
            mTitle,
            content,
            statusObject.notificationBigText,
            statusObject.notificationPercent,
            ongoingNotificationID,
            isPaused
        ))
    }

    private fun getProcessId(process: java.lang.Process): Int? {
        try {
            val pidMethod = process.javaClass.getMethod("pid")
            return (pidMethod.invoke(process) as Long).toInt()
        } catch (e: ReflectiveOperationException) {
            FLog.e(TAG, "Could not read process pid with pid method", e)
        } catch (e: ClassCastException) {
            FLog.e(TAG, "Could not cast process pid", e)
        }
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: ReflectiveOperationException) {
            FLog.e(TAG, "Could not read process pid field", e)
            null
        }
    }

    private fun finishSyncLog() {
        if (syncLogFinished || !::mTask.isInitialized) {
            return
        }
        syncLogFinished = true
        CurrentSyncDetails.finishTask(mContext, mTitle, mTask.direction, mTask.localPath)
    }

}
