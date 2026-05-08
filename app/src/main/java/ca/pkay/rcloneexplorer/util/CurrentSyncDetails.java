package ca.pkay.rcloneexplorer.util;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public class CurrentSyncDetails {

    private static final String FILE_NAME = "current_sync_details.log";

    public static synchronized void clear(Context context) {
        File file = getFile(context);
        if (file.exists()) {
            file.delete();
        }
    }

    public static synchronized void append(Context context, String line) {
        try (FileWriter writer = new FileWriter(getFile(context), true)) {
            writer.append(line);
            writer.append(System.lineSeparator());
        } catch (IOException e) {
            FLog.e("CurrentSyncDetails", "Could not append sync details", e);
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

    private static File getFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}
