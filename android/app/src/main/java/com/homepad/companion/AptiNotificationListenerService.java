package com.homepad.companion;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AptiNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "AptiVehicleListener";
    private static final String APTI_PACKAGE = "aptip.app";
    private final ExecutorService uploads = Executors.newSingleThreadExecutor();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !APTI_PACKAGE.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        String title = value(extras, Notification.EXTRA_TITLE);
        String text = join(
                value(extras, Notification.EXTRA_TEXT),
                value(extras, Notification.EXTRA_BIG_TEXT),
                value(extras, Notification.EXTRA_SUB_TEXT),
                allExtraText(extras),
                notification.tickerText == null ? "" : notification.tickerText.toString());
        String combined = title + " " + text;
        String movement = movementOf(combined);
        if (movement.isEmpty()) return;

        SharedPreferences preferences = getSharedPreferences("homepad", MODE_PRIVATE);
        saveDiagnostic(preferences, title, text, movement, "감지");
        String server = preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL);
        String token = preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN);
        if (server == null || server.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            saveDiagnostic(preferences, title, text, movement, "서버 설정 없음");
            return;
        }

        String notificationKey = sbn.getKey();
        uploads.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("title", title);
                body.put("text", text);
                body.put("movement", movement);
                body.put("occurredAt", Instant.ofEpochMilli(sbn.getPostTime()).toString());
                body.put("notificationKey", notificationKey == null ? "" : notificationKey);
                body.put("channelId", notification.getChannelId() == null ? "" : notification.getChannelId());
                JSONObject result = new ApiClient(server, token)
                        .postJson("/api/parking/apti-notification", body);
                if (result.optBoolean("accepted", false)) {
                    saveDiagnostic(preferences, title, text, movement, "서버 기록 완료");
                    // Keep Apartmenti's original if the server is unavailable.
                    // On success, the existing SSE path creates one normalized
                    // HomePad vehicle notification and history entry.
                    cancelNotification(notificationKey);
                } else {
                    saveDiagnostic(preferences, title, text, movement,
                            "서버 미기록: " + result.optString("error", "unknown"));
                }
            } catch (Exception error) {
                saveDiagnostic(preferences, title, text, movement,
                        "전송 실패: " + String.valueOf(error.getMessage()));
                Log.w(TAG, "Resident vehicle notification upload failed", error);
            }
        });
    }

    @Override public void onDestroy() {
        uploads.shutdownNow();
        super.onDestroy();
    }

    private static String movementOf(String value) {
        String compact = value == null ? "" : value.replace(" ", "");
        if (containsAny(compact, "출차", "출고", "출문", "진출", "나감", "나갔")) return "출차";
        if (containsAny(compact, "입차", "입고", "입문", "진입", "들어옴", "들어왔")) return "입차";
        return "";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private static void saveDiagnostic(SharedPreferences preferences, String title, String text,
                                       String movement, String status) {
        preferences.edit()
                .putString("apti_vehicle_last_seen_at", Instant.now().toString())
                .putString("apti_vehicle_last_title", limit(title, 256))
                .putString("apti_vehicle_last_text", limit(text, 1500))
                .putString("apti_vehicle_last_movement", movement)
                .putString("apti_vehicle_last_status", limit(status, 500))
                .apply();
    }

    private static String limit(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static String allExtraText(Bundle extras) {
        if (extras == null) return "";
        StringBuilder result = new StringBuilder();
        Set<String> keys = extras.keySet();
        for (String key : keys) {
            appendObjectText(result, extras.get(key), 0);
            if (result.length() >= 1800) break;
        }
        return limit(result.toString(), 1800);
    }

    private static void appendObjectText(StringBuilder result, Object object, int depth) {
        if (object == null || depth > 2 || result.length() >= 1800) return;
        if (object instanceof CharSequence) {
            appendUnique(result, object.toString());
            return;
        }
        if (object instanceof Bundle) {
            Bundle nested = (Bundle) object;
            for (String key : nested.keySet()) appendObjectText(result, nested.get(key), depth + 1);
            return;
        }
        Class<?> type = object.getClass();
        if (type.isArray()) {
            int length = Math.min(Array.getLength(object), 20);
            for (int index = 0; index < length; index++) {
                appendObjectText(result, Array.get(object, index), depth + 1);
            }
        }
    }

    private static void appendUnique(StringBuilder result, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || result.indexOf(trimmed) >= 0) return;
        if (result.length() > 0) result.append(' ');
        result.append(trimmed);
    }

    private static String value(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            if (result.indexOf(value) >= 0) continue;
            if (result.length() > 0) result.append(' ');
            result.append(value);
        }
        return result.toString();
    }
}
