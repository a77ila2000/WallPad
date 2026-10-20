package com.homepad.wallpadbridge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.conscrypt.Conscrypt;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.io.IOException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Relays the wallpad's own unit-entrance camera only while a viewer is active. */
public final class WallpadCameraService extends Service {
    private static final String TAG = "HomePadCamera";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FRAME_BASE = "/sdcard/homepad-unitdoor";
    private static final Object CAPTURE_LOCK = new Object();

    private volatile boolean stopped;
    private volatile long captureStartedAt;
    private volatile long lastFrameAt;
    private volatile long lastCallAt;
    private volatile boolean cameraSession;
    private volatile boolean lastDesiredActive;
    private Thread worker;
    private Process capture;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            if (worker == null || !worker.isAlive()) {
                stopped = false;
                worker = new Thread(new Runnable() {
                    @Override public void run() { runLoop(); }
                }, "HomePadUnitDoorRelay");
                worker.start();
            }
        }
        return START_STICKY;
    }

    private void runLoop() {
        long lastErrorLog = 0L;
        while (!stopped) {
            try {
                long pollStarted = SystemClock.elapsedRealtime();
                JSONObject desired = getJson("/api/wallpad/camera/desired?unitDoorControl=1&waitSeconds="
                        + (lastDesiredActive ? "0" : "10") + "&settingsRevision="
                        + WallpadCallSettings.revision(this));
                long pollMs = SystemClock.elapsedRealtime() - pollStarted;
                JSONObject unitCommand = desired.optJSONObject("unitDoorCommand");
                if (unitCommand != null) executeUnitDoorCommand(unitCommand);
                boolean settingsPending = false;
                try {
                    WallpadCallSettings.sync(this, desired.optJSONObject("callSettings"));
                    WallpadCallSettings.flushAudit(this);
                } catch (Exception controlError) {
                    settingsPending = true;
                    // Settings/audit transfer must never disable the camera.
                    Log.w(TAG, "Call settings/audit sync pending: " + controlError.getClass().getSimpleName());
                }
                boolean desiredActive = desired.optBoolean("active", false);
                if (desiredActive != lastDesiredActive || (pollMs > 2000L
                        && (lastDesiredActive || !desired.optBoolean("commandWait", false)))) {
                    Log.i(TAG, "Camera relay desired: active="
                            + desiredActive
                            + " pollMs=" + pollMs);
                }
                lastDesiredActive = desiredActive;
                if (desiredActive) {
                    long now = SystemClock.elapsedRealtime();
                    long latestCaptureActivity = Math.max(captureStartedAt, lastFrameAt);
                    boolean stale = capture != null && latestCaptureActivity > 0L
                            && now - latestCaptureActivity > 6000L;
                    if (capture == null || stale) {
                        stopCaptureProcess();
                        if (!cameraSession) {
                            if (sipCallViewActive()) {
                                // Do not replace a real visitor call with the
                                // self-call used for the unit-door preview.
                                SystemClock.sleep(1000L);
                                continue;
                            }
                            startUnitDoorView();
                            cameraSession = true;
                            lastCallAt = SystemClock.elapsedRealtime();
                            lastFrameAt = 0L;
                            // Start the read-only vendor capture early; failed
                            // one-shot attempts are retried by this service.
                            SystemClock.sleep(1200L);
                        } else if (now - lastCallAt > 25000L && !sipCallViewActive()) {
                            startUnitDoorView();
                            lastCallAt = SystemClock.elapsedRealtime();
                            lastFrameAt = 0L;
                            SystemClock.sleep(1200L);
                        }
                        startCapture(desired.optLong("captureIntervalMs", 500L));
                    }
                    SystemClock.sleep(250L);
                } else {
                    stopCaptureProcess();
                    cameraSession = false;
                    lastCallAt = 0L;
                    lastFrameAt = 0L;
                    if (settingsPending || !desired.optBoolean("commandWait", false))
                        SystemClock.sleep(1000L);
                }
            } catch (Exception error) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastErrorLog > 15000L) {
                    // Android 4.2 sometimes suppresses the throwable detail in
                    // filtered logcat output. Keep the class and message in the
                    // main line so a remote diagnosis never needs broad logs.
                    Log.w(TAG, "Camera relay poll failed: "
                            + error.getClass().getName() + ": "
                            + String.valueOf(error.getMessage()));
                    lastErrorLog = now;
                }
                stopCaptureProcess();
                cameraSession = false;
                SystemClock.sleep(3000L);
            }
        }
        stopCaptureProcess();
    }

    private void startUnitDoorView() {
        Intent intent = new Intent();
        intent.setClassName("com.commax.phonedoor", "com.commax.phonedoor.PhoneDoor");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        Log.i(TAG, "Requested COMMAX unit-door camera #201");
    }

    private void executeUnitDoorCommand(JSONObject command) {
        String id = command.optString("id", "");
        long receivedAt = SystemClock.elapsedRealtime();
        long prepareMs = 0L;
        long signalMs = 0L;
        PowerManager.WakeLock wake = null;
        boolean ok = false;
        String detail;
        try {
            if (!id.matches("[0-9a-f]{32}")
                    || !"unit_monitor_unlock".equals(command.optString("action"))
                    || !"unit_entrance".equals(command.optString("kind")))
                throw new IllegalArgumentException("지원하지 않는 문열기 요청입니다");
            long expiry = command.optLong("expiresAtEpoch", 0L) * 1000L;
            long created = command.optLong("createdAtEpoch", 0L) * 1000L;
            long now = System.currentTimeMillis();
            if (now < created - 5000L || now >= expiry)
                throw new IllegalStateException("문열기 요청이 만료되었습니다");
            android.content.SharedPreferences state = getSharedPreferences("unit_door_command", MODE_PRIVATE);
            if (id.equals(state.getString("lastId", "")))
                throw new IllegalStateException("이미 처리한 문열기 요청입니다");
            // Persist before doing anything: reconnects/restarts must not replay an unlock.
            if (!state.edit().putString("lastId", id).commit())
                throw new IllegalStateException("문열기 요청을 저장하지 못했습니다");
            long offering = WallpadEventReceiver.lastOfferingAt();
            if (WallpadCommandService.sipCallViewActive(this) || CmxMediaControl.getReferenceCount() != 0)
                throw new IllegalStateException("월패드 통화 또는 현관 보기를 종료한 뒤 다시 눌러 주세요");
            stopCaptureProcess();
            cameraSession = false;
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) throw new IllegalStateException("월패드 전원 상태를 확인하지 못했습니다");
            // Keep only the CPU available. A real #201 session can open the door
            // while the LCD stays asleep; do not wake it or wait for a video frame.
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomePad:UnitDoor");
            wake.acquire(20000L);
            // Same #201 intent as stock PhoneDoor.onCreate, without launching its
            // temporary activity. The native SIP service owns its call/video screen.
            Intent monitor = new Intent("com.commax.sipua.NEW_OUTGOING_CALL");
            monitor.setPackage("com.commax.sipua");
            monitor.putExtra("android.intent.extra.PHONE_NUMBER", "#201");
            sendBroadcast(monitor);
            Log.i(TAG, "Requested direct COMMAX unit monitor id=" + id);
            long readyDeadline = SystemClock.elapsedRealtime()
                    + Math.min(6000L, Math.max(0L, expiry - System.currentTimeMillis()));
            // For a new Place2/Status16 monitor the stock SIP service creates
            // SipCallView after Connected. Baseline had no view and getRef=0;
            // require both fresh native state indicators, never just getRef.
            while (!WallpadCommandService.sipCallViewActive(this)
                    || CmxMediaControl.getReferenceCount() <= 0) {
                if (stopped || System.currentTimeMillis() >= expiry
                        || offering != WallpadEventReceiver.lastOfferingAt())
                    throw new IllegalStateException("세대현관 연결 상태가 변경되었습니다");
                if (SystemClock.elapsedRealtime() >= readyDeadline)
                    throw new IllegalStateException("세대현관 연결을 확인하지 못했습니다");
                SystemClock.sleep(100L);
            }
            if (stopped || System.currentTimeMillis() >= expiry
                    || offering != WallpadEventReceiver.lastOfferingAt()
                    || !WallpadCommandService.sipCallViewActive(this) || CmxMediaControl.getReferenceCount() <= 0)
                throw new IllegalStateException("세대현관 연결 상태가 변경되었습니다");
            prepareMs = SystemClock.elapsedRealtime() - receivedAt;
            long signalAt = SystemClock.elapsedRealtime();
            // Exactly the check request used by SipCallView.onDoorOpen for Place2.
            // One request only: an uncertain response must never trigger a retry.
            CmxMediaControl.requestUnitDoorOpen();
            if (!WallpadEventReceiver.awaitDoorOpen("unit_entrance", signalAt, 5000L))
                throw new IllegalStateException("문열기 신호를 보냈으나 네이티브 개방 확인 신호를 받지 못했습니다");
            signalMs = SystemClock.elapsedRealtime() - signalAt;
            ok = true;
            detail = "문열기 신호 전송됨 · 실제 개방 여부를 확인해 주세요";
        } catch (Exception error) {
            detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
        try {
            JSONObject result = new JSONObject().put("commandId", id).put("ok", ok).put("detail", detail)
                    .put("prepareMs", prepareMs).put("signalMs", signalMs);
            new WallpadApiClient(this, 8000, 8000).postJson("/api/wallpad/commands/result", result);
        } catch (Exception error) {
            Log.w(TAG, "Unit door result upload failed; unlock will not be repeated", error);
        }
        Log.i(TAG, "Unit monitor door command completed id=" + id + " ok=" + ok
                + " prepareMs=" + prepareMs + " signalMs=" + signalMs + " detail=" + detail);
        // COMMAX owns the monitor timeout. Do not send CONV: on a monitor it accepts a call.
    }

    private void startCapture(long intervalMs) throws Exception {
        ApplicationInfo bridge = getApplicationInfo();
        ApplicationInfo sip = getPackageManager().getApplicationInfo("com.commax.sipua", 0);
        final String frameBase = FRAME_BASE + "-" + SystemClock.elapsedRealtime();
        String nativeLibraries = sip.nativeLibraryDir;
        if (nativeLibraries == null || nativeLibraries.length() == 0) {
            nativeLibraries = sip.dataDir + "/lib";
        }
        ProcessBuilder builder = new ProcessBuilder(
                "/system/bin/app_process", "/system/bin",
                "com.homepad.wallpadbridge.MediaCaptureMain",
                frameBase, String.valueOf(Math.max(300L, intervalMs)));
        builder.redirectErrorStream(true);
        builder.environment().put("CLASSPATH", bridge.sourceDir + ":" + sip.sourceDir);
        builder.environment().put(
                "LD_LIBRARY_PATH", nativeLibraries + ":/vendor/lib:/system/lib");
        final Process process = builder.start();
        synchronized (CAPTURE_LOCK) {
            capture = process;
            captureStartedAt = SystemClock.elapsedRealtime();
        }
        Thread reader = new Thread(new Runnable() {
            @Override public void run() { readCapture(process, frameBase); }
        }, "HomePadUnitDoorFrames");
        reader.start();
        Log.i(TAG, "Unit-door frame capture started");
    }

    private void readCapture(Process process, String frameBase) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), UTF8));
            String line;
            while (!stopped && (line = reader.readLine()) != null) {
                if (!line.startsWith("FRAME ")) {
                    if (line.startsWith("CAPTURE_ERROR")) Log.d(TAG, line);
                    continue;
                }
                String[] parts = line.split(" ", 3);
                if (parts.length != 3) continue;
                File image = new File(parts[2]);
                byte[] frame = readCompleteJpeg(image, 2 * 1024 * 1024);
                if (frame == null) continue;
                postFrame(frame);
                lastFrameAt = SystemClock.elapsedRealtime();
                image.delete();
            }
        } catch (Exception error) {
            if (!stopped && isCurrent(process)) {
                Log.w(TAG, "Unit-door frame reader stopped", error);
            }
        } finally {
            for (int index = 0; index < 4; index++) {
                new File(frameBase + "." + index + ".jpg").delete();
            }
            synchronized (CAPTURE_LOCK) {
                if (capture == process) capture = null;
            }
        }
    }

    private static byte[] readCompleteJpeg(File file, int maximum) {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                if (file.isFile() && file.length() > 4 && file.length() <= maximum) {
                    byte[] frame = readBytes(file, maximum);
                    if (isJpeg(frame)) return frame;
                }
            } catch (Exception ignored) {
                // The vendor writer may still have the file open. Retry below.
            }
            SystemClock.sleep(50L);
        }
        return null;
    }

    private static byte[] readBytes(File file, int maximum) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > maximum) {
            throw new IllegalStateException("Invalid camera frame size");
        }
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        input.close();
        return output.toByteArray();
    }

    private static boolean isJpeg(byte[] value) {
        return value.length >= 4 && (value[0] & 0xff) == 0xff && (value[1] & 0xff) == 0xd8
                && (value[value.length - 2] & 0xff) == 0xff
                && (value[value.length - 1] & 0xff) == 0xd9;
    }

    private boolean isCurrent(Process process) {
        synchronized (CAPTURE_LOCK) { return capture == process; }
    }

    private boolean sipCallViewActive() throws Exception {
        Process process = new ProcessBuilder(
                "su", "-c", "/system/bin/dumpsys", "activity", "activities")
                .redirectErrorStream(true).start();
        String activities = readMultilineText(process.getInputStream());
        int status = process.waitFor();
        if (status != 0) throw new IllegalStateException("Could not inspect COMMAX call state");
        for (String line : activities.split("\\n")) {
            if (!(line.contains("mResumedActivity:")
                    || line.contains("mFocusedActivity:"))) continue;
            if (line.contains("com.commax.sipua/.SipCallView")
                    || line.contains("com.commax.sipua/com.commax.sipua.SipCallView")) {
                Log.i(TAG, "Protected active visitor call: " + line.trim());
                return true;
            }
        }
        return false;
    }

    private void stopCaptureProcess() {
        synchronized (CAPTURE_LOCK) {
            if (capture != null) capture.destroy();
            capture = null;
            captureStartedAt = 0L;
        }
    }

    private JSONObject getJson(String path) throws Exception {
        return new WallpadApiClient(this, 8000, 12000, true).getJson(path);
    }

    private void postFrame(byte[] frame) throws Exception {
        new WallpadApiClient(this, 8000, 12000)
                .postBytes("/api/wallpad/camera/frame", "image/jpeg", frame);
    }

    private static String readMultilineText(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8));
        StringBuilder value = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) value.append(line).append('\n');
        reader.close();
        return value.toString();
    }

    @Override public void onDestroy() {
        stopped = true;
        stopCaptureProcess();
        Thread active = worker;
        if (active != null) active.interrupt();
        worker = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
