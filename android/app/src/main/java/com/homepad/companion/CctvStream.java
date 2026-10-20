package com.homepad.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Keeps one authenticated MJPEG connection open until the viewer changes channels or leaves. */
final class CctvStream implements AutoCloseable {
    interface FrameHandler {
        boolean onFrame(Bitmap frame, long sourceAgeMs);
    }

    private static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    private final String url;
    private final String token;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;

    CctvStream(String server, String token, int channel) {
        this.url = server.replaceAll("/+$", "") + "/api/cctv/channels/" + channel + "/stream";
        this.token = token == null ? "" : token.trim();
    }

    void readFrames(FrameHandler handler) throws Exception {
        if (closed) return;
        HttpURLConnection active = (HttpURLConnection) new URL(url).openConnection();
        connection = active;
        active.setConnectTimeout(8000);
        active.setReadTimeout(12000);
        active.setUseCaches(false);
        active.setRequestProperty("Accept", "multipart/x-mixed-replace");
        if (!token.isEmpty()) active.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (closed) return;
            int status = active.getResponseCode();
            if (status != 200) throw new IOException("CCTV 중계 응답 " + status);
            String type = active.getContentType();
            if (type == null || !type.toLowerCase().contains("multipart/x-mixed-replace")) {
                throw new IOException("CCTV 영상 형식이 올바르지 않습니다");
            }
            long initialFrameAgeMs = parsePositiveLong(
                    active.getHeaderField("X-Homepad-Initial-Frame-Age-Ms"));
            boolean firstFrame = true;
            try (InputStream input = new BufferedInputStream(active.getInputStream(), 65536)) {
                while (!closed) {
                    String line = readLine(input);
                    if (line == null) throw new IOException("CCTV 영상 연결이 종료됐습니다");
                    if (!line.startsWith("--frame")) continue;
                    int length = -1;
                    while (true) {
                        line = readLine(input);
                        if (line == null) throw new IOException("CCTV 영상 헤더가 끝났습니다");
                        if (line.isEmpty()) break;
                        if (line.toLowerCase().startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    if (length < 1 || length > MAX_FRAME_BYTES) {
                        throw new IOException("CCTV 영상 크기가 올바르지 않습니다");
                    }
                    byte[] bytes = new byte[length];
                    int offset = 0;
                    while (offset < bytes.length) {
                        int count = input.read(bytes, offset, bytes.length - offset);
                        if (count < 0) throw new IOException("CCTV 영상이 중간에 끊겼습니다");
                        offset += count;
                    }
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap == null) throw new IOException("CCTV 영상을 해석하지 못했습니다");
                    long sourceAgeMs = firstFrame ? initialFrameAgeMs : -1L;
                    firstFrame = false;
                    if (!handler.onFrame(bitmap, sourceAgeMs)) return;
                }
            }
        } finally {
            active.disconnect();
            if (connection == active) connection = null;
        }
    }

    private static long parsePositiveLong(String value) {
        if (value == null || value.trim().isEmpty()) return -1L;
        try { return Math.max(0L, Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) { return -1L; }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= 1024) {
            int value = input.read();
            if (value < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII.name());
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            line.write(value);
        }
        throw new IOException("CCTV 영상 헤더가 너무 깁니다");
    }

    @Override public void close() {
        closed = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }
}
