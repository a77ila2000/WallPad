package com.homepad.companion;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Process-wide latest HomePad state shared by the event service, UI, and widget. */
final class HomeRepository {
    static final String ACTION_STATUS_CHANGED = "com.homepad.companion.STATUS_CHANGED";
    static final String EXTRA_STATUS_JSON = "status_json";
    static final String EXTRA_STATUS_EVENT_TYPE = "status_event_type";

    private static final Object LOCK = new Object();
    private static String latestStatusJson = "";

    private HomeRepository() {}

    static void remember(JSONObject status) {
        if (status == null) return;
        synchronized (LOCK) {
            latestStatusJson = status.toString();
        }
    }

    static JSONObject latestStatus() {
        synchronized (LOCK) {
            if (latestStatusJson.isEmpty()) return null;
            try {
                return new JSONObject(latestStatusJson);
            } catch (Exception ignored) {
                latestStatusJson = "";
                return null;
            }
        }
    }

    static void publish(Context context, JSONObject status, String eventType) {
        remember(status);
        Intent update = baseIntent(context, eventType)
                .putExtra(EXTRA_STATUS_JSON, status.toString());
        context.sendBroadcast(update);
    }

    static void invalidate(Context context, String eventType) {
        context.sendBroadcast(baseIntent(context, eventType));
    }

    private static Intent baseIntent(Context context, String eventType) {
        return new Intent(ACTION_STATUS_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_STATUS_EVENT_TYPE, eventType == null ? "" : eventType);
    }
}
