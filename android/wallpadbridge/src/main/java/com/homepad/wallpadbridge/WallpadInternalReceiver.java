package com.homepad.wallpadbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives package-private maintenance alarms only. */
public final class WallpadInternalReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"com.homepad.wallpadbridge.PROBE".equals(action)
                && !"com.homepad.wallpadbridge.RETRY".equals(action)
                && !WallpadQuietHours.ACTION.equals(action)) return;
        context.startService(new Intent(context, WallpadBridgeService.class).setAction(action));
    }
}
