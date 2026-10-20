package com.homepad.companion;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** A manual unit-entrance control; adding/updating this widget never sends a command. */
public final class UnitDoorWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
    }

    static void openDoor(Context context) {
        openDoor(context, false);
    }

    static void openDoor(Context context, boolean vibrateOnSent) {
        context.startForegroundService(new Intent(context, VisitorNotificationService.class)
                .setAction(VisitorNotificationService.ACTION_UNIT_DOOR_OPEN)
                .putExtra(VisitorNotificationService.EXTRA_VIBRATE_ON_SENT, vibrateOnSent));
    }

    static String feedback(SharedPreferences preferences) {
        return System.currentTimeMillis() < preferences.getLong("unit_door_feedback_until", 0L)
                ? preferences.getString("unit_door_feedback", "") : "";
    }

    static boolean pending(SharedPreferences preferences) {
        return System.currentTimeMillis() < preferences.getLong("unit_door_pending_until", 0L);
    }

    static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, UnitDoorWidgetProvider.class));
        if (ids.length == 0) return;
        SharedPreferences preferences = context.getSharedPreferences("homepad", Context.MODE_PRIVATE);
        boolean enabled = !pending(preferences)
                && !preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN).trim().isEmpty();
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.unit_door_widget);
        String feedback = feedback(preferences);
        views.setTextViewText(R.id.unit_widget_feedback, feedback.isEmpty() ? "세대현관" : feedback);
        views.setBoolean(R.id.unit_widget_open, "setEnabled", enabled);
        views.setInt(R.id.unit_widget_open, "setBackgroundResource", enabled
                ? R.drawable.widget_call_background : R.drawable.widget_call_background_disabled);
        Intent open = new Intent(context, VisitorNotificationService.class)
                .setAction(VisitorNotificationService.ACTION_UNIT_DOOR_OPEN);
        views.setOnClickPendingIntent(R.id.unit_widget_open, PendingIntent.getForegroundService(
                context, 2201, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(ids, views);
    }
}
