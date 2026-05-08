package ca.pkay.rcloneexplorer;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.DocumentsContract;

import androidx.preference.PreferenceManager;

import ca.pkay.rcloneexplorer.util.FLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Log2File {

    private static final String TAG = "Log2File";
    private static final String LOG_FILE_NAME = "log.txt";
    private static final String LOG_MIME_TYPE = "text/plain";
    private final Context context;

    public Log2File(Context context) {
        this.context = context;
    }

    public void log(String message) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentDateTime = dateFormat.format(new Date());
        new WriteLogMessage(context.getApplicationContext(), LOG_FILE_NAME, currentDateTime + " - " + message + "\n").execute();
    }

    private static class WriteLogMessage extends AsyncTask<Void, Void, Void> {

        private final Context context;
        private final String fileName;
        private final String logMessage;

        WriteLogMessage(Context context, String fileName, String logMessage) {
            this.context = context;
            this.fileName = fileName;
            this.logMessage = logMessage;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                append(context, fileName, logMessage);
            } catch (IOException e) {
                FLog.e(TAG, "Could not write log file", e);
            }
            return null;
        }
    }

    public static Uri getConfiguredLogLocation(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String uriString = preferences.getString(context.getString(R.string.pref_key_log_location_uri), "");
        if (uriString == null || uriString.isEmpty()) {
            return null;
        }
        return Uri.parse(uriString);
    }

    public static boolean testLogLocation(Context context, Uri treeUri) {
        try {
            writeToTree(context, treeUri, LOG_FILE_NAME, "Round Sync log location test\n");
            return true;
        } catch (IOException | RuntimeException e) {
            FLog.e(TAG, "Could not test log file location", e);
            return false;
        }
    }

    public static synchronized void append(Context context, String fileName, String logMessage) throws IOException {
        Uri logLocation = getConfiguredLogLocation(context);
        if (logLocation != null) {
            writeToTree(context, logLocation, fileName, logMessage);
            return;
        }

        File logFile = getPrivateLogFile(context, fileName);
        int fileSize = Integer.parseInt(String.valueOf(logFile.length() / 1024));
        if (fileSize > 10000000) {
            logFile.delete();
        }
        try (FileOutputStream stream = new FileOutputStream(logFile, true)) {
            stream.write(logMessage.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static synchronized String read(Context context, String fileName) {
        Uri logLocation = getConfiguredLogLocation(context);
        if (logLocation != null) {
            try {
                Uri fileUri = findLogFile(context, logLocation, fileName);
                if (fileUri == null) {
                    return "";
                }
                try (InputStream stream = context.getContentResolver().openInputStream(fileUri)) {
                    return readStream(stream);
                }
            } catch (IOException | RuntimeException e) {
                FLog.e(TAG, "Could not read configured log file", e);
                return "";
            }
        }

        File logFile = getPrivateLogFile(context, fileName);
        if (!logFile.exists()) {
            return "";
        }
        try (InputStream stream = new FileInputStream(logFile)) {
            return readStream(stream);
        } catch (IOException e) {
            FLog.e(TAG, "Could not read private log file", e);
            return "";
        }
    }

    public static synchronized void delete(Context context, String fileName) {
        Uri logLocation = getConfiguredLogLocation(context);
        if (logLocation != null) {
            try {
                Uri fileUri = findLogFile(context, logLocation, fileName);
                if (fileUri != null) {
                    DocumentsContract.deleteDocument(context.getContentResolver(), fileUri);
                }
                return;
            } catch (IOException | RuntimeException e) {
                FLog.e(TAG, "Could not delete configured log file", e);
            }
        }

        File logFile = getPrivateLogFile(context, fileName);
        if (logFile.exists()) {
            logFile.delete();
        }
    }

    public static void deleteErrorLog(Context context) {
        delete(context, LOG_FILE_NAME);
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            for (int read; (read = reader.read(buffer, 0, buffer.length)) > 0; ) {
                output.append(buffer, 0, read);
            }
        }
        return output.toString();
    }

    private static File getPrivateLogFile(Context context, String fileName) {
        File path = context.getExternalFilesDir("logs");
        if (path == null) {
            path = context.getFilesDir();
        }
        if (!path.exists()) {
            path.mkdirs();
        }
        return new File(path, fileName);
    }

    private static void writeToTree(Context context, Uri treeUri, String fileName, String logMessage) throws IOException {
        Uri logUri = findOrCreateLogFile(context, treeUri, fileName);
        try (OutputStream stream = context.getContentResolver().openOutputStream(logUri, "wa")) {
            if (stream == null) {
                throw new IOException("Could not open log file");
            }
            stream.write(logMessage.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Uri findOrCreateLogFile(Context context, Uri treeUri, String fileName) throws IOException {
        Uri existing = findLogFile(context, treeUri, fileName);
        if (existing != null) {
            return existing;
        }
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
        );
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parentUri, LOG_MIME_TYPE, fileName);
        if (created == null) {
            throw new IOException("Could not create log file");
        }
        return created;
    }

    private static Uri findLogFile(Context context, Uri treeUri, String fileName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
        );
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    if (fileName.equals(cursor.getString(nameIndex))) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex));
                    }
                }
            }
        }
        return null;
    }
}
