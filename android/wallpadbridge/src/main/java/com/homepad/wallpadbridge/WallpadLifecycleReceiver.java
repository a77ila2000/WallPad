package com.homepad.wallpadbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts bridge maintenance after boot or an APK replacement. */
public final class WallpadLifecycleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) return;
        context.startService(new Intent(context, WallpadBridgeService.class).setAction(
                Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                        ? WallpadQuietHours.ACTION : action));
    }
}
