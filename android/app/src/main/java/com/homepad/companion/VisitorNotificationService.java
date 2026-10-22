package com.homepad.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VisitorNotificationService extends Service {
    static final String EXTRA_OPEN_VISITORS = "open_visitors";
    static final String EXTRA_REQUEST_ANSWER = "request_visitor_answer";
    static final String EXTRA_REQUEST_UNLOCK = "request_visitor_unlock";
    static final String EXTRA_TEST_PARKING_NOTIFICATION = "test_parking_notification";
    static final String EXTRA_VISITOR_EVENT_ID = "visitor_event_id";
    static final String EXTRA_TEST_NOTIFICATION = "test_visitor_notification";
    private static final String ACTION_TEST = "com.homepad.companion.TEST_VISITOR_NOTIFICATION";
    private static final String ACTION_TEST_PARKING = "com.homepad.companion.TEST_PARKING_NOTIFICATION";
    static final String ACTION_ELEVATOR_CALL = "com.homepad.companion.CALL_ELEVATOR";
    static final String ACTION_UNIT_DOOR_OPEN = "com.homepad.companion.OPEN_UNIT_DOOR";
    static final String EXTRA_VIBRATE_ON_SENT = "vibrate_on_sent";
    static final String ACTION_ELEVATOR_B2_CALL = "com.homepad.companion.CALL_ELEVATOR_B2";
    private static final String SERVICE_CHANNEL = "homepad_connection_v1";
    private static final String VISITOR_CHANNEL = "visitor_calls_v1";
    private static final String ELEVATOR_CHANNEL = "elevator_arrival_v1";
    private static final String PARKING_CHANNEL = "parking_vehicle_v1";
    private static final String CCTV_MOTION_CHANNEL = "cctv_motion_v1";
    private static final int SERVICE_NOTIFICATION_ID = 1101;
    private static final int VISITOR_NOTIFICATION_ID = 1201;
    private static final int ELEVATOR_NOTIFICATION_ID = 1301;
    private static final int HOME_FLOOR = 2;
    private static final long PARKING_DUPLICATE_WINDOW_MS = 5 * 60 * 1000L;
    private static final long RECENT_VISITOR_SECONDS = 10 * 60L;

    private final ExecutorService listener = Executors.newSingleThreadExecutor();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final AtomicBoolean unitDoorOpening = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private SharedPreferences preferences;
    private ElevatorShortcutGate shortcutGate;
    private NotificationManager notifications;
    private volatile JSONObject latestElevator = new JSONObject();
    private volatile boolean b2CallReady;
    private volatile String ongoingDetail = "Oracle 서버의 방문 호출을 기다립니다";
    private volatile long callFeedbackUntilMillis;
    private long pendingCallEpoch;
    private boolean seenAwayAfterCall;
    private boolean callStartedAtHome;

    static void start(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("homepad", MODE_PRIVATE);
        if (preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN).trim().isEmpty()) return;
        Intent intent = new Intent(context, VisitorNotificationService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void restart(Context context) {
        // Starting an already running service delivers a fresh start command.
        // A stop/start pair can race on Samsung devices and leave the new
        // foreground-service request attached to the instance being destroyed.
        start(context);
    }

    static void testNotification(Context context) {
        if (!BuildConfig.DEBUG) return;
        Intent intent = new Intent(context, VisitorNotificationService.class).setAction(ACTION_TEST);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void testParkingNotification(Context context) {
        if (!BuildConfig.DEBUG) return;
        Intent intent = new Intent(context, VisitorNotificationService.class).setAction(ACTION_TEST_PARKING);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("homepad", MODE_PRIVATE);
        shortcutGate = new ElevatorShortcutGate(preferences.getLong("shortcut_last_call_uptime", -1L));
        notifications = getSystemService(NotificationManager.class);
        createChannels();
        Notification ongoing = ongoingNotification("Oracle 서버의 방문 호출을 기다립니다");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SERVICE_NOTIFICATION_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, ongoing);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_UNIT_DOOR_OPEN.equals(intent.getAction())
                && !UnitDoorWidgetProvider.pending(preferences) && unitDoorOpening.compareAndSet(false, true)) {
            long tappedAt = System.currentTimeMillis();
            long serviceStarted = android.os.SystemClock.elapsedRealtime();
            preferences.edit().putLong("unit_door_pending_until", tappedAt + 40000L)
                    .putString("unit_door_feedback", "연결 중…")
                    .putLong("unit_door_feedback_until", tappedAt + 40000L).apply();
            UnitDoorWidgetProvider.renderAll(this);
            boolean vibrateOnSent = intent.getBooleanExtra(EXTRA_VIBRATE_ON_SENT, false);
            commands.execute(() -> openUnitDoor(tappedAt, serviceStarted, vibrateOnSent));
        }
        if (intent != null && ACTION_ELEVATOR_CALL.equals(intent.getAction())) {
            String shortcutId = intent.getStringExtra(ElevatorCallShortcutActivity.EXTRA_REQUEST_ID);
            if (shortcutId == null) {
                commands.execute(() -> callElevator(false));
            } else {
                long uptime = android.os.SystemClock.elapsedRealtime();
                if (shortcutGate.tryStart(uptime)) {
                    preferences.edit().putLong("shortcut_last_call_uptime", uptime).apply();
                    commands.execute(() -> callElevator(false, shortcutId));
                } else {
                    shortcutResult(shortcutId, false, "이미 호출 중이거나 방금 호출했습니다. 10초 후 다시 눌러 주세요.");
                }
            }
        }
        if (intent != null && ACTION_ELEVATOR_B2_CALL.equals(intent.getAction())) {
            commands.execute(() -> callElevator(true));
        }
        if (BuildConfig.DEBUG && intent != null && ACTION_TEST.equals(intent.getAction())) {
            JSONObject event = new JSONObject();
            try {
                event.put("id", "debug-test-" + System.currentTimeMillis());
                event.put("kind", "central_entrance");
                event.put("location", "테스트 공동현관");
            } catch (Exception ignored) {
            }
            notifyVisitor(event);
        }
        if (BuildConfig.DEBUG && intent != null && ACTION_TEST_PARKING.equals(intent.getAction())) {
            JSONObject event = new JSONObject();
            try {
                event.put("id", "debug-parking-" + System.currentTimeMillis());
                event.put("vehicleType", "registered");
                event.put("carNumber", "0000(테스트)");
                event.put("entryAt", java.time.OffsetDateTime.now().toString());
            } catch (Exception ignored) {
            }
            notifyParkingVehicle(event);
        }
        if (listening.compareAndSet(false, true)) listener.execute(this::listenForVisitors);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopping = true;
        listener.shutdownNow();
        commands.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void listenForVisitors() {
        while (!stopping) {
            String server = preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL);
            String token = preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN);
            if (token.trim().isEmpty()) {
                stopSelf();
                return;
            }
            try {
                ApiClient api = new ApiClient(server, token);
                JSONObject status = api.getJson("/api/status");
                long sequence = status.optLong("sequence", 0);
                long lastHeartbeatReconcile = android.os.SystemClock.elapsedRealtime();
                notifyCurrentCall(status);
                updateElevator(status);
                publishStatus(status, "initial");
                updateOngoing("방문 호출 대기 중");
                while (!stopping) {
                    JSONObject event = api.waitForEvent(sequence);
                    long eventSequence = event.optLong("sequence", sequence);
                    String type = event.optString("type", "");
                    if ("visitor.ring".equals(type)
                            || "visitor.updated".equals(type)
                            || "visitor.photo".equals(type)) {
                        JSONObject data = event.optJSONObject("data");
                        if (data != null) notifyVisitor(data, "visitor.photo".equals(type));
                        publishInvalidation(type);
                    } else if ("parking.vehicle".equals(type)) {
                        JSONObject data = event.optJSONObject("data");
                        if (data != null) notifyParkingVehicle(data);
                        publishInvalidation(type);
                    } else if ("cctv.motion".equals(type)) {
                        JSONObject data = event.optJSONObject("data");
                        if (data != null) notifyCctvMotion(data);
                    } else if ("state.refresh".equals(type)) {
                        JSONObject refreshed = event.optJSONObject("data");
                        if (refreshed != null) {
                            notifyCurrentCall(refreshed);
                            updateElevator(refreshed);
                            publishStatus(refreshed, type);
                        }
                    } else if ("elevator.status".equals(type) || "elevator.call".equals(type)) {
                        JSONObject refreshed = api.getJson("/api/status");
                        updateElevator(refreshed);
                        publishStatus(refreshed, type);
                    } else if ("light.channel".equals(type)
                            || "heating.zone".equals(type)
                            || "batch_switch.state".equals(type)
                            || "ventilation.state".equals(type)
                            || "official.state".equals(type)
                            || "ew11.connection".equals(type)
                            || "control.command".equals(type)) {
                        publishInvalidation(type);
                    } else if ("heartbeat".equals(type)) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastHeartbeatReconcile >= 300000L) {
                            JSONObject refreshed = api.getJson("/api/status");
                            eventSequence = refreshed.optLong("sequence", 0);
                            notifyCurrentCall(refreshed);
                            updateElevator(refreshed);
                            publishStatus(refreshed, type);
                            lastHeartbeatReconcile = now;
                        }
                    }
                    sequence = eventSequence;
                }
            } catch (Exception error) {
                latestElevator = new JSONObject();
                updateOngoing("서버 재연결 중");
                if (!sleep(3000)) return;
            }
        }
    }

    private void publishStatus(JSONObject status, String eventType) {
        HomeRepository.publish(this, status, eventType);
    }

    private void publishInvalidation(String eventType) {
        HomeRepository.invalidate(this, eventType);
    }

    private void callElevator(boolean basement) {
        callElevator(basement, null);
    }

    private void openUnitDoor(long tappedAt, long serviceStarted, boolean vibrateOnSent) {
        String feedback = "결과 미확인";
        String detail;
        try {
            if (stopping || System.currentTimeMillis() - tappedAt > 5000L)
                throw new IllegalStateException("다른 요청을 처리 중입니다. 잠시 후 다시 눌러 주세요");
            ApiClient api = new ApiClient(preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL),
                    preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN));
            String id = java.util.UUID.randomUUID().toString().replace("-", "");
            preferences.edit().putString("unit_door_last_request_id", id)
                    .putLong("unit_door_tapped_at", tappedAt)
                    .putLong("unit_door_service_queue_ms", android.os.SystemClock.elapsedRealtime() - serviceStarted)
                    .putLong("unit_door_request_ms", -1L).apply();
            long requestStarted = android.os.SystemClock.elapsedRealtime();
            JSONObject result = api.postJson("/api/unit-door/unlock", new JSONObject().put("requestId", id));
            preferences.edit().putLong("unit_door_request_ms",
                    android.os.SystemClock.elapsedRealtime() - requestStarted).apply();
            long deadline = android.os.SystemClock.elapsedRealtime() + 18000L;
            while (!stopping && "pending".equals(result.optString("state"))
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(500L);
                result = api.getJson("/api/unit-door/requests/" + id, 5000);
            }
            detail = result.optString("detail", "결과를 확인하지 못했습니다");
            if ("sent".equals(result.optString("state"))) feedback = "신호 전송됨";
            else if ("failed".equals(result.optString("state"))
                    || "expired".equals(result.optString("state"))) feedback = "요청 실패";
        } catch (Exception error) {
            detail = error.getMessage() == null ? "서버 연결을 확인해 주세요" : error.getMessage();
        }
        if (vibrateOnSent && "신호 전송됨".equals(feedback)) {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) vibrator.vibrate(android.os.VibrationEffect.createWaveform(
                    new long[]{0, 120, 90, 220}, -1));
        }
        preferences.edit().putLong("unit_door_pending_until", 0L)
                .putLong("unit_door_result_ms", android.os.SystemClock.elapsedRealtime() - serviceStarted)
                .putString("unit_door_feedback", feedback)
                .putLong("unit_door_feedback_until", System.currentTimeMillis() + 8000L).apply();
        unitDoorOpening.set(false);
        UnitDoorWidgetProvider.renderAll(this);
        main.postDelayed(() -> {
            preferences.edit().remove("unit_door_feedback").remove("unit_door_feedback_until").apply();
            UnitDoorWidgetProvider.renderAll(this);
        }, 8000L);
        Intent show = new Intent(this, MainActivity.class).putExtra(EXTRA_OPEN_VISITORS, true);
        Notification resultNotice = new Notification.Builder(this, VISITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("세대현관 · " + feedback)
                .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 2202, show,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build();
        notifications.notify(1402, resultNotice);
    }

    private void callElevator(boolean basement, String shortcutId) {
        try {
            String server = preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL);
            String token = preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN);
            ApiClient api = new ApiClient(server, token);
            JSONObject request = new JSONObject();
            request.put("action", "call");
            api.postJson(basement ? "/api/elevator/b2-call" : "/api/elevator/call", request);
            callFeedbackUntilMillis = System.currentTimeMillis() + 10_000L;
            if (!basement) {
                ElevatorWidgetProvider.showCallFeedback(this, "호출 완료");
                main.postDelayed(() -> ElevatorWidgetProvider.clearCallFeedback(this), 3000L);
            }
            updateOngoing(basement ? "지하주차장 호출 신호 전송됨" : "엘리베이터 호출 신호 전송됨");
            if (shortcutId != null) shortcutResult(shortcutId, true, "우리층 호출 신호를 보냈습니다.");
        } catch (Exception error) {
            String callError = error.getMessage() == null ? "앱을 열어 연결을 확인하세요" : error.getMessage();
            if (error instanceof java.net.SocketTimeoutException) {
                callError = "호출 응답이 늦어 결과를 확인하지 못했습니다. 앱에서 현재 층을 확인해 주세요.";
            } else if (error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException) {
                callError = "서버에 연결하지 못했습니다. 인터넷 연결과 광고 차단 앱의 우리집 예외 설정을 확인해 주세요.";
            }
            android.util.Log.w("HomePadElevator", "Call unconfirmed: " + error.getClass().getSimpleName());
            boolean cooldown = callError.startsWith("방금 호출했습니다.");
            if (!basement) {
                ElevatorWidgetProvider.showCallFeedback(this, cooldown ? "호출 대기" : "호출 실패");
                main.postDelayed(() -> ElevatorWidgetProvider.clearCallFeedback(this), 3000L);
            }
            Notification failed = new Notification.Builder(this, ELEVATOR_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(basement ? "지하주차장 호출 준비 중"
                            : cooldown ? "엘리베이터 호출 대기" : "엘리베이터 호출 실패")
                    .setContentText(callError)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setLocalOnly(false)
                    .setAutoCancel(true)
                    .build();
            notifications.notify(ELEVATOR_NOTIFICATION_ID, failed);
            if (shortcutId != null) shortcutResult(shortcutId, false,
                    callError);
        } finally {
            if (shortcutId != null) shortcutGate.finish();
        }
    }

    private void shortcutResult(String requestId, boolean success, String message) {
        preferences.edit().putString("shortcut_result_id", requestId)
                .putBoolean("shortcut_result_ok", success)
                .putString("shortcut_result_message", message)
                .putLong("shortcut_result_at", System.currentTimeMillis()).apply();
        sendBroadcast(new Intent(ElevatorCallShortcutActivity.ACTION_RESULT)
                .setPackage(getPackageName())
                .putExtra(ElevatorCallShortcutActivity.EXTRA_REQUEST_ID, requestId));
    }

    private void updateElevator(JSONObject status) {
        JSONObject elevator = status.optJSONObject("elevator");
        latestElevator = elevator == null ? new JSONObject() : elevator;
        JSONObject capabilities = status.optJSONObject("capabilities");
        b2CallReady = capabilities != null && capabilities.optBoolean("elevatorB2Call", false);
        ElevatorWidgetProvider.updateFromStatus(this, status);
        UnitDoorWidgetProvider.renderAll(this);
        maybeNotifyArrival(status);
        if (("엘리베이터 호출 신호 전송됨".equals(ongoingDetail)
                || "지하주차장 호출 신호 전송됨".equals(ongoingDetail))
                && System.currentTimeMillis() >= callFeedbackUntilMillis) {
            ongoingDetail = "방문 호출 대기 중";
        }
        updateOngoing(ongoingDetail);
    }

    private void maybeNotifyArrival(JSONObject status) {
        JSONObject call = status.optJSONObject("elevatorCall");
        JSONObject elevator = status.optJSONObject("elevator");
        if (call == null || elevator == null) return;
        long requested = call.optLong("lastRequestedEpoch", 0);
        long age = System.currentTimeMillis() / 1000 - requested;
        if (requested <= 0 || age < 0 || age > 300
                || requested <= preferences.getLong("lastNotifiedElevatorCall", 0)) {
            pendingCallEpoch = 0;
            seenAwayAfterCall = false;
            callStartedAtHome = false;
            return;
        }
        if (requested != pendingCallEpoch) {
            pendingCallEpoch = requested;
            seenAwayAfterCall = false;
            callStartedAtHome = false;
        }
        if (!elevator.optBoolean("fresh", false) || !elevator.optBoolean("available", false)) return;
        boolean atHome = !elevator.optBoolean("isBasement", false)
                && elevator.optInt("floor", -1) == HOME_FLOOR;
        boolean doorOpen = elevator.optBoolean("doorOpen", false);
        if (atHome && !seenAwayAfterCall) callStartedAtHome = true;
        if (callStartedAtHome) return;
        if (!atHome) seenAwayAfterCall = true;
        if (!seenAwayAfterCall || !atHome
                || (!doorOpen && elevator.optInt("direction", -1) != 0)) return;

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 9, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification arrived = new Notification.Builder(this, ELEVATOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("엘리베이터가 2층에 도착했습니다")
                .setContentText(doorOpen
                        ? "우리 층에서 문 열림 신호를 확인했습니다"
                        : "호출 후 우리 층 정차를 감지했습니다")
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(false)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        notifications.notify(ELEVATOR_NOTIFICATION_ID, arrived);
        preferences.edit().putLong("lastNotifiedElevatorCall", requested).apply();
        pendingCallEpoch = 0;
        seenAwayAfterCall = false;
        callStartedAtHome = false;
    }

    private void notifyCurrentCall(JSONObject status) {
        JSONObject visitors = status.optJSONObject("visitors");
        if (visitors == null) return;
        JSONObject event = visitors.optJSONObject("lastEvent");
        if (event == null) return;
        if (visitors.optBoolean("ringing", false)) {
            notifyVisitor(event);
            return;
        }

        // A disconnected listener may have missed the entire ring. Recover a
        // recent completed call, and keep its notification after COMMAX hangs up.
        String eventId = event.optString("id", "");
        String photoUrl = event.optString("photoUrl", "");
        long received = event.optLong("receivedEpoch", event.optLong("detectedEpoch", 0));
        long age = System.currentTimeMillis() / 1000 - received;
        if (!eventId.isEmpty() && age >= 0 && age <= RECENT_VISITOR_SECONDS
                && !eventId.equals(preferences.getString("lastNotifiedVisitorEndedEvent", ""))) {
            notifyVisitor(event);
        }
        String lastPhotoId = preferences.getString("lastNotifiedVisitorPhotoEvent", "");
        if (!eventId.isEmpty() && !photoUrl.isEmpty()
                && age >= 0 && age <= 180 && !eventId.equals(lastPhotoId)) {
            notifyVisitor(event, true);
        }
    }

    private void notifyVisitor(JSONObject event) {
        notifyVisitor(event, false);
    }

    private void notifyVisitor(JSONObject event, boolean forceUpdate) {
        String eventId = event.optString("id", "");
        if (eventId.isEmpty()) return;
        String callState = event.optString("callState", "ringing");
        boolean activeCall = "ringing".equals(callState) || "connected".equals(callState);
        String lastId = preferences.getString("lastNotifiedVisitorEvent", "");
        String lastPhotoId = preferences.getString("lastNotifiedVisitorPhotoEvent", "");
        if (!forceUpdate && eventId.equals(preferences.getString(
                activeCall ? "lastNotifiedVisitorEvent" : "lastNotifiedVisitorEndedEvent", ""))) return;
        if (forceUpdate && eventId.equals(lastPhotoId)) return;

        boolean central = "central_entrance".equals(event.optString("kind"));
        String title = central ? "공동현관 방문 호출" : "세대 현관 방문 호출";
        if (!activeCall) title = central ? "공동현관 방문 기록" : "세대 현관 방문 기록";
        if ("confirmed".equals(event.optString("autoUnlockStatus"))) title = "중앙현관 자동 문 열림";
        String fallback = central ? "공동현관" : "세대 현관";
        String location = event.optString("location", fallback);

        Intent open = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_OPEN_VISITORS, true)
                .putExtra(EXTRA_VISITOR_EVENT_ID, eventId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, eventId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent answer = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_OPEN_VISITORS, true)
                .putExtra(EXTRA_REQUEST_ANSWER, true)
                .putExtra(EXTRA_VISITOR_EVENT_ID, eventId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent answerPending = PendingIntent.getActivity(
                this, eventId.hashCode() ^ 0x43414c4c, answer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent unlock = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_OPEN_VISITORS, true)
                .putExtra(EXTRA_REQUEST_UNLOCK, true)
                .putExtra(EXTRA_VISITOR_EVENT_ID, eventId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent unlockPending = PendingIntent.getActivity(
                this, eventId.hashCode() ^ 0x4f50454e, unlock,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, VISITOR_CHANNEL)
                .setSmallIcon(com.homepad.companion.R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(location + "에서 호출했습니다")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(false)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setOnlyAlertOnce(forceUpdate || (!activeCall && eventId.equals(lastId)));
        if (activeCall) {
            builder.addAction(com.homepad.companion.R.drawable.ic_notification, "월패드 수락", answerPending)
                    .addAction(com.homepad.companion.R.drawable.ic_notification, "문 열기", unlockPending);
        }
        String photoUrl = event.optString("photoUrl", "");
        if (!photoUrl.isEmpty()) {
            try {
                String server = preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL);
                String token = preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN);
                Bitmap photo = new ApiClient(server, token).getBitmap(photoUrl);
                builder.setLargeIcon(photo);
                builder.setStyle(new Notification.BigPictureStyle()
                        .bigPicture(photo)
                        .setSummaryText(location + "에서 호출했습니다"));
            } catch (Exception ignored) { }
        }
        Notification notification = builder.build();
        notifications.notify(VISITOR_NOTIFICATION_ID, notification);
        if (!eventId.isEmpty()) {
            android.content.SharedPreferences.Editor editor = preferences.edit()
                    .putString("lastNotifiedVisitorEvent", eventId);
            if (!activeCall) editor.putString("lastNotifiedVisitorEndedEvent", eventId);
            if (forceUpdate && !event.optString("photoUrl", "").isEmpty()) {
                editor.putString("lastNotifiedVisitorPhotoEvent", eventId);
            }
            editor.apply();
        }
    }

    private void notifyParkingVehicle(JSONObject event) {
        String rowId = event.optString("id", "");
        // A vehicle's entry and exit intentionally share one history row, but
        // notificationId changes whenever that row gains an exit time.
        String eventId = event.optString("notificationId", "").trim();
        if (eventId.isEmpty()) eventId = rowId;
        String lastId = preferences.getString("lastNotifiedParkingEvent", "");
        if (!eventId.isEmpty() && eventId.equals(lastId)) return;
        boolean registered = "registered".equals(event.optString("vehicleType"));
        String type = registered ? "세대차량" : "방문차량";
        String movement = parkingMovement(event);
        String plate = event.optString("displayCarNumber", "").trim();
        if (plate.isEmpty()) plate = event.optString("carNumber", "차량번호 없음");
        String signature = parkingNotificationSignature(event, movement);
        if (isRecentParkingDuplicate(signature)) {
            if (!eventId.isEmpty()) {
                preferences.edit().putString("lastNotifiedParkingEvent", eventId).apply();
            }
            return;
        }
        String visitor = event.optString("visitorName", "").trim();
        String detail = plate + ((!registered && !visitor.isEmpty()) ? " · " + visitor : "");

        Intent open = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_OPEN_VISITORS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, eventId.hashCode() ^ 0x5041524b, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, PARKING_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(type + " " + movement)
                .setContentText(detail)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(false)
                .setOnlyAlertOnce(false)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        notifications.notify(2000 + Math.abs(eventId.hashCode() % 100000), notification);
        if (!eventId.isEmpty()) {
            preferences.edit().putString("lastNotifiedParkingEvent", eventId).apply();
        }
    }

    private static String parkingMovement(JSONObject event) {
        String movement = event.optString("movement", "").trim();
        if (movement.contains("출차")) return "출차";
        if (movement.contains("입차")) return "입차";
        if (!event.optString("exitAt", "").trim().isEmpty()) return "출차";
        if (!event.optString("entryAt", "").trim().isEmpty()) return "입차";
        String status = event.optString("status", "이동").trim();
        if (status.contains("출차")) return "출차";
        if (status.contains("입차") || status.contains("주차")) return "입차";
        return status.isEmpty() ? "이동" : status;
    }

    private static String parkingNotificationSignature(JSONObject event, String movement) {
        String plate = event.optString("carNumber", "").trim();
        if (plate.isEmpty()) plate = event.optString("displayCarNumber", "").trim();
        plate = plate.replaceAll("[^0-9A-Za-z가-힣]", "").toUpperCase(Locale.ROOT);
        return plate + "|" + movement;
    }

    private boolean isRecentParkingDuplicate(String signature) {
        if (signature.startsWith("|")) return false;
        long now = System.currentTimeMillis();
        JSONObject recent;
        try {
            recent = new JSONObject(preferences.getString("recentParkingNotificationSignatures", "{}"));
        } catch (Exception ignored) {
            recent = new JSONObject();
        }
        List<String> expired = new ArrayList<>();
        Iterator<String> keys = recent.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (now - recent.optLong(key, 0L) > PARKING_DUPLICATE_WINDOW_MS) expired.add(key);
        }
        for (String key : expired) recent.remove(key);
        long previous = recent.optLong(signature, 0L);
        try {
            recent.put(signature, now);
        } catch (Exception ignored) { }
        preferences.edit().putString("recentParkingNotificationSignatures", recent.toString()).apply();
        return previous > 0L && now - previous <= PARKING_DUPLICATE_WINDOW_MS;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel service = new NotificationChannel(
                SERVICE_CHANNEL, "방문 알림 연결", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("방문 호출을 실시간으로 받기 위한 서버 연결 상태");
        service.setShowBadge(false);
        notifications.createNotificationChannel(service);

        NotificationChannel visitor = new NotificationChannel(
                VISITOR_CHANNEL, "방문 호출", NotificationManager.IMPORTANCE_HIGH);
        visitor.setDescription("공동현관과 세대 현관의 방문 호출");
        visitor.enableVibration(true);
        visitor.enableLights(true);
        visitor.setLightColor(Color.BLUE);
        notifications.createNotificationChannel(visitor);

        NotificationChannel elevator = new NotificationChannel(
                ELEVATOR_CHANNEL, "엘리베이터 도착", NotificationManager.IMPORTANCE_HIGH);
        elevator.setDescription("호출 후 우리 층에 멈춘 엘리베이터 알림");
        notifications.createNotificationChannel(elevator);

        NotificationChannel parking = new NotificationChannel(
                PARKING_CHANNEL, "차량 입·출차", NotificationManager.IMPORTANCE_HIGH);
        parking.setDescription("방문 등록 차량과 세대 보유 차량의 입차 및 출차 알림");
        parking.enableVibration(true);
        notifications.createNotificationChannel(parking);

        NotificationChannel motion = new NotificationChannel(
                CCTV_MOTION_CHANNEL, "CCTV 모션 감지", NotificationManager.IMPORTANCE_HIGH);
        motion.setDescription("선택한 현관·거실 카메라에서 움직임이 감지되면 알립니다");
        motion.enableVibration(true);
        notifications.createNotificationChannel(motion);
    }

    private void notifyCctvMotion(JSONObject event) {
        String camera = event.optString("cameraId", "");
        if (!"cam1".equals(camera) && !"cam2".equals(camera)) return;
        if (!preferences.getBoolean("cctv_motion_" + camera, false)) return;
        long epoch = event.optLong("detectedEpoch", 0);
        long enabled = preferences.getLong("cctv_motion_since_" + camera, 0);
        if (epoch * 1000L < enabled - 1000L || Math.abs(System.currentTimeMillis() / 1000L - epoch) > 300L) return;
        String id = event.optString("id", "");
        String previous = "lastNotifiedCctvMotion_" + camera;
        if (id.isEmpty() || id.equals(preferences.getString(previous, ""))) return;
        String name = "cam1".equals(camera) ? "거실 CAM" : "현관 CAM";
        String at = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_CCTV_CAMERA, camera)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int notificationId = "cam1".equals(camera) ? 1401 : 1402;
        PendingIntent pending = PendingIntent.getActivity(this, notificationId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CCTV_MOTION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name + (event.optBoolean("test") ? " · 알림 시험" : " · 모션 감지"))
                .setContentText("움직임 감지 · " + at + " · 누르면 카메라를 봅니다")
                .setCategory(Notification.CATEGORY_EVENT).setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setLocalOnly(false)
                .setWhen(epoch * 1000L).setAutoCancel(true).setContentIntent(pending).build();
        notifications.notify(notificationId, notification);
        preferences.edit().putString(previous, id).apply();
    }

    private Notification ongoingNotification(String detail) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 8, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent call = new Intent(this, VisitorNotificationService.class).setAction(ACTION_ELEVATOR_CALL);
        PendingIntent callPending = PendingIntent.getService(this, 10, call,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent b2Call = new Intent(this, VisitorNotificationService.class).setAction(ACTION_ELEVATOR_B2_CALL);
        PendingIntent b2CallPending = PendingIntent.getService(this, 11, b2Call,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        JSONObject elevator = latestElevator;
        String floor = elevator.optBoolean("available", false)
                ? elevator.optString("displayFloor", "—") : "—";
        String floorDetail = elevator.optBoolean("available", false)
                ? elevator.optBoolean("fresh", false)
                    ? "엘베 " + floor + "층 · " + (elevator.optBoolean("doorOpen", false)
                        ? "문 열림"
                        : elevator.optString("directionText", "상태 확인 중"))
                    : "엘베 마지막 위치 " + floor + "층"
                : "엘베 위치 대기 중";
        String pendingCallText = elevator.optString("pendingCallText", "");
        if (!pendingCallText.isEmpty() && !"대기 호출층 · 없음".equals(pendingCallText)) {
            floorDetail += " · " + pendingCallText;
        }
        Notification.Builder builder = new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(com.homepad.companion.R.drawable.ic_notification)
                .setContentTitle("우리집 방문 알림")
                .setContentText(detail + " · " + floorDetail)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .addAction(R.drawable.ic_notification, "우리 층 호출", callPending);
        if (b2CallReady) {
            builder.addAction(R.drawable.ic_notification, "B2 호출", b2CallPending);
        }
        return builder.build();
    }

    private void updateOngoing(String detail) {
        ongoingDetail = detail;
        notifications.notify(SERVICE_NOTIFICATION_ID, ongoingNotification(detail));
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
