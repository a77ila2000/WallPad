package com.homepad.wallpadbridge;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicLong;

/** Cache files and isolated test prefs only. No calls, vendor keys or media changes. */
public final class AutoUnlockLatencyProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File directory = new File(getTargetContext().getCacheDir(), "jpeg-close-probe");
        VisitorPhotoAwaiter photos = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("test directory unavailable");
            for (File file : directory.listFiles()) file.delete();
            photos = new VisitorPhotoAwaiter(new String[] {directory.getAbsolutePath()});
            long fresh = System.currentTimeMillis() - 1000L;
            File stale = new File(directory, "stale-1-0-1-0.jpg");
            jpeg(stale, true);
            stale.setLastModified(fresh - 10000L);
            check(photos.await(fresh, "central_entrance", 80L) == null, "stale photo selected");
            File unit = new File(directory, "unit-1-0-0-0.jpg");
            jpeg(unit, true);
            check(photos.await(fresh, "central_entrance", 80L) == null, "unit photo selected for central call");
            final File incoming = new File(directory, "incoming-1-0-1-0.jpg");
            jpeg(incoming, false);
            check(photos.await(fresh, "central_entrance", 80L) == null, "incomplete JPEG selected");
            final AtomicLong closedAt = new AtomicLong();
            final AtomicLong writerError = new AtomicLong();
            Thread writer = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        SystemClock.sleep(310L);
                        FileOutputStream out = new FileOutputStream(incoming, true);
                        out.write(new byte[] {(byte) 0xff, (byte) 0xd9});
                        closedAt.set(SystemClock.elapsedRealtime());
                        out.close();
                    } catch (Exception ignored) { writerError.set(1L); }
                }
            });
            writer.start();
            File found = photos.await(fresh, "central_entrance", 1500L);
            long returnedAt = SystemClock.elapsedRealtime();
            writer.join();
            check(writerError.get() == 0L && incoming.equals(found), "JPEG close not detected");
            long latency = returnedAt - closedAt.get();
            result.putLong("jpegCloseWakeMs", latency);
            check(latency >= 0L && latency < 150L, "JPEG close wake unexpectedly slow: " + latency);
            photos.close();
            photos = null;

            final DoorOpenSignal signal = new DoorOpenSignal();
            signal.mark(31, 100L);
            check(!signal.await("central_entrance", 100L, 30L), "old door event accepted");
            signal.mark(33, 200L);
            check(!signal.await("central_entrance", 100L, 30L), "unit event accepted as central");
            final AtomicLong signalledAt = new AtomicLong();
            Thread sender = new Thread(new Runnable() {
                @Override public void run() {
                    SystemClock.sleep(80L);
                    signalledAt.set(SystemClock.elapsedRealtime());
                    signal.mark(31, 201L);
                }
            });
            sender.start();
            check(signal.await("central_entrance", 100L, 1000L), "new door event not detected");
            long doorWake = SystemClock.elapsedRealtime() - signalledAt.get();
            sender.join();
            result.putLong("doorSignalWakeMs", doorWake);
            check(doorWake >= 0L && doorWake < 150L, "signal wake unexpectedly slow");
            check(signal.await("unit_entrance", 199L, 30L), "unit event lost");

            // Legacy instrumentation runs as the target UID. Use a separate
            // preference name and never access the production call_settings.
            Context isolated = new ContextWrapper(getTargetContext()) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("latency_probe_" + name, mode);
                }
            };
            SharedPreferences state = isolated.getSharedPreferences("call_settings", 0);
            check(state.edit().clear().commit(), "isolated prefs unavailable");
            JSONObject audit = new JSONObject().put("localCallId", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                    .put("autoUnlockStatus", "attempted").put("photoPath", incoming.getAbsolutePath());
            check(WallpadCallSettings.claim(isolated, 123L, "lobby0041", audit), "claim failed");
            check(state.getString("lastClaim", "").equals("lobby0041:123"), "claim not persisted");
            JSONArray queue = new JSONArray(state.getString("auditQueue", "[]"));
            check(queue.length() == 1 && queue.getJSONObject(0).optString("photoPath").equals(incoming.getAbsolutePath()),
                    "durable photo audit missing");
            check(!WallpadCallSettings.claim(isolated, 123L, "lobby0041", audit), "duplicate claim accepted");
            check(new JSONArray(state.getString("auditQueue", "[]")).length() == 1, "duplicate audit queued");
            check(state.edit().clear().commit(), "isolated prefs cleanup failed");
            result.putString("result", "native filesystem wake, isolated signal wake and atomic audit PASS");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.toString());
            finish(0, result);
        } finally {
            if (photos != null) photos.close();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }

    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
    private static void jpeg(File path, boolean complete) throws Exception {
        FileOutputStream out = new FileOutputStream(path);
        try {
            byte[] data = new byte[2048];
            data[0] = (byte) 0xff; data[1] = (byte) 0xd8;
            if (complete) { data[data.length - 2] = (byte) 0xff; data[data.length - 1] = (byte) 0xd9; }
            out.write(data);
        } finally { out.close(); }
    }
}
