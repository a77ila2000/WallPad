package com.homepad.companion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Compact 3x1 home-screen control fed by the app's existing real-time service. */
public final class ElevatorWidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "homepad";
    private static final String KEY_ELEVATOR = "widget_elevator";
    private static final String KEY_CALL_ENABLED = "widget_elevator_call_enabled";
    private static final String KEY_FEEDBACK = "widget_elevator_feedback";

    @Override public void onEnabled(Context context) {
        super.onEnabled(context);
        VisitorNotificationService.start(context);
        requestFreshStatus(context);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        renderAll(context);
        VisitorNotificationService.start(context);
        requestFreshStatus(context);
    }

    static void updateFromStatus(Context context, JSONObject status) {
        HomeRepository.remember(status);
        JSONObject elevator = status.optJSONObject("elevator");
        JSONObject capabilities = status.optJSONObject("capabilities");
        boolean canCall = capabilities != null && capabilities.optBoolean("elevatorCall", false);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(KEY_ELEVATOR, elevator == null ? "{}" : elevator.toString())
                .putBoolean(KEY_CALL_ENABLED, canCall)
                .apply();
        renderAll(context);
    }

    static void showCallFeedback(Context context, String feedback) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FEEDBACK, feedback)
                .apply();
        renderAll(context);
    }

    static void clearCallFeedback(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_FEEDBACK)
                .apply();
        renderAll(context);
    }

    private static void requestFreshStatus(Context context) {
        Context app = context.getApplicationContext();
        JSONObject cached = HomeRepository.latestStatus();
        if (cached != null) updateFromStatus(app, cached);
        new Thread(() -> {
            SharedPreferences preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String server = preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL);
            String token = preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN);
            if (token == null || token.trim().isEmpty()) {
                renderAll(app);
                return;
            }
            try {
                updateFromStatus(app, new ApiClient(server, token).getJson("/api/status"));
            } catch (Exception ignored) {
                renderAll(app);
            }
        }, "elevator-widget-refresh").start();
    }

    private static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, ElevatorWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids.length == 0) return;
        RemoteViews views = buildViews(context);
        manager.updateAppWidget(ids, views);
    }

    private static RemoteViews buildViews(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject elevator;
        try {
            elevator = new JSONObject(preferences.getString(KEY_ELEVATOR, "{}"));
        } catch (Exception ignored) {
            elevator = new JSONObject();
        }
        boolean available = elevator.optBoolean("available", false);
        boolean fresh = elevator.optBoolean("fresh", false);
        boolean callEnabled = preferences.getBoolean(KEY_CALL_ENABLED, false)
                && !preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN).trim().isEmpty();
        String floor = available ? elevator.optString("displayFloor", "—") : "—";
        String floorText = "—".equals(floor) ? floor : floor + "층";
        boolean doorOpen = fresh && elevator.optBoolean("doorOpen", false);
        int direction = fresh ? elevator.optInt("direction", 0) : -1;
        String directionText;
        int directionColor;
        if (doorOpen) {
            directionText = "문 열림";
            directionColor = Color.rgb(52, 199, 89);
        } else if (direction == 1) {
            directionText = "↑  상승";
            directionColor = Color.rgb(100, 169, 255);
        } else if (direction == 2) {
            directionText = "↓  하강";
            directionColor = Color.rgb(100, 169, 255);
        } else if (direction == 0) {
            directionText = "●  정지";
            directionColor = Color.rgb(52, 199, 89);
        } else {
            directionText = available ? "마지막 위치" : "위치 대기 중";
            directionColor = Color.rgb(174, 174, 178);
        }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.elevator_widget);
        views.setTextViewText(R.id.widget_floor, floorText);
        views.setTextViewText(R.id.widget_direction, directionText);
        views.setTextColor(R.id.widget_direction, directionColor);
        views.setContentDescription(R.id.widget_status,
                "엘리베이터 현재 " + floorText + ", " + directionText.replace("↑  ", "").replace("↓  ", ""));

        String feedback = preferences.getString(KEY_FEEDBACK, "");
        views.setTextViewText(R.id.widget_call, feedback.isEmpty() ? "우리층 호출" : feedback);
        views.setBoolean(R.id.widget_call, "setEnabled", callEnabled && feedback.isEmpty());
        views.setInt(R.id.widget_call, "setBackgroundResource",
                callEnabled ? R.drawable.widget_call_background : R.drawable.widget_call_background_disabled);
        views.setTextColor(R.id.widget_call, callEnabled ? Color.WHITE : Color.rgb(142, 142, 147));

        Intent call = new Intent(context, VisitorNotificationService.class)
                .setAction(VisitorNotificationService.ACTION_ELEVATOR_CALL);
        PendingIntent callPending = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(context, 2101, call,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(context, 2101, call,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_call, callPending);

        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_ELEVATOR, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, 2102, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_status, openPending);
        return views;
    }
}
