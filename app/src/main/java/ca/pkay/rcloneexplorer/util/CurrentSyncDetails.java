package ca.pkay.rcloneexplorer.util;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

public class CurrentSyncDetails {

    private static final String FILE_NAME = "current_sync_details.log";
    private static final int MAX_LINES = 1200;
    private static final String[] DIFFERENCE_KEYWORDS = new String[]{
            "delete", "deleted", "deleting", "remove", "removed", "purge",
            "copy", "copied", "copying", "upload", "uploaded", "uploading",
            "download", "downloaded", "downloading", "transfer", "transferred",
            "move", "moved", "moving", "rename", "renamed", "update", "updated"
    };

    public static synchronized void clear(Context context) {
        File file = getFile(context);
        if (file.exists()) {
            file.delete();
        }
    }

    public static synchronized void append(Context context, String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        try (FileWriter writer = new FileWriter(getFile(context), true)) {
            writer.append(line);
            writer.append(System.lineSeparator());
        } catch (IOException e) {
            FLog.e("CurrentSyncDetails", "Could not append sync details", e);
        }
    }

    public static synchronized void appendRcloneLine(Context context, String line) {
        String formatted = formatRcloneDifference(line);
        if (formatted != null) {
            append(context, formatted);
        }
    }

    public static synchronized String read(Context context) {
        File file = getFile(context);
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
            FLog.e("CurrentSyncDetails", "Could not read sync details", e);
        }
        return output.toString();
    }

    public static synchronized ArrayList<String> readLines(Context context) {
        File file = getFile(context);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        ArrayDeque<String> tail = new ArrayDeque<>(MAX_LINES);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (tail.size() == MAX_LINES) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        } catch (IOException e) {
            FLog.e("CurrentSyncDetails", "Could not read sync detail lines", e);
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

    private static File getFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}
