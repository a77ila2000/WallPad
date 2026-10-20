package com.homepad.wallpadbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public final class WallpadEventReceiver extends BroadcastReceiver {
    private static final String TAG = "HomePadBridge";
    private static volatile int lastCheckType;
    private static volatile long lastCheckAt;
    private static volatile long lastOfferingAt;
    private static final DoorOpenSignal DOOR_SIGNAL = new DoorOpenSignal();

    static int lastCheckType() { return lastCheckType; }
    static long lastCheckAt() { return lastCheckAt; }
    static long lastOfferingAt() { return lastOfferingAt; }
    static long lastDoorOpenAt(String kind) {
        return DOOR_SIGNAL.latest(kind);
    }
    static boolean awaitDoorOpen(String kind, long after, long timeoutMs) throws InterruptedException {
        return DOOR_SIGNAL.await(kind, after, timeoutMs);
    }
    static boolean awaitUnitVideoReady(long after, long timeoutMs) throws InterruptedException {
        return DOOR_SIGNAL.await("unit_monitor_video", after, timeoutMs);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"com.commax.service.system.REG_CHECK_EVENT".equals(action)
                && !"com.commax.service.sipua.SIPUA_OFFERING_ACTION".equals(action)
                && !"com.commax.service.visitor.INSERT_VISITOR_ACTION".equals(action)) {
            Log.w(TAG, "Ignoring unsupported exported action=" + action);
            return;
        }
        Intent service = new Intent(context, WallpadBridgeService.class);
        service.setAction(action);
        if ("com.commax.service.system.REG_CHECK_EVENT".equals(action)) {
            int type = eventType(intent.getExtras());
            lastCheckType = type;
            lastCheckAt = SystemClock.elapsedRealtime();
            Object nativeDummy = intent.getExtras() == null ? null : intent.getExtras().get("dummy");
            if (type != 15 || "video-ready".equals(String.valueOf(nativeDummy)))
                DOOR_SIGNAL.mark(type, lastCheckAt);
            if (type != 1 && type != 2) {
                Log.i(TAG, "Ignoring check event type=" + type);
                return;
            }
            service.putExtra("eventType", type);
            service.putExtra("occurredAt", isoNow());
            Object dummy = intent.getExtras() == null ? null : intent.getExtras().get("dummy");
            service.putExtra("eventDummy", dummy == null ? "" : String.valueOf(dummy));
        }
        if ("com.commax.service.sipua.SIPUA_OFFERING_ACTION".equals(action)) {
            long now = SystemClock.elapsedRealtime();
            if (lastOfferingAt > 0L && now - lastOfferingAt < 500L) return;
            lastOfferingAt = now;
            service.putExtra("detectedAt", isoNow());
            service.putExtra("offeredAt", lastOfferingAt);
            service.putExtra("localCallId", UUID.randomUUID().toString().replace("-", ""));
            if (WallpadCallSettings.enabled(context)) {
                Intent automatic = new Intent(service);
                automatic.setClass(context, WallpadAutoUnlockService.class);
                context.startService(automatic);
            }
        }
        if ("com.commax.service.visitor.INSERT_VISITOR_ACTION".equals(action)) {
            service.putExtra("capturedAt", isoNow());
        }
        context.startService(service);
    }

    private static int eventType(Bundle extras) {
        if (extras == null) return 0;
        Object value = extras.get("type");
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
