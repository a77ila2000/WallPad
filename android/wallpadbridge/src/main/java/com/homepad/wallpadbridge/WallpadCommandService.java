package com.homepad.wallpadbridge;

import android.app.Service;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import org.conscrypt.Conscrypt;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Executes commands belonging to the currently active COMMAX call. The bridge
 * sends the same vendor broadcasts as the wallpad's physical conversation and
 * door-open keys so native SIP/RTP and door-open state stay inside COMMAX.
 */
public final class WallpadCommandService extends Service {
    private static final String TAG = "HomePadBridge";
    private static final String VENDOR_CONVERSATION_ACTION =
            "com.commax.service.system.ledaction.CONV";
    private static final String VENDOR_DOOR_OPEN_ACTION =
            "com.commax.service.system.ledaction.DOOROPEN";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern DOOR_BOUNDS = Pattern.compile(
            "resource-id=\\\"com\\.commax\\.sipua:id/imgDoorOpenButton\\\"[^>]*bounds=\\\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\\\"");
    private static final Pattern ACCEPT_BOUNDS = Pattern.compile(
            "resource-id=\\\"com\\.commax\\.sipua:id/imgCallAcceptButton\\\"[^>]*bounds=\\\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\\\"");
    private static final Pattern DISCONNECT_BOUNDS = Pattern.compile(
            "resource-id=\\\"com\\.commax\\.sipua:id/imgCallDisconnectButton\\\"[^>]*bounds=\\\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\\\"");
    private volatile int generation;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        final String eventId = intent.getStringExtra("eventId");
        final String kind = intent.getStringExtra("kind");
        if (eventId == null || eventId.length() != 32) return START_NOT_STICKY;
        final int workerGeneration = ++generation;
        new Thread(new Runnable() {
            @Override public void run() {
                monitor(eventId, kind == null ? "" : kind, workerGeneration);
            }
        }, "HomePadDoorCommand").start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        generation++;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void monitor(String eventId, String kind, int workerGeneration) {
        long deadline = SystemClock.elapsedRealtime() + 300_000L;
        try {
            while (generation == workerGeneration && SystemClock.elapsedRealtime() < deadline) {
                JSONObject response = getJson("/api/wallpad/commands/next?eventId="
                        + URLEncoder.encode(eventId, "UTF-8"));
                JSONObject command = response.optJSONObject("command");
                if (command == null) continue;
                boolean ok = false;
                String detail;
                try {
                    if (!eventId.equals(command.optString("eventId"))) {
                        throw new IllegalStateException("호출 식별자가 일치하지 않습니다");
                    }
                    if (!kind.equals(command.optString("kind"))) {
                        throw new IllegalStateException("현관 종류가 일치하지 않습니다");
                    }
                    String action = command.optString("action", "");
                    detail = executeCallCommand(action, eventId, kind);
                    ok = true;
                } catch (Exception error) {
                    detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                }
                JSONObject result = new JSONObject();
                result.put("commandId", command.optString("id"));
                result.put("ok", ok);
                result.put("detail", detail);
                postJson("/api/wallpad/commands/result", result);
                String completedAction = command.optString("action", "");
                Log.i(TAG, "Call command completed action=" + completedAction
                        + " ok=" + ok + " detail=" + detail);
                // Audio relay is optional to the native COMMAX call.  A relay
                // setup error must not tear down this event's command monitor,
                // otherwise the later door-open/hangup command never reaches
                // the wallpad even though the native call is still active.
                if ("unlock".equals(completedAction) || "hangup".equals(completedAction)) break;
            }
        } catch (Exception error) {
            Log.e(TAG, "Door command monitor failed", error);
        } finally {
            if (generation == workerGeneration) stopSelf();
        }
    }

    private String executeCallCommand(String action, String eventId, String kind) throws Exception {
        if ("answer".equals(action)) return executeAnswerButton();
        if ("audio_start".equals(action)) return executeAudioStart(eventId, kind);
        if ("hangup".equals(action)) {
            stopAudioRelays();
            return executeHangupButton();
        }
        if ("unlock".equals(action)) {
            stopAudioRelays();
            return executeDoorButton(kind);
        }
        throw new IllegalArgumentException("지원하지 않는 통화 명령입니다");
    }

    private String executeAudioStart(String eventId, String kind) throws Exception {
        if (!sipCallViewActive()) throw new IllegalStateException("코맥스 통화가 연결되지 않았습니다");
        if ("unit_entrance".equals(kind)) {
            Intent relay = new Intent(this, WallpadNativeRtpRelayService.class);
            relay.putExtra("eventId", eventId);
            startService(relay);
            if (!WallpadNativeRtpRelayService.awaitReady(eventId, 7000L)) {
                String detail = WallpadNativeRtpRelayService.readyDetail(eventId);
                WallpadNativeRtpRelayService.stopActive();
                stopService(relay);
                throw new IllegalStateException(detail.length() == 0
                        ? "세대현관 네이티브 RTP를 준비하지 못했습니다" : detail);
            }
            return WallpadNativeRtpRelayService.readyDetail(eventId);
        }
        Intent relay = new Intent(this, WallpadAudioRelayService.class);
        relay.putExtra("eventId", eventId);
        startService(relay);
        if (!WallpadAudioRelayService.awaitReady(eventId, 9000L)) {
            String detail = WallpadAudioRelayService.readyDetail(eventId);
            stopService(relay);
            throw new IllegalStateException(detail.length() == 0
                    ? "PCMU 오디오 중계를 준비하지 못했습니다" : detail);
        }
        return WallpadAudioRelayService.readyDetail(eventId);
    }

    private void stopAudioRelays() {
        WallpadNativeRtpRelayService.stopActive();
        stopService(new Intent(this, WallpadNativeRtpRelayService.class));
        stopService(new Intent(this, WallpadAudioRelayService.class));
    }

    private String executeAnswerButton() throws Exception {
        long signalBefore = WallpadEventReceiver.lastCheckAt();
        sendVendorKeyBroadcast(VENDOR_CONVERSATION_ACTION);
        for (int attempt = 0; attempt < 24; attempt++) {
            SystemClock.sleep(250);
            if (WallpadEventReceiver.lastCheckAt() > signalBefore
                    && WallpadEventReceiver.lastCheckType() == 41) {
                return "코맥스 통화가 연결되었습니다";
            }
        }
        throw new IllegalStateException("통화 수락 상태 전환을 확인하지 못했습니다");
    }

    private String executeHangupButton() throws Exception {
        if (!sipCallViewActive()) return "코맥스 통화가 이미 종료되었습니다";
        validateFixedSipCallView();
        sendVendorKeyBroadcast(VENDOR_CONVERSATION_ACTION);
        for (int attempt = 0; attempt < 12; attempt++) {
            SystemClock.sleep(400);
            if (!sipCallViewActive()) return "코맥스 통화가 종료되었습니다";
        }
        throw new IllegalStateException("통화 종료를 확인하지 못했습니다");
    }

    private String executeDoorButton(String kind) throws Exception {
        return executeNativeDoor(this, kind);
    }

    static String executeNativeDoor(android.content.Context context, String kind) throws Exception {
        return executeNativeDoor(context, kind, false);
    }

    static String executeNativeDoor(android.content.Context context, String kind, boolean requireDoorSignal) throws Exception {
        boolean callViewWasActive = sipCallViewActive(context);
        long signalBefore = SystemClock.elapsedRealtime();
        Intent doorKey = new Intent(VENDOR_DOOR_OPEN_ACTION);
        doorKey.setPackage("com.commax.sipua");
        context.sendBroadcast(doorKey);
        if (requireDoorSignal) {
            if (!WallpadEventReceiver.awaitDoorOpen(kind, signalBefore, 5000L))
                throw new IllegalStateException("문열기 신호를 보냈으나 네이티브 개방 확인 신호를 받지 못했습니다");
            return "unit_entrance".equals(kind)
                    ? "코맥스 세대현관 RF 문 열기 신호가 확인되었습니다"
                    : "코맥스 공동현관 문 열기 신호가 확인되었습니다";
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            SystemClock.sleep(500);
            if (WallpadEventReceiver.lastDoorOpenAt(kind) > signalBefore) {
                return "unit_entrance".equals(kind)
                        ? "코맥스 세대현관 RF 문 열기 신호가 확인되었습니다"
                        : "코맥스 공동현관 문 열기 신호가 확인되었습니다";
            }
            if (!requireDoorSignal && callViewWasActive && !sipCallViewActive(context)) {
                return "코맥스 문 열기 동작 후 통화가 종료되었습니다";
            }
        }
        throw new IllegalStateException("버튼을 눌렀지만 통화 종료를 확인하지 못했습니다");
    }

    private void sendVendorKeyBroadcast(String action) {
        // COMMAX's own SipService dynamically registers these two actions for
        // its physical CONV and DOOR OPEN keys. Sending the same action keeps
        // call state, RTP setup and out-of-band door opening inside the vendor
        // process and avoids fragile shell input injection.
        Intent intent = new Intent(action);
        intent.setPackage("com.commax.sipua");
        sendBroadcast(intent);
        Log.i(TAG, "Vendor call key broadcast sent action=" + action);
    }

    private void validateFixedSipCallView() throws Exception {
        if (!sipCallViewActive()) {
            throw new IllegalStateException("코맥스 통화 화면이 활성 상태가 아닙니다");
        }
        // This stock SipCallView uses a fixed 1024x600 layout. Query Android's
        // own task/display APIs so root is used only for the actual input tap.
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        if (metrics.widthPixels != 1024 || metrics.heightPixels != 600) {
            throw new IllegalStateException("검증되지 않은 월패드 화면 크기입니다");
        }
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private void tapButton(Pattern pattern, String missingMessage) throws Exception {
        Matcher bounds = pattern.matcher(hierarchy());
        if (!bounds.find()) {
            throw new IllegalStateException(missingMessage);
        }
        int left = Integer.parseInt(bounds.group(1));
        int top = Integer.parseInt(bounds.group(2));
        int right = Integer.parseInt(bounds.group(3));
        int bottom = Integer.parseInt(bounds.group(4));
        if (right <= left || bottom <= top) {
            throw new IllegalStateException("코맥스 버튼 좌표가 올바르지 않습니다");
        }
        rootInputTap((left + right) / 2, (top + bottom) / 2);
    }

    private static void rootInputTap(int x, int y) throws Exception {
        // /system/bin/input on this Android 4 wallpad is a shell script without
        // a shebang. Executing it directly from an app-owned su process exits
        // with status 11, while running the same script through sh is stable.
        rootCommand(
                "/system/bin/sh",
                "/system/bin/input",
                "tap",
                Integer.toString(x),
                Integer.toString(y));
    }

    private String hierarchy() throws Exception {
        String path = "/sdcard/homepad-call.xml";
        rootCommand("/system/bin/uiautomator", "dump", path);
        return read(new FileInputStream(path));
    }

    private boolean sipCallViewActive() { return sipCallViewActive(this); }

    static boolean sipCallViewActive(android.content.Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) return false;
        ComponentName top = tasks.get(0).topActivity;
        return top != null
                && "com.commax.sipua".equals(top.getPackageName())
                && "com.commax.sipua.SipCallView".equals(top.getClassName());
    }

    private static String rootCommand(String... command) throws Exception {
        // The legacy su binary on this wallpad expects the executable and each
        // argument as separate argv entries after -c. Starting an interactive
        // root shell is unstable here and can terminate with SIGSEGV (139).
        String[] root = new String[command.length + 2];
        root[0] = "su";
        root[1] = "-c";
        System.arraycopy(command, 0, root, 2, command.length);
        ProcessBuilder builder = new ProcessBuilder(root).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.remove("CLASSPATH");
        environment.put("PATH", "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin");
        environment.put("LD_LIBRARY_PATH", "/vendor/lib:/system/lib");
        environment.put("BOOTCLASSPATH",
                "/system/framework/core.jar:/system/framework/core-junit.jar:"
                        + "/system/framework/bouncycastle.jar:/system/framework/ext.jar:"
                        + "/system/framework/framework.jar:/system/framework/telephony-common.jar:"
                        + "/system/framework/mms-common.jar:/system/framework/android.policy.jar:"
                        + "/system/framework/services.jar:/system/framework/apache-xml.jar");
        Process process = builder.start();
        String value = read(process.getInputStream());
        int status = process.waitFor();
        if (status != 0) {
            String detail = value.trim();
            throw new IllegalStateException("root command failed: " + status + " [" + command[0] + "]"
                    + (detail.length() == 0 ? "" : " (" + detail + ")"));
        }
        return value;
    }

    private JSONObject getJson(String path) throws Exception {
        return new WallpadApiClient(this, 8000, 25000).getJson(path);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        return new WallpadApiClient(this, 8000, 12000).postJson(path, body);
    }

    private static String read(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8));
        StringBuilder value = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) value.append(line).append('\n');
        reader.close();
        return value.toString();
    }
}
