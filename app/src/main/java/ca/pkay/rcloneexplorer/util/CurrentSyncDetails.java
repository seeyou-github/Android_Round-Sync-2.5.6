package ca.pkay.rcloneexplorer.util;

import android.content.Context;

import ca.pkay.rcloneexplorer.Log2File;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

public class CurrentSyncDetails {

    private static final int MAX_LINES = 1200;
    private static final StringBuilder SESSION_LOG = new StringBuilder();
    private static final String[] DIFFERENCE_KEYWORDS = new String[]{
            "delete", "deleted", "deleting", "remove", "removed", "purge",
            "copy", "copied", "copying", "upload", "uploaded", "uploading",
            "download", "downloaded", "downloading", "transfer", "transferred",
            "move", "moved", "moving", "rename", "renamed", "update", "updated"
    };

    public static synchronized void clear(Context context) {
        SESSION_LOG.setLength(0);
        Log2File.delete(context, Log2File.SYNC_LOG_FILE_NAME);
        File legacyFile = getLegacyFile(context);
        if (legacyFile.exists()) {
            legacyFile.delete();
        }
    }

    public static synchronized void append(Context context, String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        SESSION_LOG.append(line).append(System.lineSeparator());
    }

    public static synchronized void appendRcloneLine(Context context, String line) {
        String formatted = formatRcloneDifference(line);
        if (formatted != null) {
            append(context, formatted);
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
            Log2File.write(context, Log2File.SYNC_LOG_FILE_NAME, SESSION_LOG.toString());
            SESSION_LOG.setLength(0);
        } catch (IOException e) {
            FLog.e("CurrentSyncDetails", "Could not save sync log", e);
        }
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
        if (text.contains("unchanged") || text.contains("same size") || text.contains("no need to transfer")) {
            return false;
        }
        for (String keyword : DIFFERENCE_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
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
}
