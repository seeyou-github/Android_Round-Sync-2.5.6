package ca.pkay.rcloneexplorer.util;

import android.content.Context;

import ca.pkay.rcloneexplorer.Log2File;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Copyright (C) 2021  Felix Nüsse
 * Created on 20.06.21 - 15:30
 *
 * Edited by: Felix Nüsse felix.nuesse(at)t-online.de
 *
 */

public class SyncLog {

    private static final String LOG_FILE_NAME = "sync.log";
    private static int loglength = 4;

    public static String TIMESTAMP = "timestamp";
    public static String TITLE = "title";
    public static String CONTENT = "content";
    public static String TYPE = "type";
    public static final int TYPE_ERROR = 0;
    public static final int TYPE_INFO = 1;

    public static ArrayList<JSONObject> getLog(Context c){
        String file = Log2File.read(c, LOG_FILE_NAME);
        if (file.isEmpty() && Log2File.getConfiguredLogLocation(c) == null) {
            file = readLegacyPrivateLog(c);
        }

        String lines[] = file.split("\\r?\\n");

        ArrayList<JSONObject> jsons = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            try {
                jsons.add(new JSONObject(lines[i]));
            } catch (JSONException e) {
            }
        }
        Collections.reverse(jsons);
        return jsons;
    }

    private static void appendLog(Context c, String entry){
        try {
            Log2File.append(c, LOG_FILE_NAME, entry + System.lineSeparator());
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public static long log(Context c, String title, String content, int type){
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            json.put(TIMESTAMP, now);
            json.put(CONTENT, content);
            json.put(TITLE, title);
            json.put(TYPE, type);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        appendLog(c, json.toString());
        return now;
    }

    public static long error(Context c, String title, String content){
        return log(c, title, content, TYPE_ERROR);
    }

    public static long info(Context c, String title, String content){
        return log(c, title, content, TYPE_INFO);
    }

    public static void delete(Context c){
        Log2File.delete(c, LOG_FILE_NAME);
        File legacyLog = new File(c.getFilesDir(), LOG_FILE_NAME);
        if (legacyLog.exists() && !legacyLog.delete()) {
            System.out.println("legacy sync log not Deleted");
        }
    }

    private static String readLegacyPrivateLog(Context c) {
        File log = new File(c.getFilesDir(), LOG_FILE_NAME);
        if (!log.exists()) {
            return "";
        }
        StringBuilder file = new StringBuilder();
        try {
            char[] buffer = new char[4096];
            InputStream inputStream = new FileInputStream(log);
            Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
                file.append(buffer, 0, numRead);
            }
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file.toString();
    }

}
