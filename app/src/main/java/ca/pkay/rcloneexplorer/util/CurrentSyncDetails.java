package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.text.format.Formatter;

import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.Log2File;
import ca.pkay.rcloneexplorer.R;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

public class CurrentSyncDetails {

    private static final int MAX_LINES = 1200;
    private static final StringBuilder SESSION_LOG = new StringBuilder();
    private static final LinkedHashMap<String, TaskLog> TASK_LOGS = new LinkedHashMap<>();
    private static int activeTasks = 0;
    private static final String[] DIFFERENCE_KEYWORDS = new String[]{
            "delete", "deleted", "deleting", "remove", "removed", "purge",
            "copy", "copied", "copying", "upload", "uploaded", "uploading",
            "download", "downloaded", "downloading", "transfer", "transferred",
            "move", "moved", "moving", "rename", "renamed", "update", "updated"
    };

    public static synchronized void clear(Context context) {
        SESSION_LOG.setLength(0);
        TASK_LOGS.clear();
        activeTasks = 0;
        Log2File.delete(context, Log2File.SYNC_LOG_FILE_NAME);
        File legacyFile = getLegacyFile(context);
        if (legacyFile.exists()) {
            legacyFile.delete();
        }
    }

    public static synchronized void startTask(Context context, String taskName, int direction, String localRoot) {
        if (activeTasks == 0) {
            SESSION_LOG.setLength(0);
            TASK_LOGS.clear();
            Log2File.delete(context, Log2File.SYNC_LOG_FILE_NAME);
        }
        activeTasks++;
        getTaskLog(taskName, direction, localRoot);
        appendTaskLine(context, taskName, context.getString(R.string.current_sync_details_started));
    }

    public static synchronized void append(Context context, String line) {
        if (line == null) {
            return;
        }
        SESSION_LOG.append(line).append(System.lineSeparator());
    }

    public static synchronized void appendTaskLine(Context context, String taskName, String line) {
        String formattedLine = context.getString(R.string.sync_log_task_prefix, taskName) + "  " + line;
        append(context, formattedLine);
        TaskLog taskLog = TASK_LOGS.get(taskName);
        if (taskLog != null) {
            taskLog.lines.add(formattedLine);
        }
    }

    private static void appendSummaryLine(Context context, TaskLog taskLog, String line) {
        append(context, line);
        taskLog.lines.add(line);
    }

    public static synchronized void appendRcloneLine(Context context, String taskName, int direction, String localRoot, String line) {
        TaskLog taskLog = getTaskLog(taskName, direction, localRoot);
        collectRcloneSummaryData(context, taskLog, line);
        String formatted = formatRcloneDifference(line);
        if (formatted != null) {
            appendTaskLine(context, taskName, formatted);
        }
    }

    public static synchronized void finishTask(Context context, String taskName, int direction, String localRoot) {
        TaskLog taskLog = getTaskLog(taskName, direction, localRoot);
        appendTaskSummary(context, taskLog);
        if (activeTasks > 0) {
            activeTasks--;
        }
        if (activeTasks == 0) {
            save(context);
        }
    }

    public static synchronized String read(Context context) {
        if (SESSION_LOG.length() > 0) {
            return SESSION_LOG.toString();
        }
        String output = Log2File.read(context, Log2File.SYNC_LOG_FILE_NAME);
        if (output.isEmpty() && Log2File.getConfiguredLogLocation(context) == null) {
            output = readLegacy(context);
        }
        return output;
    }

    public static synchronized void save(Context context) {
        if (SESSION_LOG.length() == 0) {
            return;
        }
        try {
            Log2File.write(context, Log2File.SYNC_LOG_FILE_NAME, getPersistedLogContent());
            SESSION_LOG.setLength(0);
        } catch (IOException e) {
            FLog.e("CurrentSyncDetails", "Could not save sync log", e);
        }
    }

    public static synchronized String getNotificationSummary(Context context, String taskName) {
        TaskLog taskLog = TASK_LOGS.get(taskName);
        if (taskLog == null) {
            return context.getString(R.string.sync_log_notification_no_work);
        }
        ArrayList<String> parts = new ArrayList<>();
        parts.add(context.getString(R.string.sync_log_task_prefix, taskName));
        if (taskLog.uploads.size() > 0) {
            parts.add(context.getString(R.string.sync_log_notification_upload_count, taskLog.uploads.size()));
        }
        if (taskLog.downloads.size() > 0) {
            parts.add(context.getString(R.string.sync_log_notification_download_count, taskLog.downloads.size()));
        }
        if (taskLog.remoteDeletes.size() > 0) {
            parts.add(context.getString(R.string.sync_log_notification_remote_delete_count, taskLog.remoteDeletes.size()));
        }
        if (taskLog.localDeletes.size() > 0) {
            parts.add(context.getString(R.string.sync_log_notification_local_delete_count, taskLog.localDeletes.size()));
        }
        if (parts.size() == 1) {
            parts.add(context.getString(R.string.sync_log_notification_no_work));
        }
        return joinLines(parts);
    }

    public static synchronized ArrayList<String> readLines(Context context) {
        String content = read(context);
        if (content.isEmpty()) {
            return new ArrayList<>();
        }
        ArrayDeque<String> tail = new ArrayDeque<>(MAX_LINES);
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (tail.size() == MAX_LINES) {
                tail.removeFirst();
            }
            tail.addLast(line);
        }
        return new ArrayList<>(tail);
    }

    private static String formatRcloneDifference(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(line);
            if (json.has("stats")) {
                return null;
            }
            String message = json.optString("msg", "");
            String object = json.optString("object", "");
            if (!isDifferenceLine(message, object)) {
                return null;
            }
            StringBuilder formatted = new StringBuilder();
            String time = json.optString("time", "");
            if (!time.isEmpty()) {
                formatted.append(time).append("  ");
            }
            if (!object.isEmpty()) {
                formatted.append(object).append("  ");
            }
            formatted.append(message);
            return formatted.toString().trim();
        } catch (JSONException e) {
            return isDifferenceLine(line, "") ? line : null;
        }
    }

    private static boolean isDifferenceLine(String message, String object) {
        String text = (message + " " + object).toLowerCase(Locale.US);
        if (text.contains("unchanged")
                || text.contains("same size")
                || text.contains("sizes identical")
                || text.contains("size =")
                || text.contains("starting with parameters")
                || text.contains("waiting for transfers")
                || text.contains("there was nothing to transfer")
                || text.contains("no need to transfer")) {
            return false;
        }
        for (String keyword : DIFFERENCE_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static void collectRcloneSummaryData(Context context, TaskLog taskLog, String line) {
        try {
            JSONObject json = new JSONObject(line);
            if (json.has("stats")) {
                collectTransferSizes(context, taskLog, json.getJSONObject("stats"));
                return;
            }
            String message = json.optString("msg", "");
            String object = json.optString("object", "");
            if (object.isEmpty()) {
                return;
            }
            String lower = message.toLowerCase(Locale.US);
            if (lower.contains("deleted")) {
                FileChange deleted = new FileChange(resolveLocalPath(taskLog.localRoot, object), "", "");
                if (taskLog.direction == SyncDirectionObject.SYNC_LOCAL_TO_REMOTE) {
                    addUnique(taskLog.remoteDeletes, deleted);
                } else if (taskLog.direction == SyncDirectionObject.SYNC_REMOTE_TO_LOCAL) {
                    addUnique(taskLog.localDeletes, deleted);
                }
                return;
            }
            if (lower.contains("copied") || lower.contains("uploaded") || lower.contains("downloaded")) {
                String changeType = lower.contains("new")
                        ? context.getString(R.string.sync_log_change_new)
                        : context.getString(R.string.sync_log_change_modified);
                FileChange change = new FileChange(
                        resolveLocalPath(taskLog.localRoot, object),
                        taskLog.sizeByObject.containsKey(object) ? taskLog.sizeByObject.get(object) : "",
                        changeType
                );
                if (taskLog.direction == SyncDirectionObject.SYNC_LOCAL_TO_REMOTE
                        || taskLog.direction == SyncDirectionObject.COPY_LOCAL_TO_REMOTE) {
                    addUnique(taskLog.uploads, change);
                } else if (taskLog.direction == SyncDirectionObject.SYNC_REMOTE_TO_LOCAL
                        || taskLog.direction == SyncDirectionObject.COPY_REMOTE_TO_LOCAL) {
                    addUnique(taskLog.downloads, change);
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private static void collectTransferSizes(Context context, TaskLog taskLog, JSONObject stats) {
        JSONArray transferring = stats.optJSONArray("transferring");
        if (transferring == null) {
            return;
        }
        for (int i = 0; i < transferring.length(); i++) {
            JSONObject transfer = transferring.optJSONObject(i);
            if (transfer == null) {
                continue;
            }
            String name = transfer.optString("name", "");
            if (!name.isEmpty()) {
                taskLog.sizeByObject.put(name, Formatter.formatFileSize(context, transfer.optLong("size", 0)));
            }
        }
    }

    private static void appendTaskSummary(Context context, TaskLog taskLog) {
        appendSummaryLine(context, taskLog, "");
        appendSummaryLine(context, taskLog, getSummaryHeader(context, taskLog));
        if (!taskLog.uploads.isEmpty()) {
            appendSummaryLine(context, taskLog, "");
            appendSummaryLine(context, taskLog, context.getString(R.string.sync_log_upload_list));
            appendChanges(context, taskLog, taskLog.uploads, true);
        }
        if (!taskLog.downloads.isEmpty()) {
            appendSummaryLine(context, taskLog, "");
            appendSummaryLine(context, taskLog, context.getString(R.string.sync_log_download_list));
            appendChanges(context, taskLog, taskLog.downloads, true);
        }
        if (!taskLog.remoteDeletes.isEmpty()) {
            appendSummaryLine(context, taskLog, "");
            appendSummaryLine(context, taskLog, context.getString(R.string.sync_log_remote_delete_list));
            appendChanges(context, taskLog, taskLog.remoteDeletes, false);
        }
        if (!taskLog.localDeletes.isEmpty()) {
            appendSummaryLine(context, taskLog, "");
            appendSummaryLine(context, taskLog, context.getString(R.string.sync_log_local_delete_list));
            appendChanges(context, taskLog, taskLog.localDeletes, false);
        }
    }

    private static void appendChanges(Context context, TaskLog taskLog, ArrayList<FileChange> changes, boolean includeSizeAndType) {
        for (FileChange change : changes) {
            if (includeSizeAndType) {
                appendSummaryLine(context, taskLog, (change.path + "   " + change.size + "   " + change.changeType).trim());
            } else {
                appendSummaryLine(context, taskLog, change.path);
            }
        }
    }

    private static String getSummaryHeader(Context context, TaskLog taskLog) {
        switch (taskLog.direction) {
            case SyncDirectionObject.SYNC_LOCAL_TO_REMOTE:
                return context.getString(R.string.sync_log_summary_sync_to_remote, taskLog.taskName);
            case SyncDirectionObject.SYNC_REMOTE_TO_LOCAL:
                return context.getString(R.string.sync_log_summary_sync_to_local, taskLog.taskName);
            case SyncDirectionObject.COPY_LOCAL_TO_REMOTE:
                return context.getString(R.string.sync_log_summary_copy_to_remote, taskLog.taskName);
            case SyncDirectionObject.COPY_REMOTE_TO_LOCAL:
                return context.getString(R.string.sync_log_summary_copy_to_local, taskLog.taskName);
            default:
                return context.getString(R.string.sync_log_task_prefix, taskLog.taskName);
        }
    }

    private static TaskLog getTaskLog(String taskName, int direction, String localRoot) {
        TaskLog taskLog = TASK_LOGS.get(taskName);
        if (taskLog == null) {
            taskLog = new TaskLog(taskName, direction, localRoot);
            TASK_LOGS.put(taskName, taskLog);
        }
        return taskLog;
    }

    private static String resolveLocalPath(String localRoot, String object) {
        if (localRoot == null || localRoot.isEmpty()) {
            return object;
        }
        if (object == null || object.isEmpty()) {
            return localRoot;
        }
        if (localRoot.endsWith("/")) {
            return localRoot + object;
        }
        return localRoot + "/" + object;
    }

    private static void addUnique(ArrayList<FileChange> changes, FileChange change) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).path.equals(change.path)) {
                changes.set(i, change);
                return;
            }
        }
        changes.add(change);
    }

    private static String joinLines(ArrayList<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static String getPersistedLogContent() {
        if (TASK_LOGS.isEmpty()) {
            return SESSION_LOG.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (TaskLog taskLog : TASK_LOGS.values()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            for (String line : taskLog.lines) {
                builder.append(line).append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private static String readLegacy(Context context) {
        File file = getLegacyFile(context);
        if (!file.exists()) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            for (int read; (read = reader.read(buffer, 0, buffer.length)) > 0; ) {
                output.append(buffer, 0, read);
            }
        } catch (IOException e) {
            FLog.e("CurrentSyncDetails", "Could not read legacy sync log", e);
        }
        return output.toString();
    }

    private static File getLegacyFile(Context context) {
        return new File(context.getFilesDir(), "current_sync_details.log");
    }

    private static class TaskLog {
        final String taskName;
        final int direction;
        final String localRoot;
        final ArrayList<FileChange> uploads = new ArrayList<>();
        final ArrayList<FileChange> downloads = new ArrayList<>();
        final ArrayList<FileChange> remoteDeletes = new ArrayList<>();
        final ArrayList<FileChange> localDeletes = new ArrayList<>();
        final ArrayList<String> lines = new ArrayList<>();
        final Map<String, String> sizeByObject = new LinkedHashMap<>();

        TaskLog(String taskName, int direction, String localRoot) {
            this.taskName = taskName;
            this.direction = direction;
            this.localRoot = localRoot == null ? "" : localRoot;
        }
    }

    private static class FileChange {
        final String path;
        final String size;
        final String changeType;

        FileChange(String path, String size, String changeType) {
            this.path = path == null ? "" : path;
            this.size = size == null ? "" : size;
            this.changeType = changeType == null ? "" : changeType;
        }
    }
}
