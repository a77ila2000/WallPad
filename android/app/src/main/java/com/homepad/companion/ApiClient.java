package com.homepad.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ApiClient {
    private final String baseUrl;
    private final String token;

    ApiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    JSONObject getJson(String path) throws Exception {
        return getJson(path, 12000);
    }

    JSONObject getJson(String path, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = open(path, "GET");
        connection.setReadTimeout(readTimeoutMs);
        return new JSONObject(readResponse(connection));
    }

    JSONObject waitForEvent(long after) throws Exception {
        HttpURLConnection connection = open("/api/events?after=" + after, "GET");
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Accept", "text/event-stream");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException(errorMessage(connection, status));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) return new JSONObject(line.substring(5).trim());
            }
        } finally {
            connection.disconnect();
        }
        throw new IOException("이벤트 응답을 읽지 못했습니다");
    }

    JSONObject postJson(String path, JSONObject body) throws Exception {
        // Includes the server's bounded link probe and lost-reply receipt check.
        return postJson(path, body, "/api/elevator/call".equals(path) ? 18000 : 12000);
    }

    JSONObject postJson(String path, JSONObject body, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = open(path, "POST");
        connection.setReadTimeout(readTimeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            return new JSONObject(readResponse(connection));
        } finally {
            // Also release the socket when connecting/writing fails before a reply.
            connection.disconnect();
        }
    }

    Bitmap getBitmap(String path) throws Exception {
        HttpURLConnection connection = open(path, "GET");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException(errorMessage(connection, status));
        }
        try (InputStream input = connection.getInputStream()) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new IOException("영상 이미지를 읽지 못했습니다");
            return bitmap;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json, image/jpeg");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException(errorMessage(connection, status));
        try (InputStream input = connection.getInputStream()) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private String errorMessage(HttpURLConnection connection, int status) throws IOException {
        InputStream error = connection.getErrorStream();
        String detail = error == null ? "" : new String(readAll(error), StandardCharsets.UTF_8);
        connection.disconnect();
        if (!detail.isEmpty()) {
            try {
                JSONObject value = new JSONObject(detail);
                String message = value.optString("error", value.optString("message", ""));
                if ("call cooldown active".equals(message)) {
                    int seconds = value.optInt("retryAfterSeconds", 0);
                    return seconds > 0 ? "방금 호출했습니다. " + seconds + "초 후 다시 눌러 주세요."
                            : "방금 호출했습니다. 잠시 후 다시 눌러 주세요.";
                }
                if (!message.isEmpty()) return message;
            } catch (Exception ignored) { }
        }
        if (status == 401) return "서버 연결 인증이 필요합니다. 설정에서 연결 정보를 확인해 주세요";
        if (detail.trim().startsWith("<")) return "서버에 연결하지 못했습니다. 잠시 후 다시 확인해 주세요";
        return detail.isEmpty() ? "서버 응답 오류 " + status : detail;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
