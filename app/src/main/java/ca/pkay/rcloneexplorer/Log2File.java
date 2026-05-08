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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Log2File {

    private static final String TAG = "Log2File";
    private static final String LOG_FILE_NAME = "log.txt";
    private static final String LOG_MIME_TYPE = "text/plain";
    private Context context;

    public Log2File(Context context) {
        this.context = context;
    }

    public void log(String message) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentDateTime = dateFormat.format(new Date());

        String logMessage = currentDateTime + " - " + message + "\n";

        Uri logLocation = getConfiguredLogLocation(context);
        if (logLocation != null) {
            new WriteToUri(context.getApplicationContext(), logLocation, logMessage).execute();
            return;
        }

        File path = context.getExternalFilesDir("logs");
        File logFile = new File(path, LOG_FILE_NAME);
        clearLogsIfTooBif(logFile);
        new WriteToFile(logFile, logMessage).execute();
    }

    private void clearLogsIfTooBif(File logFile) {
        int fileSize = Integer.parseInt(String.valueOf(logFile.length() / 1024));
        if (fileSize > 10000000) { // 10 MB
            logFile.delete();
        }
    }

    private static class WriteToFile extends AsyncTask<Void, Void, Void> {

        private File logFile;
        private String logMessage;

        WriteToFile(File logFile, String logMessage) {
            this.logFile = logFile;
            this.logMessage = logMessage;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            try {
                FileOutputStream stream = new FileOutputStream(logFile, true);
                stream.write(logMessage.getBytes());
                stream.close();
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
            writeToTree(context, treeUri, "Round Sync log location test\n");
            return true;
        } catch (IOException | RuntimeException e) {
            FLog.e(TAG, "Could not test log file location", e);
            return false;
        }
    }

    private static class WriteToUri extends AsyncTask<Void, Void, Void> {

        private final Context context;
        private final Uri treeUri;
        private final String logMessage;

        WriteToUri(Context context, Uri treeUri, String logMessage) {
            this.context = context;
            this.treeUri = treeUri;
            this.logMessage = logMessage;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                writeToTree(context, treeUri, logMessage);
            } catch (IOException | RuntimeException e) {
                FLog.e(TAG, "Could not write log file", e);
            }
            return null;
        }
    }

    private static void writeToTree(Context context, Uri treeUri, String logMessage) throws IOException {
        Uri logUri = findOrCreateLogFile(context, treeUri);
        try (OutputStream stream = context.getContentResolver().openOutputStream(logUri, "wa")) {
            if (stream == null) {
                throw new IOException("Could not open log file");
            }
            stream.write(logMessage.getBytes());
        }
    }

    private static Uri findOrCreateLogFile(Context context, Uri treeUri) throws IOException {
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
        );
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
                    if (LOG_FILE_NAME.equals(cursor.getString(nameIndex))) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex));
                    }
                }
            }
        }
        Uri created = DocumentsContract.createDocument(resolver, parentUri, LOG_MIME_TYPE, LOG_FILE_NAME);
        if (created == null) {
            throw new IOException("Could not create log file");
        }
        return created;
    }
}
