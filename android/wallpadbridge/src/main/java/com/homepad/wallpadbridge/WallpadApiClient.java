package com.homepad.wallpadbridge;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/** Shared authenticated HTTP client for wallpad-to-server traffic. */
final class WallpadApiClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_JSON_RESPONSE_BYTES = 1024 * 1024;

    private final Context context;
    private final int connectTimeout;
    private final int readTimeout;
    private final boolean keepAlive;

    WallpadApiClient(Context context, int connectTimeout, int readTimeout) {
        this(context, connectTimeout, readTimeout, false);
    }

    WallpadApiClient(Context context, int connectTimeout, int readTimeout, boolean keepAlive) {
        this.context = context.getApplicationContext();
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.keepAlive = keepAlive;
    }

    JSONObject getJson(String path) throws Exception {
        return response(open(path, "GET"));
    }

    JSONObject postJson(String path, JSONObject body) throws Exception {
        return postBytes(path, "application/json; charset=utf-8",
                body.toString().getBytes(UTF8), Collections.<String, String>emptyMap());
    }

    JSONObject postBytes(String path, String contentType, byte[] body) throws Exception {
        return postBytes(path, contentType, body, Collections.<String, String>emptyMap());
    }

    JSONObject postBytes(String path, String contentType, byte[] body,
                         Map<String, String> headers) throws Exception {
        HttpsURLConnection connection = open(path, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null && header.getValue().length() > 0) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(body);
        } finally {
            output.close();
        }
        return response(connection);
    }

    JSONObject postFile(String path, String contentType, File file,
                        Map<String, String> headers) throws Exception {
        if (file.length() > Integer.MAX_VALUE) throw new IllegalArgumentException("file is too large");
        HttpsURLConnection connection = open(path, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null && header.getValue().length() > 0) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        connection.setFixedLengthStreamingMode((int) file.length());
        OutputStream output = connection.getOutputStream();
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        } finally {
            input.close();
            output.close();
        }
        return response(connection);
    }

    private HttpsURLConnection open(String path, String method) throws Exception {
        if (BuildConfig.INGEST_TOKEN.length() == 0) {
            throw new IllegalStateException("Missing ingest token at build time");
        }
        HttpsURLConnection connection = LegacyHttps.open(
                context, BuildConfig.SERVER_URL + path, method, connectTimeout, readTimeout);
        connection.setRequestProperty("Connection", keepAlive ? "keep-alive" : "close");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Homepad-Ingest", BuildConfig.INGEST_TOKEN);
        return connection;
    }

    private static JSONObject response(HttpsURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String text = stream == null ? "" : read(stream);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Server HTTP " + status + " " + text);
            }
            return text.length() == 0 ? new JSONObject() : new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8));
        try {
            StringBuilder value = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                value.append(line).append('\n');
                if (value.length() > MAX_JSON_RESPONSE_BYTES) {
                    throw new IllegalStateException("Server response is too large");
                }
            }
            return value.toString();
        } finally {
            reader.close();
        }
    }
}
