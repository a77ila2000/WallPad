package com.homepad.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class MainActivity extends Activity {
    static final String EXTRA_OPEN_ELEVATOR = "open_elevator";
    static final String EXTRA_CCTV_CAMERA = "cctv_camera";
    private static final int HEATING = 0;
    private static final int VISITORS = 1;
    private static final int ELEVATOR = 2;
    private static final int LIGHTS = 3;
    private static final int CCTV = 4;
    private static final int REQUEST_VISITOR_MICROPHONE = 4102;
    private static final String[] TAB_NAMES = {"메인", "방문", "엘리베이터", "전등", "CCTV"};
    private static final String PARKING_VISITOR_NAME = BuildConfig.PARKING_VISITOR_NAME;
    private static final String PARKING_PHONE = BuildConfig.PARKING_PHONE;
    private static final DateTimeFormatter PARKING_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter PARKING_DATE_LABEL = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter VISITOR_TIME_LABEL = DateTimeFormatter.ofPattern("MM.dd HH:mm");

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService controls = Executors.newSingleThreadExecutor();
    private final ExecutorService cctvNetwork = Executors.newSingleThreadExecutor();
    private final ExecutorService visitorPhotoNetwork = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, PendingPower> pendingPower = new HashMap<>();
    private final LruCache<String, Bitmap> visitorPhotoCache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };
    private final LruCache<Integer, Bitmap> cctvFrameCache = new LruCache<Integer, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };
    private final Map<Integer, Long> cctvFrameTimes = new HashMap<>();
    private static final long POWER_CONFIRM_TIMEOUT_MS = 20_000;
    private static final class PendingPower {
        final boolean enabled;
        final long startedAt;
        boolean accepted;
        PendingPower(boolean enabled) {
            this.enabled = enabled;
            this.startedAt = SystemClock.elapsedRealtime();
        }
    }
    private final List<Button> tabButtons = new ArrayList<>();
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refreshStatus(false);
            scheduleRefresh();
        }
    };
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!HomeRepository.ACTION_STATUS_CHANGED.equals(intent.getAction())) return;
            String eventType = intent.getStringExtra(
                    HomeRepository.EXTRA_STATUS_EVENT_TYPE);
            String encoded = intent.getStringExtra(HomeRepository.EXTRA_STATUS_JSON);
            if (encoded == null || encoded.isEmpty()) {
                refreshStatus(false);
            } else {
                try {
                    applyStatus(new JSONObject(encoded));
                } catch (Exception ignored) {
                    refreshStatus(false);
                }
            }
            if ("parking.vehicle".equals(eventType) && currentTab == VISITORS) {
                parkingLoadedAt = 0;
                refreshParkingHistory(false);
            }
        }
    };

    private SharedPreferences preferences;
    private CredentialStore credentialStore;
    private ApiClient api;
    private JSONObject status;
    private LinearLayout page;
    private ScrollView pageScroll;
    private boolean resetPageScrollOnRender;
    private TextView connection;
    private int currentTab = HEATING;
    private boolean loadingStatus;
    private boolean autoOfficialAttempted;
    private volatile boolean enrollingOfficial;
    private boolean resumed;
    private boolean statusReceiverRegistered;
    private int targetAllTemperature = 24;
    private Bitmap cctvBitmap;
    private ImageView cctvPreview;
    private PersonalCctvView personalCctv;
    private int cctvChannel = -1;
    private String cctvChannelName = "";
    private boolean cctvLoading;
    private boolean cctvShowingCached;
    private long cctvCachedAgeMs;
    private String cctvError = "";
    private volatile CctvStream cctvStream;
    private volatile int cctvSessionId;
    private String pendingAnswerEventId;
    private String pendingMicrophoneAnswerEventId;
    private String pendingUnlockEventId;
    private String pendingCallEventId = "";
    private String pendingCallAction = "";
    private long pendingCallStartedAt;
    private String connectedCallEventId = "";
    private long connectedCallStartedAt;
    private TextView visitorCallStatusView;
    private Button unitDoorOpenButton;
    private TextView unitDoorFeedbackView;
    private final Runnable unitDoorUiRefresh = this::updateUnitDoorControl;
    private final SharedPreferences.OnSharedPreferenceChangeListener unitDoorListener =
            (prefs, key) -> { if (key != null && key.startsWith("unit_door_")) main.post(this::updateUnitDoorControl); };
    private final Runnable visitorCallUiTicker = new Runnable() {
        @Override public void run() {
            if (!resumed || currentTab != VISITORS || visitorCallStatusView == null) return;
            JSONObject event = object(object(status, "visitors"), "lastEvent");
            String eventId = event.optString("id", "");
            String callState = event.optString("callState", "ended");
            boolean pending = eventId.equals(pendingCallEventId) && !pendingCallAction.isEmpty();
            if (!pending && !"connected".equals(callState)) return;
            visitorCallStatusView.setText(visitorCallStatusLabel(event, callState));
            main.postDelayed(this, 1000);
        }
    };
    private JSONObject parkingHistory;
    private boolean parkingLoading;
    private boolean parkingExpanded;
    private long parkingLoadedAt;
    private String parkingError = "";
    private String parkingCarNumber = "";
    private LocalDate parkingVisitDate = LocalDate.now();
    private float swipeStartX;
    private float swipeStartY;
    private long swipeStartedAt;

    private boolean dark;
    private int appBackground;
    private int surface;
    private int label;
    private int secondary;
    private int separator;
    private int tint;
    private int tintSoft;
    private int success;
    private int danger;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("homepad", MODE_PRIVATE);
        credentialStore = new CredentialStore(this);
        readLaunchIntent(getIntent());
        configurePalette();
        configureWindow();
        api = new ApiClient(serverUrl(), apiToken());
        restoreParkingHistory();
        setContentView(buildApp());
        if (apiToken().isEmpty()) {
            main.postDelayed(this::showSettings, 250);
        } else {
            enableVisitorNotifications();
        }
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra(VisitorNotificationService.EXTRA_TEST_NOTIFICATION, false)) {
            main.postDelayed(() -> VisitorNotificationService.testNotification(this), 600);
        }
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra(VisitorNotificationService.EXTRA_TEST_PARKING_NOTIFICATION, false)) {
            main.postDelayed(() -> VisitorNotificationService.testParkingNotification(this), 800);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLaunchIntent(intent);
        if (intent.hasExtra(EXTRA_CCTV_CAMERA)) {
            stopCctvStream();
            updateTabs();
            renderPage();
        }
        if (intent.getBooleanExtra(VisitorNotificationService.EXTRA_OPEN_VISITORS, false)) {
            currentTab = VISITORS;
            updateTabs();
            renderPage();
            maybeStartPendingAnswer();
            maybeConfirmPendingUnlock();
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_ELEVATOR, false)) {
            currentTab = ELEVATOR;
            updateTabs();
            renderPage();
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra(VisitorNotificationService.EXTRA_TEST_NOTIFICATION, false)) {
            VisitorNotificationService.testNotification(this);
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra(VisitorNotificationService.EXTRA_TEST_PARKING_NOTIFICATION, false)) {
            VisitorNotificationService.testParkingNotification(this);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PersonalCctvView.REQUEST_MICROPHONE) {
            if (personalCctv != null) personalCctv.finishMicrophoneRequest();
            return;
        }
        if (requestCode != REQUEST_VISITOR_MICROPHONE) return;
        String eventId = pendingMicrophoneAnswerEventId;
        pendingMicrophoneAnswerEventId = null;
        if (eventId == null || eventId.isEmpty()) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            controlVisitorCall(eventId, "answer");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("마이크 권한이 필요합니다")
                .setMessage("방문자와 통화하려면 휴대폰 마이크 권한을 허용해 주세요.")
                .setPositiveButton("확인", null)
                .show();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swipeStartX = event.getRawX();
            swipeStartY = event.getRawY();
            swipeStartedAt = SystemClock.elapsedRealtime();
        }

        boolean handled = super.dispatchTouchEvent(event);
        if (action != MotionEvent.ACTION_UP) return handled;
        // A thumbnail swipe must scroll recordings, rather than change tabs.
        if (currentTab == CCTV && personalCctv != null && personalCctv.isShown()) {
            int[] position = new int[2];
            personalCctv.getLocationOnScreen(position);
            if (swipeStartY >= position[1] && swipeStartY <= position[1] + personalCctv.getHeight()) return handled;
        }

        float dx = event.getRawX() - swipeStartX;
        float dy = event.getRawY() - swipeStartY;
        long elapsed = SystemClock.elapsedRealtime() - swipeStartedAt;
        float threshold = dp(72);
        boolean horizontal = Math.abs(dx) >= threshold && Math.abs(dx) > Math.abs(dy) * 1.35f;
        boolean contentArea = swipeStartY < getResources().getDisplayMetrics().heightPixels - dp(72);
        if (!horizontal || !contentArea || elapsed > 1400) return handled;

        int next = dx < 0 ? currentTab + 1 : currentTab - 1;
        if (next >= 0 && next < TAB_NAMES.length) {
            selectTab(next);
            return true;
        }
        return handled;
    }

    @Override protected void onResume() {
        super.onResume();
        preferences.registerOnSharedPreferenceChangeListener(unitDoorListener);
        resumed = true;
        registerStatusReceiver();
        JSONObject cached = HomeRepository.latestStatus();
        if (cached != null) applyStatus(cached);
        updateUnitDoorControl();
        refreshStatus(true);
        scheduleRefresh();
        if (currentTab == VISITORS) refreshParkingHistory(false);
        if (currentTab == CCTV && cctvChannel >= 0) loadCctv(cctvChannel, cctvChannelName);
    }

    @Override protected void onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(unitDoorListener);
        main.removeCallbacks(unitDoorUiRefresh);
        resumed = false;
        unregisterStatusReceiver();
        main.removeCallbacks(refreshLoop);
        main.removeCallbacks(visitorCallUiTicker);
        stopCctvStream();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (personalCctv != null) {
            ViewGroup parent = (ViewGroup) personalCctv.getParent();
            if (parent != null) parent.removeView(personalCctv);
            personalCctv.destroy();
        }
        network.shutdownNow();
        controls.shutdownNow();
        cctvNetwork.shutdownNow();
        visitorPhotoNetwork.shutdownNow();
        super.onDestroy();
    }

    private void scheduleRefresh() {
        main.removeCallbacks(refreshLoop);
        if (!resumed) return;
        JSONObject elevator = object(status, "elevator");
        boolean moving = elevator.optBoolean("fresh", false)
                && (elevator.optInt("direction", 0) == 1 || elevator.optInt("direction", 0) == 2);
        long delay = !pendingPower.isEmpty() ? 1000
                : (moving || currentTab == ELEVATOR ? 5000 : 15000);
        main.postDelayed(refreshLoop, delay);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStatusReceiver() {
        if (statusReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(HomeRepository.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        statusReceiverRegistered = true;
    }

    private void unregisterStatusReceiver() {
        if (!statusReceiverRegistered) return;
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        statusReceiverRegistered = false;
    }

    private void configurePalette() {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        appBackground = Color.parseColor(dark ? "#000000" : "#F2F2F7");
        surface = Color.parseColor(dark ? "#1C1C1E" : "#FFFFFF");
        label = Color.parseColor(dark ? "#F2F2F7" : "#1C1C1E");
        secondary = Color.parseColor(dark ? "#AEAEB2" : "#636366");
        separator = Color.parseColor(dark ? "#3A3A3C" : "#D1D1D6");
        tint = Color.parseColor(dark ? "#64A9FF" : "#0A84FF");
        tintSoft = Color.parseColor(dark ? "#173653" : "#E8F3FF");
        success = Color.parseColor("#34C759");
        danger = Color.parseColor(dark ? "#FF6961" : "#C9342F");
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(appBackground);
        getWindow().setNavigationBarColor(appBackground);
        if (!dark && Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private View buildApp() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(appBackground);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        root.addView(buildHeader(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        page = vertical(dp(12));
        page.setPadding(dp(12), dp(10), dp(12), dp(18));
        pageScroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildTabs(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        renderPage();
        return root;
    }

    private View buildHeader() {
        LinearLayout header = horizontal(dp(12));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(10), dp(8));

        LinearLayout titles = vertical(0);
        TextView eyebrow = text("HOMEPAD", 11, secondary, Typeface.BOLD);
        TextView title = text("우리집", 23, label, Typeface.BOLD);
        titles.addView(eyebrow);
        titles.addView(title);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        connection = text("연결 확인 중", 12, secondary, Typeface.BOLD);
        connection.setGravity(Gravity.CENTER);
        connection.setMinHeight(dp(36));
        connection.setPadding(dp(10), 0, dp(10), 0);
        connection.setBackground(rounded(tintSoft, dp(18), 0, 0));
        header.addView(connection);

        Button settings = button("설정", Color.TRANSPARENT, tint);
        settings.setContentDescription("서버 연결 설정");
        settings.setMinWidth(dp(52));
        settings.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(58), dp(44));
        settingsParams.setMarginStart(dp(4));
        header.addView(settings, settingsParams);
        return header;
    }

    private View buildTabs() {
        LinearLayout bar = horizontal(dp(4));
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(7), dp(5), dp(7), dp(5));
        bar.setBackground(rounded(surface, dp(20), separator, 1));
        for (int index = 0; index < TAB_NAMES.length; index++) {
            final int tab = index;
            Button item = button(TAB_NAMES[index], Color.TRANSPARENT, secondary);
            item.setTextSize(index == ELEVATOR ? 10 : 11);
            item.setMinWidth(0);
            item.setOnClickListener(v -> selectTab(tab));
            tabButtons.add(item);
            bar.addView(item, new LinearLayout.LayoutParams(0, dp(50), 1f));
        }
        updateTabs();
        return bar;
    }

    private void selectTab(int tab) {
        if (currentTab == CCTV && tab != CCTV) stopCctvStream();
        if (currentTab != tab) resetPageScrollOnRender = true;
        currentTab = tab;
        updateTabs();
        renderPage();
        if (tab == VISITORS) refreshParkingHistory(false);
        if (tab == CCTV && cctvChannel >= 0 && cctvChannel < 101 && cctvStream == null && resumed) {
            loadCctv(cctvChannel, cctvChannelName);
        }
    }

    private void updateTabs() {
        for (int i = 0; i < tabButtons.size(); i++) {
            boolean selected = i == currentTab;
            Button button = tabButtons.get(i);
            button.setTextColor(selected ? tint : secondary);
            button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackground(rounded(selected ? tintSoft : Color.TRANSPARENT, dp(14), 0, 0));
        }
    }

    private void refreshStatus(boolean announceErrors) {
        if (loadingStatus || apiToken().isEmpty()) return;
        loadingStatus = true;
        network.execute(() -> {
            try {
                JSONObject value = api.getJson("/api/status");
                main.post(() -> {
                    loadingStatus = false;
                    applyStatus(value);
                });
            } catch (Exception error) {
                main.post(() -> {
                    loadingStatus = false;
                    updateConnection(false, message(error));
                    if (announceErrors) toast(message(error));
                });
            }
        });
    }

    private void applyStatus(JSONObject value) {
        HomeRepository.remember(value);
        String previousPageState = pageFingerprint(status, currentTab);
        String previousPending = pendingPowerFingerprint();
        status = value;
        reconcilePendingPower();
        String nextPageState = pageFingerprint(value, currentTab);
        String nextPending = pendingPowerFingerprint();
        updateConnection(true, null);
        if (!previousPageState.equals(nextPageState) || !previousPending.equals(nextPending)) {
            renderPage();
        }
        maybeStartPendingAnswer();
        maybeConfirmPendingUnlock();
        maybeAutoEnrollOfficial();
    }

    private String pageFingerprint(JSONObject value, int tab) {
        if (value == null) return "";
        if (tab == CCTV) return object(value, "cctv").toString();
        String common = visitorPageFingerprint(value)
                + "|" + object(value, "elevator").toString()
                + "|" + object(value, "capabilities").toString();
        if (tab == HEATING) {
            return common + "|" + object(value, "heating") + "|" + object(value, "batchSwitch");
        }
        if (tab == LIGHTS) {
            return common + "|" + object(value, "lights") + "|" + object(value, "batchSwitch");
        }
        if (tab == ELEVATOR) return common;
        if (tab == VISITORS) return common + "|" + object(value, "integration");
        return common + "|" + object(value, "cctv");
    }

    private String pendingPowerFingerprint() {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, PendingPower> entry : pendingPower.entrySet()) {
            values.add(entry.getKey() + "=" + entry.getValue().enabled + ":" + entry.getValue().accepted);
        }
        Collections.sort(values);
        return values.toString();
    }

    private String visitorPageFingerprint(JSONObject value) {
        if (value == null) return "";
        JSONObject visitors = object(value, "visitors");
        JSONObject event = visitors.optJSONObject("lastEvent");
        JSONObject integration = object(value, "integration");
        JSONObject capabilities = object(value, "capabilities");
        String eventState = event == null ? "" : event.optString("id", "")
                + "|" + event.optString("kind", "")
                + "|" + event.optString("location", event.optString("title", ""))
                + "|" + event.optString("receivedAt", "")
                + "|" + event.optString("photoUrl", "")
                + "|" + event.optString("callState", "")
                + "|" + event.optString("callStateUpdatedAt", "")
                + "|" + event.optString("answeredAt", "")
                + "|" + event.optString("unlockedAt", "");
        return visitors.optBoolean("ringing", false) + "|" + eventState
                + "|" + object(integration, "aptiParking").optBoolean("authenticated", false)
                + "|" + object(integration, "officialSession").optBoolean("authenticated", false)
                + "|" + capabilities.optBoolean("visitorCallControl", false)
                + "|" + capabilities.optBoolean("visitorAudio", false)
                + "|" + capabilities.optBoolean("visitorCentralAudio", false)
                + "|" + capabilities.optBoolean("visitorUnitAudio", false)
                + "|" + capabilities.optBoolean("centralDoorUnlock", false)
                + "|" + capabilities.optBoolean("unitDoorUnlock", false);
    }

    private void updateConnection(boolean serverUp, String error) {
        connection.setText(serverUp ? "집 연결됨" : "연결 안 됨");
        connection.setTextColor(serverUp ? success : danger);
        connection.setContentDescription(error == null ? connection.getText() : error);
    }

    private void reconcilePendingPower() {
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, PendingPower>> entries = pendingPower.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, PendingPower> entry = entries.next();
            Boolean observed = observedPower(entry.getKey());
            if (entry.getValue().accepted && observed != null && observed == entry.getValue().enabled) {
                entries.remove();
            } else if (now - entry.getValue().startedAt >= POWER_CONFIRM_TIMEOUT_MS) {
                entries.remove();
                toast("제어 결과를 확인하지 못했습니다. 실제 기기 상태를 확인해 주세요");
            }
        }
        scheduleRefresh();
    }

    private Boolean observedPower(String path) {
        if (path.equals("/api/batch-switch/power")) {
            return object(status, "batchSwitch").optBoolean("enabled");
        }
        if (path.startsWith("/api/lights/") && path.endsWith("/power")) {
            String key = path.substring("/api/lights/".length(), path.length() - "/power".length());
            JSONObject light = object(object(status, "lights"), key);
            return light.has("enabled") ? light.optBoolean("enabled") : null;
        }
        if (path.startsWith("/api/heating/zones/") && path.endsWith("/power")) {
            String key = path.substring("/api/heating/zones/".length(), path.length() - "/power".length());
            JSONObject zone = object(object(status, "heating"), "zones").optJSONObject(key);
            return zone != null && zone.has("enabled") ? zone.optBoolean("enabled") : null;
        }
        return null;
    }

    private void renderPage() {
        if (page == null) return;
        visitorCallStatusView = null;
        main.removeCallbacks(visitorCallUiTicker);
        final int previousScrollY = resetPageScrollOnRender || pageScroll == null
                ? 0 : pageScroll.getScrollY();
        resetPageScrollOnRender = false;
        page.removeAllViews();
        if (status == null) {
            if (currentTab == VISITORS && parkingHistory != null) {
                page.addView(pageTitle("방문", "서버 연결 중 · 이전 주차 내역을 표시합니다"));
                LinearLayout saved = card();
                renderParkingHistory(saved);
                page.addView(saved);
                restorePageScroll(previousScrollY);
                return;
            }
            page.addView(pageTitle("연결 중", "서버에서 집 상태를 불러오고 있습니다"));
            restorePageScroll(previousScrollY);
            return;
        }
        if (currentTab == HEATING) renderHeating();
        else if (currentTab == LIGHTS) renderLights();
        else if (currentTab == ELEVATOR) renderElevator();
        else if (currentTab == VISITORS) renderVisitors();
        else renderCctv();
        restorePageScroll(previousScrollY);
    }

    private void restorePageScroll(int scrollY) {
        if (pageScroll == null) return;
        pageScroll.post(() -> pageScroll.scrollTo(0, Math.max(0, scrollY)));
    }

    private void renderHeating() {
        page.addView(topStatusRow());
        addGap(page, 10);
        page.addView(batchSwitchCard());
        addGap(page, 10);
        page.addView(pageTitle("난방", "현재온도와 설정온도를 한 화면에서 조절합니다"));
        addGap(page, 10);

        JSONObject zones = object(object(status, "heating"), "zones");
        List<String> keys = sortedKeys(zones);
        if (!keys.isEmpty() && targetAllTemperature == 24) {
            int sum = 0;
            for (String key : keys) sum += object(zones, key).optInt("setpoint", 24);
            targetAllTemperature = Math.round((float) sum / keys.size());
        }
        page.addView(heatingMasterCard(keys));
        addGap(page, 10);
        for (String key : keys) {
            page.addView(zoneRow(object(zones, key)));
            addGap(page, 8);
        }
        if (keys.isEmpty()) page.addView(empty("난방 상태를 기다리고 있습니다"));
    }

    private View topStatusRow() {
        LinearLayout row = horizontal(dp(8));

        JSONObject visitors = object(status, "visitors");
        JSONObject event = visitors.optJSONObject("lastEvent");
        boolean ringing = visitors.optBoolean("ringing", false);
        String visitorDetail = event == null
                ? "최근 호출 없음"
                : event.optString("location", event.optString("title", "현관"));
        Button visitor = compactStatusButton(
                ringing ? "호출 중\n" + visitorDetail : "호출 대기\n" + visitorDetail,
                ringing ? danger : label);
        visitor.setContentDescription("방문 " + (ringing ? "호출 중 " : "호출 대기 ") + visitorDetail);
        visitor.setOnClickListener(v -> selectTab(VISITORS));
        row.addView(visitor, new LinearLayout.LayoutParams(0, dp(78), 1f));

        JSONObject elevator = object(status, "elevator");
        String floor = elevator.optBoolean("available", false)
                ? elevator.optString("displayFloor", "—")
                : "—";
        String elevatorDetail = elevator.optBoolean("fresh", false)
                ? elevator.optBoolean("doorOpen", false)
                    ? "문 열림"
                    : elevator.optString("directionText", "상태 확인 중")
                : "마지막 위치";
        LinearLayout lift = horizontal(dp(8));
        lift.setGravity(Gravity.CENTER_VERTICAL);
        lift.setPadding(dp(12), dp(7), dp(12), dp(7));
        lift.setBackground(rounded(surface, dp(16), separator, 1));
        lift.setClickable(true);
        lift.setFocusable(true);
        LinearLayout floorCopy = vertical(0);
        floorCopy.addView(text("엘리베이터", 11, secondary, Typeface.BOLD));
        LinearLayout floorLine = horizontal(dp(12));
        floorLine.setGravity(Gravity.CENTER_VERTICAL);
        floorLine.addView(text(floor, 30, label, Typeface.BOLD));
        View quickMovement = movingArrows(elevator, 22, 38);
        if (quickMovement != null) floorLine.addView(quickMovement);
        floorCopy.addView(floorLine);
        lift.addView(floorCopy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout liftActions = vertical(dp(2));
        liftActions.setGravity(Gravity.END);
        String pendingCallText = elevator.optString("pendingCallText", "");
        TextView lastPosition = text(
                pendingCallText.isEmpty() ? elevatorDetail : elevatorDetail + " · " + pendingCallText,
                11, secondary, Typeface.NORMAL);
        lastPosition.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        liftActions.addView(lastPosition, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        Button quickCall = button("호출", tint, Color.WHITE);
        quickCall.setContentDescription("엘리베이터를 우리 층으로 호출");
        quickCall.setEnabled(object(status, "capabilities").optBoolean("elevatorCall", false));
        quickCall.setOnClickListener(v -> post(
                "/api/elevator/call", json("action", "call"),
                "엘리베이터 호출 신호를 보냈습니다"));
        liftActions.addView(quickCall, new LinearLayout.LayoutParams(dp(72), dp(44)));
        lift.addView(liftActions, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        lift.setContentDescription("엘리베이터 현재 " + floor + "층 " + elevatorDetail);
        lift.setOnClickListener(v -> selectTab(ELEVATOR));
        LinearLayout.LayoutParams liftParams = new LinearLayout.LayoutParams(0, dp(78), 1f);
        liftParams.setMarginStart(dp(8));
        row.addView(lift, liftParams);
        return row;
    }

    private Button compactStatusButton(String value, int textColor) {
        Button result = button(value, surface, textColor);
        result.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        result.setTextSize(14);
        result.setPadding(dp(12), dp(8), dp(10), dp(8));
        result.setBackground(rounded(surface, dp(16), separator, 1));
        return result;
    }

    private View heatingMasterCard(List<String> zoneKeys) {
        LinearLayout card = card();
        LinearLayout heading = horizontal(dp(8));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = vertical(0);
        copy.addView(text("4개 구역", 11, secondary, Typeface.BOLD));
        copy.addView(text("일괄 제어", 18, label, Typeface.BOLD));
        heading.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button off = button("전체 끄기", soft(danger), danger);
        Button on = button("전체 켜기", tint, Color.WHITE);
        off.setOnClickListener(v -> postHeatingPowerAll(zoneKeys, false));
        on.setOnClickListener(v -> postHeatingPowerAll(zoneKeys, true));
        heading.addView(off, new LinearLayout.LayoutParams(dp(88), dp(44)));
        LinearLayout.LayoutParams onParams = new LinearLayout.LayoutParams(dp(88), dp(44));
        onParams.setMarginStart(dp(6));
        heading.addView(on, onParams);
        card.addView(heading);

        LinearLayout temperature = horizontal(dp(7));
        temperature.setGravity(Gravity.CENTER_VERTICAL);
        temperature.setPadding(0, dp(10), 0, 0);
        Button down = step("−");
        TextView target = text(targetAllTemperature + "°", 23, label, Typeface.BOLD);
        target.setGravity(Gravity.CENTER);
        Button up = step("+");
        Button apply = button("일괄 적용", tint, Color.WHITE);
        down.setOnClickListener(v -> { targetAllTemperature = Math.max(5, targetAllTemperature - 1); target.setText(targetAllTemperature + "°"); });
        up.setOnClickListener(v -> { targetAllTemperature = Math.min(40, targetAllTemperature + 1); target.setText(targetAllTemperature + "°"); });
        apply.setOnClickListener(v -> postHeatingTemperatureAll(zoneKeys, targetAllTemperature));
        temperature.addView(down, new LinearLayout.LayoutParams(dp(44), dp(44)));
        temperature.addView(target, new LinearLayout.LayoutParams(0, dp(44), 1f));
        temperature.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(dp(94), dp(44));
        applyParams.setMarginStart(dp(7));
        temperature.addView(apply, applyParams);
        card.addView(temperature);
        return card;
    }

    private View zoneRow(JSONObject zone) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));

        String name = zone.optString("name", "구역");
        int current = zone.optInt("currentTemperature", -1);
        int setpoint = zone.optInt("setpoint", 24);
        int zoneId = zone.optInt("id", 0);
        String powerPath = "/api/heating/zones/" + zoneId + "/power";
        PendingPower powerRequest = pendingPower.get(powerPath);
        LinearLayout copy = vertical(0);
        copy.addView(text(name, 16, label, Typeface.BOLD));
        copy.addView(text("현재 " + (current < 0 ? "—" : current) + "° · 설정 " + setpoint + "°"
                + (powerRequest == null ? "" : " · 적용 중"), 12, secondary, Typeface.NORMAL));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button down = step("−");
        TextView target = text(setpoint + "°", 17, label, Typeface.BOLD);
        target.setGravity(Gravity.CENTER);
        Button up = step("+");
        down.setContentDescription(name + " 설정온도 내리기");
        up.setContentDescription(name + " 설정온도 올리기");
        down.setOnClickListener(v -> post("/api/heating/zones/" + zoneId + "/setpoint", json("temperature", setpoint - 1), name + " 온도를 내렸습니다"));
        up.setOnClickListener(v -> post("/api/heating/zones/" + zoneId + "/setpoint", json("temperature", setpoint + 1), name + " 온도를 올렸습니다"));
        row.addView(down, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(target, new LinearLayout.LayoutParams(dp(42), dp(44)));
        row.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));

        Switch toggle = toggle(name + " 난방", powerRequest == null
                ? zone.optBoolean("enabled", false) : powerRequest.enabled);
        toggle.setEnabled(powerRequest == null);
        toggle.setOnCheckedChangeListener((button, checked) -> postPower(
                powerPath, checked,
                name + " 난방 " + (checked ? "켜기" : "끄기") + "를 요청했습니다"));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(48)));
        return row;
    }

    private View batchSwitchCard() {
        JSONObject batch = object(status, "batchSwitch");
        boolean enabled = batch.optBoolean("enabled", false);
        String powerPath = "/api/batch-switch/power";
        PendingPower powerRequest = pendingPower.get(powerPath);
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(72));

        TextView icon = text("⌁", 25, tint, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(tintSoft, dp(13), 0, 0));
        card.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout copy = vertical(0);
        copy.setPadding(dp(11), 0, dp(8), 0);
        copy.addView(text("일괄소등", 17, label, Typeface.BOLD));
        copy.addView(text(powerRequest != null ? "적용 중 · 전등·환풍기 등 연결 기기"
                : enabled ? "ON · 전등·환풍기 등 연결 기기" : "OFF · 전등·환풍기 등 연결 기기", 12, secondary, Typeface.NORMAL));
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = toggle("일괄소등", powerRequest == null ? enabled : powerRequest.enabled);
        toggle.setEnabled(powerRequest == null && object(status, "capabilities").optBoolean("batchSwitchControl", false));
        toggle.setOnCheckedChangeListener((button, checked) -> postPower(
                powerPath, checked,
                "일괄소등 " + (checked ? "ON" : "OFF") + "을 요청했습니다"));
        card.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(48)));
        return card;
    }

    private void renderLights() {
        page.addView(pageTitle("전등", "각 조명을 바로 확인하고 켜거나 끕니다"));
        addGap(page, 10);
        JSONObject lights = object(status, "lights");
        List<String> keys = sortedKeys(lights);
        LinearLayout list = card();
        for (int index = 0; index < keys.size(); index++) {
            JSONObject light = object(lights, keys.get(index));
            if (index > 0) list.addView(divider());
            LinearLayout row = horizontal(dp(8));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(54));
            LinearLayout copy = vertical(0);
            String name = light.optString("name", "전등 " + light.optInt("channel"));
            int channel = light.optInt("channel");
            String powerPath = "/api/lights/" + channel + "/power";
            PendingPower powerRequest = pendingPower.get(powerPath);
            copy.addView(text(name, 16, label, Typeface.BOLD));
            copy.addView(text(powerRequest != null ? "적용 중"
                    : light.optBoolean("enabled") ? "켜짐" : "꺼짐", 12, secondary, Typeface.NORMAL));
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Switch toggle = toggle(name + " 전등", powerRequest == null
                    ? light.optBoolean("enabled") : powerRequest.enabled);
            toggle.setEnabled(powerRequest == null);
            toggle.setOnCheckedChangeListener((button, checked) -> postPower(
                    powerPath, checked,
                    name + " 전등 " + (checked ? "켜기" : "끄기") + "를 요청했습니다"));
            row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(48)));
            list.addView(row);
        }
        page.addView(keys.isEmpty() ? empty("전등 상태를 기다리고 있습니다") : list);
    }

    private void renderCctv() {
        page.addView(pageTitle("CCTV", "우리집·놀이터·세대 현관 실시간 영상과 모션 녹화"));
        addGap(page, 10);
        LinearLayout card = card();
        String detail = !cctvError.isEmpty() ? cctvError
                : cctvLoading && cctvShowingCached
                    ? "최근 화면 " + cctvAgeLabel(cctvCachedAgeMs) + " · 새 영상 연결 중"
                : cctvLoading ? "영상 연결 중"
                : cctvChannel < 0 ? "채널을 선택하세요"
                : cctvChannel == 100 ? "세대 현관 · 빠른 확인 · 실시간 갱신"
                : cctvChannel == 101 || cctvChannel == 102 ? cctvChannelName + " · 실시간·모션 녹화"
                : cctvChannelName + " · 실시간 영상";
        TextView state = text(detail, 13, secondary, Typeface.BOLD);
        LinearLayout cameraOptions = horizontal(dp(8));
        cameraOptions.addView(state, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (cctvChannel == 101 || cctvChannel == 102) {
            String camera = cctvChannel == 101 ? "cam1" : "cam2";
            Button options = button("옵션", tintSoft, tint);
            options.setTextSize(14);
            options.setOnClickListener(v -> {
                boolean[] checked = {preferences.getBoolean("cctv_motion_" + camera, false)};
                new AlertDialog.Builder(this).setTitle(cctvChannelName + " 옵션")
                    .setMultiChoiceItems(new String[]{"모션 감지 알림"}, checked, (dialog, which, value) -> checked[0] = value)
                    .setNegativeButton("취소", null)
                    .setPositiveButton("저장", (dialog, which) -> {
                        boolean before = preferences.getBoolean("cctv_motion_" + camera, false);
                        SharedPreferences.Editor editor = preferences.edit().putBoolean("cctv_motion_" + camera, checked[0]);
                        if (checked[0] && !before) editor.putLong("cctv_motion_since_" + camera, System.currentTimeMillis());
                        editor.apply();
                        if (checked[0]) enableVisitorNotifications();
                        else getSystemService(android.app.NotificationManager.class).cancel("cam1".equals(camera) ? 1401 : 1402);
                        Toast.makeText(this, "모션 감지 알림 " + (checked[0] ? "켜짐" : "꺼짐"), Toast.LENGTH_SHORT).show();
                    }).show();
            });
            cameraOptions.addView(options, new LinearLayout.LayoutParams(dp(76), dp(48)));
        }
        card.addView(cameraOptions);

        GridLayout channels = new GridLayout(this);
        channels.setColumnCount(2);
        channels.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        channels.setUseDefaultMargins(false);
        JSONArray configured = object(status, "cctv").optJSONArray("channels");
        JSONArray items = new JSONArray();
        try {
            items.put(new JSONObject().put("id", 101).put("name", "거실 CAM"));
            items.put(new JSONObject().put("id", 102).put("name", "현관 CAM"));
            items.put(new JSONObject().put("id", 100).put("name", "세대 현관"));
        } catch (org.json.JSONException ignored) { }
        if (configured != null) for (int i = 0; i < configured.length(); i++) {
            JSONObject item = configured.optJSONObject(i);
            if (item != null && item.optInt("id") != 100 && item.optInt("id") != 101 && item.optInt("id") != 102) items.put(item);
        }
        if (items != null) {
            int visibleIndex = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                int id = item.optInt("id");
                String name = switch (id) {
                    case 1 -> "꿀벌놀이터1";
                    case 2 -> "꿀벌놀이터2";
                    case 3 -> "캠핑놀이터1";
                    case 4 -> "캠핑놀이터2";
                    default -> item.optString("name", "채널 " + id);
                };
                Button button = button(name, id == cctvChannel ? tint : tintSoft, id == cctvChannel ? Color.WHITE : tint);
                button.setTextSize(14);
                button.setPadding(dp(12), 0, dp(12), 0);
                button.setOnClickListener(v -> loadCctv(id, name));
                boolean lastOdd = items.length() % 2 == 1 && visibleIndex == items.length() - 1;
                int column = lastOdd ? 0 : visibleIndex % 2;
                int row = visibleIndex / 2;
                int span = lastOdd ? 2 : 1;
                GridLayout.LayoutParams buttonParams = new GridLayout.LayoutParams(
                        GridLayout.spec(row), GridLayout.spec(column, span, 1f));
                buttonParams.width = 0;
                buttonParams.height = dp(48);
                buttonParams.setMargins(dp(5), dp(5), dp(5), dp(5));
                buttonParams.setGravity(Gravity.FILL);
                channels.addView(button, buttonParams);
                visibleIndex++;
            }
        }
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        channelParams.topMargin = dp(8);
        channelParams.leftMargin = -dp(5);
        channelParams.rightMargin = -dp(5);
        card.addView(channels, channelParams);

        boolean personalCamera = cctvChannel == 101 || cctvChannel == 102;
        if (!personalCamera && cctvChannel >= 0) {
            cctvPreview = new ImageView(this);
            cctvPreview.setContentDescription(cctvChannelName.isEmpty() ? "CCTV 영상" : cctvChannelName + " CCTV 영상");
            cctvPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cctvPreview.setBackgroundColor(Color.BLACK);
            if (cctvBitmap != null) cctvPreview.setImageBitmap(cctvBitmap);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270));
            previewParams.topMargin = dp(8);
            card.addView(cctvPreview, previewParams);
        } else cctvPreview = null;
        if (personalCctv != null) {
            ViewGroup previousParent = (ViewGroup) personalCctv.getParent();
            if (previousParent != null) previousParent.removeView(personalCctv);
        }
        if (personalCamera) {
            if (personalCctv == null) personalCctv = new PersonalCctvView(this);
            LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, personalCctv.getHeight() > 0 ? personalCctv.getHeight() : dp(450));
            cameraParams.topMargin = dp(12);
            card.addView(personalCctv, cameraParams);
            if (resumed) personalCctv.showCamera(cctvChannel == 101 ? "cam1" : "cam2", apiToken());
        }
        page.addView(card);
    }

    private void loadCctv(int channel, String name) {
        stopCctvStream();
        cctvLoading = true;
        cctvChannel = channel;
        cctvChannelName = name;
        if (channel == 101 || channel == 102) {
            cctvLoading = false;
            cctvError = "";
            cctvBitmap = null;
            renderPage();
            return;
        }
        Bitmap cached = cctvFrameCache.get(channel);
        Long cachedAt = cctvFrameTimes.get(channel);
        cctvShowingCached = cached != null && cachedAt != null
                && SystemClock.elapsedRealtime() - cachedAt <= 30_000L;
        cctvCachedAgeMs = cctvShowingCached
                ? Math.max(0L, SystemClock.elapsedRealtime() - cachedAt) : 0L;
        cctvBitmap = cctvShowingCached ? cached : null;
        cctvError = "";
        renderPage();
        int session = cctvSessionId;
        cctvNetwork.execute(() -> {
            while (session == cctvSessionId && !Thread.currentThread().isInterrupted()) {
                CctvStream stream = new CctvStream(serverUrl(), apiToken(), channel);
                cctvStream = stream;
                try {
                    stream.readFrames((image, sourceAgeMs) -> {
                        if (session != cctvSessionId) return false;
                        main.post(() -> {
                            if (session != cctvSessionId || currentTab != CCTV) return;
                            boolean first = cctvLoading || !cctvError.isEmpty();
                            cctvBitmap = image;
                            cctvFrameCache.put(channel, image);
                            long now = SystemClock.elapsedRealtime();
                            if (sourceAgeMs >= 0L) {
                                cctvFrameTimes.put(channel, now - sourceAgeMs);
                                cctvLoading = true;
                                cctvShowingCached = true;
                                cctvCachedAgeMs = sourceAgeMs;
                            } else {
                                cctvFrameTimes.put(channel, now);
                                cctvLoading = false;
                                cctvShowingCached = false;
                                cctvCachedAgeMs = 0L;
                            }
                            cctvError = "";
                            if (first || sourceAgeMs >= 0L) renderPage();
                            else if (cctvPreview != null) cctvPreview.setImageBitmap(image);
                        });
                        return true;
                    });
                } catch (Exception error) {
                    main.post(() -> {
                        if (session != cctvSessionId || currentTab != CCTV) return;
                        cctvError = "영상 재연결 중: " + message(error);
                        renderPage();
                    });
                } finally {
                    stream.close();
                    if (cctvStream == stream) cctvStream = null;
                }
                if (session != cctvSessionId) return;
                try { Thread.sleep(1000); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        });
    }

    private static String cctvAgeLabel(long ageMs) {
        long seconds = Math.max(0L, ageMs / 1000L);
        if (seconds < 5L) return "방금 전";
        if (seconds < 60L) return seconds + "초 전";
        return Math.max(1L, seconds / 60L) + "분 전";
    }

    private void stopCctvStream() {
        if (personalCctv != null) personalCctv.suspendVideo();
        cctvSessionId++;
        CctvStream stream = cctvStream;
        cctvStream = null;
        if (stream != null) stream.close();
        cctvLoading = false;
    }

    private void renderElevator() {
        page.addView(pageTitle("엘리베이터", "현재 위치와 우리 층 호출 상태"));
        addGap(page, 10);
        JSONObject elevator = object(status, "elevator");
        LinearLayout card = card();
        TextView floor = text(elevator.optBoolean("available") ? elevator.optString("displayFloor", "—") : "—", 50, label, Typeface.BOLD);
        floor.setGravity(Gravity.CENTER);
        LinearLayout floorLine = horizontal(dp(20));
        floorLine.setGravity(Gravity.CENTER);
        floorLine.addView(floor);
        View movement = movingArrows(elevator, 34, 62);
        if (movement != null) floorLine.addView(movement);
        card.addView(floorLine);
        card.addView(centered(elevator.optBoolean("fresh")
                ? elevator.optBoolean("doorOpen", false)
                    ? "문 열림 · 도착 신호"
                    : elevator.optString("directionText")
                : "마지막 위치", 14, secondary));
        card.addView(centered(
                elevator.optString("pendingCallText", "대기 호출층 · 확인 중"),
                13, elevator.optJSONArray("pendingCallFloors") != null
                        && elevator.optJSONArray("pendingCallFloors").length() > 0
                        ? tint : secondary));
        if (object(status, "capabilities").optBoolean("elevatorRealtimeWhenWallpadOff", false)) {
            card.addView(centered("월패드 화면이 꺼져 있어도 위치를 조회합니다", 12, secondary));
        }
        JSONObject elevatorCall = object(status, "elevatorCall");
        long requestedEpoch = elevatorCall.optLong("lastRequestedEpoch", 0);
        boolean recentAppCall = requestedEpoch > 0
                && Math.abs(System.currentTimeMillis() / 1000L - requestedEpoch) <= 300;
        String requestedTarget = elevatorCall.optString("target", "");
        card.addView(centered(
                recentAppCall && !requestedTarget.isEmpty()
                        ? "내 호출 목적지 · " + requestedTarget
                        : elevator.optString("cabinDestinationText", "차내 목적층 · 홈넷 미제공"),
                12, secondary));
        LinearLayout calls = horizontal(dp(8));
        Button call = button("우리 층 호출", tint, Color.WHITE);
        call.setEnabled(object(status, "capabilities").optBoolean("elevatorCall", false));
        call.setOnClickListener(v -> post("/api/elevator/call", json("action", "call"), "우리 층 호출 신호를 보냈습니다"));
        calls.addView(call, new LinearLayout.LayoutParams(0, dp(48), 1f));
        boolean b2CallReady = object(status, "capabilities").optBoolean("elevatorB2Call", false);
        Button b2Call = button("B2 호출", tint, Color.WHITE);
        b2Call.setEnabled(b2CallReady);
        b2Call.setAlpha(b2CallReady ? 1f : 0.45f);
        b2Call.setContentDescription(b2CallReady
                ? "엘리베이터를 지하 2층 승강장으로 호출"
                : "B2 호출 경로 확인 중");
        b2Call.setOnClickListener(v -> post("/api/elevator/b2-call", json("action", "call"), "지하주차장 호출 신호를 보냈습니다"));
        LinearLayout.LayoutParams b2Params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        b2Params.setMarginStart(dp(12));
        calls.addView(b2Call, b2Params);
        LinearLayout.LayoutParams callParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        callParams.topMargin = dp(16);
        card.addView(calls, callParams);
        if (!b2CallReady) {
            card.addView(text("B2 출입단말의 정상 호출 신호를 확인하면 활성화됩니다. 세대층 상향 호출은 B2 명령으로 사용하지 않습니다.", 12, secondary, Typeface.NORMAL));
        }
        card.addView(text("호출 후 우리 층의 문 열림 신호를 도착으로 알립니다", 12, secondary, Typeface.NORMAL));
        page.addView(card);
    }

    private View movingArrows(JSONObject elevator, int width, int height) {
        if (!elevator.optBoolean("fresh", false)) return null;
        if (elevator.optBoolean("doorOpen", false)) return null;
        int direction = elevator.optInt("direction", 0);
        if (direction != 1 && direction != 2) return null;
        ElevatorMovementView arrows = new ElevatorMovementView(this, direction, tint);
        arrows.setLayoutParams(new LinearLayout.LayoutParams(dp(width), dp(height)));
        arrows.setContentDescription(direction == 1 ? "상승 중" : "하강 중");
        return arrows;
    }

    private void renderVisitors() {
        page.addView(pageTitle("방문", "세대현관과 공동현관 호출을 구분합니다"));
        Button doorSettings = button("현관 통화·호출음 설정", tintSoft, tint);
        doorSettings.setOnClickListener(v -> showDoorCallSettings());
        page.addView(doorSettings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        addGap(page, 10);
        JSONObject visitors = object(status, "visitors");
        LinearLayout unitDoorCard = card();
        unitDoorCard.addView(text("세대현관", 18, label, Typeface.BOLD));
        unitDoorOpenButton = button("세대현관 문 열기", tint, Color.WHITE);
        unitDoorOpenButton.setOnClickListener(v -> UnitDoorWidgetProvider.openDoor(this));
        LinearLayout.LayoutParams unitDoorButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        unitDoorButtonParams.topMargin = dp(10);
        unitDoorCard.addView(unitDoorOpenButton, unitDoorButtonParams);
        unitDoorFeedbackView = text("", 12, secondary, Typeface.NORMAL);
        unitDoorCard.addView(unitDoorFeedbackView);
        page.addView(unitDoorCard);
        updateUnitDoorControl();
        addGap(page, 12);
        JSONObject event = visitors.optJSONObject("lastEvent");
        LinearLayout card = card();
        LinearLayout callHeader = horizontal(0);
        callHeader.setGravity(Gravity.CENTER_VERTICAL);
        callHeader.addView(text(visitors.optBoolean("ringing") ? "호출 중" : "호출 대기",
                        18, visitors.optBoolean("ringing") ? danger : label, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button visitorHistory = button("방문 기록", tintSoft, tint);
        visitorHistory.setOnClickListener(v -> showVisitorHistory(visitors));
        callHeader.addView(visitorHistory, new LinearLayout.LayoutParams(dp(96), dp(40)));
        card.addView(callHeader);
        if (event == null) {
            card.addView(text("기록된 방문 호출이 없습니다", 13, secondary, Typeface.NORMAL));
        } else {
            card.addView(text(event.optString("location", event.optString("title", "현관")), 23, label, Typeface.BOLD));
            card.addView(text(event.optString("receivedAt", ""), 12, secondary, Typeface.NORMAL));
            String photoUrl = event.optString("photoUrl", "");
            if (!photoUrl.isEmpty()) {
                ImageView visitorPhoto = new ImageView(this);
                visitorPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
                visitorPhoto.setContentDescription("방문자 사진");
                GradientDrawable background = rounded(surface, dp(18), separator, 0);
                visitorPhoto.setBackground(background);
                visitorPhoto.setClipToOutline(Build.VERSION.SDK_INT >= 21);
                LinearLayout.LayoutParams photoParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(210));
                photoParams.topMargin = dp(12);
                card.addView(visitorPhoto, photoParams);
                loadVisitorPhoto(visitorPhoto, photoUrl);
            }
            boolean callControlReady = object(status, "capabilities").optBoolean("visitorCallControl", false);
            boolean audioReady = visitorAudioReady(event);
            String callState = event.optString("callState", "ringing");
            String eventId = event.optString("id", "");
            boolean callPending = eventId.equals(pendingCallEventId) && !pendingCallAction.isEmpty();
            boolean activeSession = object(status, "visitors").optBoolean("ringing", false);
            if (!activeSession && ("ringing".equals(callState) || "connected".equals(callState))) {
                callState = "ended";
            }
            boolean activelyRinging = activeSession && "ringing".equals(callState);
            boolean activelyConnected = activeSession && "connected".equals(callState);
            boolean activeCall = activelyRinging || activelyConnected;
            String audioState = VisitorAudioService.stateFor(eventId);

            visitorCallStatusView = text(visitorCallStatusLabel(event, callState),
                    17,
                    activelyConnected || callPending ? success : secondary,
                    Typeface.BOLD);
            visitorCallStatusView.setGravity(Gravity.CENTER);
            visitorCallStatusView.setPadding(dp(12), dp(13), dp(12), dp(13));
            visitorCallStatusView.setBackground(rounded(
                    activelyConnected || callPending ? soft(success) : soft(secondary),
                    dp(12), 0, 0));
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stateParams.topMargin = dp(14);
            card.addView(visitorCallStatusView, stateParams);
            if (callPending || (activelyConnected
                    && ("connecting".equals(audioState) || "connected".equals(audioState)))) {
                main.post(visitorCallUiTicker);
            }

            Button talk;
            if (callPending) {
                String pendingLabel = "answer".equals(pendingCallAction) ? "연결 중…" : "종료 중…";
                talk = button(pendingLabel, Color.parseColor(dark ? "#3A3A3C" : "#E5E5EA"), secondary);
                talk.setEnabled(false);
            } else if (activelyConnected) {
                talk = button(audioReady ? "통화 종료" : "월패드 통화 종료", danger, Color.WHITE);
                talk.setEnabled(callControlReady);
                if (callControlReady) {
                    talk.setOnClickListener(v -> controlVisitorCall(event.optString("id", ""), "hangup"));
                }
            } else if (activelyRinging) {
                String answerLabel = audioReady ? "통화 연결" : "월패드 통화 수락";
                talk = button(answerLabel,
                        callControlReady ? success : Color.parseColor(dark ? "#3A3A3C" : "#E5E5EA"),
                        callControlReady ? Color.WHITE : secondary);
                talk.setEnabled(callControlReady);
                if (callControlReady) {
                    talk.setOnClickListener(v -> controlVisitorCall(event.optString("id", ""), "answer"));
                }
            } else {
                talk = button("호출 종료", Color.parseColor(dark ? "#3A3A3C" : "#E5E5EA"), secondary);
                talk.setEnabled(false);
            }
            LinearLayout.LayoutParams talkParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            talkParams.topMargin = dp(16);
            if (activeCall || callPending) card.addView(talk, talkParams);
            String talkHint = !activelyRinging && !activelyConnected
                    ? "종료된 호출입니다"
                    : (audioReady
                    ? ("error".equals(audioState)
                    ? "휴대폰 음성 오류: " + VisitorAudioService.detailFor(eventId)
                    : ("connected".equals(audioState) || "connecting".equals(audioState)
                    ? "휴대폰 마이크와 방문자 음성을 양방향으로 연결합니다"
                    : "휴대폰 음성 중계가 실행되지 않았습니다"))
                    : (activelyConnected
                    ? "월패드만 통화 연결 상태입니다. 휴대폰 음성은 연결되지 않았습니다"
                    : "월패드에서 호출 수락만 가능합니다. 휴대폰 음성은 연결되지 않습니다"));
            if (activeCall || callPending) {
                card.addView(text(talkHint, 12, secondary, Typeface.NORMAL));
            }
            boolean central = "central_entrance".equals(event.optString("kind"));
            boolean ready = (activelyRinging || activelyConnected)
                    && object(status, "capabilities").optBoolean(central ? "centralDoorUnlock" : "unitDoorUnlock", false);
            String unlockLabel = ready ? "문 열기" : (activeCall ? "문 열기 연결 준비 중" : "호출 종료");
            Button unlock = button(unlockLabel,
                    ready ? tint : Color.parseColor(dark ? "#3A3A3C" : "#E5E5EA"),
                    ready ? Color.WHITE : secondary);
            unlock.setEnabled(ready);
            if (ready) unlock.setOnClickListener(v -> confirmUnlock(event));
            LinearLayout.LayoutParams unlockParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            unlockParams.topMargin = dp(16);
            if (activeCall || callPending) card.addView(unlock, unlockParams);
            String unlockHint = ready
                    ? "현재 호출 세션에서 한 번만 실행합니다"
                    : (activeCall
                    ? "이 출입구의 문열림 경로가 아직 준비되지 않았습니다"
                    : "호출이 끝나 문열림을 전송하지 않습니다");
            if (activeCall || callPending) {
                card.addView(text(unlockHint, 12, secondary, Typeface.NORMAL));
            }
        }
        page.addView(card);
        addGap(page, 10);
        page.addView(parkingCard());
    }

    private void updateUnitDoorControl() {
        if (unitDoorOpenButton == null || unitDoorFeedbackView == null) return;
        main.removeCallbacks(unitDoorUiRefresh);
        boolean pending = UnitDoorWidgetProvider.pending(preferences);
        unitDoorOpenButton.setEnabled(!pending
                && !preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN).trim().isEmpty());
        unitDoorOpenButton.setText(pending ? "현관 연결 중…" : "세대현관 문 열기");
        String feedback = UnitDoorWidgetProvider.feedback(preferences);
        unitDoorFeedbackView.setText(feedback.isEmpty() ? "벨을 누르거나 월패드 화면을 켜지 않고 문을 엽니다" : feedback);
        long until = Math.max(preferences.getLong("unit_door_pending_until", 0L),
                preferences.getLong("unit_door_feedback_until", 0L));
        if (resumed && until > System.currentTimeMillis())
            main.postDelayed(unitDoorUiRefresh, until - System.currentTimeMillis() + 50L);
    }

    private void showVisitorHistory(JSONObject visitors) {
        JSONArray history = visitors.optJSONArray("history");
        LinearLayout list = vertical(dp(8));
        list.setPadding(dp(4), dp(4), dp(4), dp(8));
        if (history == null || history.length() == 0) {
            list.addView(text("기록된 방문 호출이 없습니다", 14, secondary, Typeface.NORMAL));
        } else {
            int count = Math.min(20, history.length());
            for (int index = 0; index < count; index++) {
                JSONObject item = history.optJSONObject(index);
                if (item == null) continue;
                if (index > 0) list.addView(divider());
                list.addView(visitorHistoryRow(item, visitors));
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        new AlertDialog.Builder(this)
                .setTitle("방문 기록")
                .setView(scroll)
                .setPositiveButton("닫기", null)
                .show();
    }

    private View visitorHistoryRow(JSONObject item, JSONObject visitors) {
        LinearLayout row = horizontal(dp(10));
        row.setGravity(Gravity.CENTER_VERTICAL);
        String photoUrl = item.optString("photoUrl", "");
        if (!photoUrl.isEmpty()) {
            ImageView photo = new ImageView(this);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo.setContentDescription("방문자 기록 사진");
            photo.setBackground(rounded(surface, dp(10), separator, 1));
            photo.setClipToOutline(Build.VERSION.SDK_INT >= 21);
            row.addView(photo, new LinearLayout.LayoutParams(dp(84), dp(64)));
            loadVisitorPhoto(photo, photoUrl);
        }
        LinearLayout detail = vertical(dp(2));
        String location = item.optString("location", item.optString("title", "현관"));
        detail.addView(text(location, 15, label, Typeface.BOLD));
        String time = formatVisitorTimestamp(item.optString("receivedAt", item.optString("detectedAt", "")));
        detail.addView(text(time + " · " + visitorHistoryState(item, visitors),
                12, visitorHistoryStateColor(item, visitors), Typeface.NORMAL));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!photoUrl.isEmpty()) detailParams.setMarginStart(dp(10));
        row.addView(detail, detailParams);
        row.setPadding(0, dp(8), 0, dp(8));
        return row;
    }

    private String visitorHistoryState(JSONObject item, JSONObject visitors) {
        String id = item.optString("id", "");
        JSONObject last = visitors.optJSONObject("lastEvent");
        if (visitors.optBoolean("ringing", false) && last != null
                && id.equals(last.optString("id", ""))) return "호출 중";
        if (!item.optString("unlockedAt", "").isEmpty())
            return "confirmed".equals(item.optString("autoUnlockStatus")) ? "자동 문 열림" : "문 열림";
        if (!item.optString("answeredAt", "").isEmpty()) return "통화 완료";
        if ("connected".equals(item.optString("callState", ""))) return "통화 연결";
        if (!item.optString("callStateUpdatedAt", "").isEmpty()) return "처리 완료";
        return "부재중";
    }

    private int visitorHistoryStateColor(JSONObject item, JSONObject visitors) {
        String state = visitorHistoryState(item, visitors);
        if ("부재중".equals(state)) return danger;
        if ("호출 중".equals(state)) return tint;
        return success;
    }

    private String formatVisitorTimestamp(String raw) {
        if (raw == null || raw.isEmpty()) return "시간 확인 불가";
        try {
            return OffsetDateTime.parse(raw.replace(" ", "T"))
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(VISITOR_TIME_LABEL);
        } catch (Exception ignored) {
            return raw.length() > 16 ? raw.substring(0, 16).replace('T', ' ') : raw.replace('T', ' ');
        }
    }

    private void loadVisitorPhoto(ImageView target, String photoUrl) {
        target.setTag(photoUrl);
        Bitmap cached = visitorPhotoCache.get(photoUrl);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        visitorPhotoNetwork.execute(() -> {
            try {
                Bitmap image = api.getBitmap(photoUrl);
                visitorPhotoCache.put(photoUrl, image);
                main.post(() -> {
                    Object tag = target.getTag();
                    if (photoUrl.equals(tag) && !image.isRecycled()) {
                        target.setImageBitmap(image);
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private View parkingCard() {
        JSONObject parking = object(object(status, "integration"), "aptiParking");
        boolean authenticated = parking.optBoolean("authenticated", false);
        LinearLayout card = card();
        LinearLayout registrationTitle = horizontal(0);
        registrationTitle.setGravity(Gravity.CENTER_VERTICAL);
        registrationTitle.addView(text("방문차량 등록", 18, label, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        registrationTitle.addView(text(parkingRemainingLabel(), 18, tint, Typeface.BOLD));
        card.addView(registrationTitle);
        card.addView(text("차량번호와 방문일만 입력하면 바로 등록합니다", 12, secondary, Typeface.NORMAL));
        addGap(card, 10);
        AptiConnectionState.State loginState = aptiConnectionState(parking);
        boolean parkingReady = loginState == AptiConnectionState.State.READY;
        int stateColor = parkingReady ? success : Color.rgb(234, 118, 28);
        String stateText = AptiConnectionState.title(loginState);
        card.addView(text(stateText, 13, stateColor, Typeface.BOLD));
        if (parkingReady) {
            card.addView(text(PARKING_VISITOR_NAME + " · " + PARKING_PHONE, 12, secondary, Typeface.NORMAL));
        } else {
            Button connect = button(authenticated ? "아파트아이 연결 확인" : "아파트아이 로그인", tintSoft, tint);
            connect.setOnClickListener(v -> showAptiConnection());
            LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            connectParams.topMargin = dp(8);
            connectParams.bottomMargin = dp(4);
            card.addView(connect, connectParams);
        }
        addGap(card, 8);
        EditText carNumber = new EditText(this);
        carNumber.setHint("차량 번호 · 예: 12가 3456");
        carNumber.setSingleLine(true);
        carNumber.setTextColor(label);
        carNumber.setHintTextColor(secondary);
        carNumber.setBackgroundTintList(ColorStateList.valueOf(tint));
        carNumber.setText(parkingCarNumber);
        carNumber.setSelection(carNumber.getText().length());
        carNumber.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                parkingCarNumber = value.toString();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        card.addView(carNumber, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        addGap(card, 6);
        LinearLayout reservationActions = horizontal(0);
        Button date = button(parkingVisitDate.format(PARKING_DATE_LABEL), tintSoft, tint);
        date.setOnClickListener(v -> chooseParkingDate(date));
        Button register = button("등록", tint, Color.WHITE);
        register.setEnabled(parkingReady);
        register.setAlpha(register.isEnabled() ? 1f : 0.45f);
        register.setOnClickListener(v -> reserveParkingFromPage());
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        dateParams.rightMargin = dp(8);
        reservationActions.addView(date, dateParams);
        reservationActions.addView(register, new LinearLayout.LayoutParams(0, dp(48), 1f));
        card.addView(reservationActions);

        addGap(card, 12);
        card.addView(divider());
        addGap(card, 10);
        LinearLayout historyTitle = horizontal(0);
        historyTitle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button refresh = button("새로고침", tint, Color.WHITE);
        refresh.setEnabled(status != null);
        refresh.setAlpha(status != null ? 1f : 0.45f);
        refresh.setOnClickListener(v -> refreshParkingHistory(true));
        historyTitle.addView(refresh, new LinearLayout.LayoutParams(dp(96), dp(40)));
        card.addView(historyTitle);
        renderParkingHistory(card);
        return card;
    }

    private void renderParkingHistory(LinearLayout card) {
        if (parkingLoading) {
            card.addView(text(parkingHistory == null ? "내역을 불러오는 중입니다"
                    : "이전 내역을 표시합니다 · 새 내역 확인 중", 13, secondary, Typeface.NORMAL));
            if (parkingHistory == null) return;
        }
        if (!parkingError.isEmpty()) {
            card.addView(text(parkingError, 13, danger, Typeface.NORMAL));
            if (parkingHistory == null) return;
        }
        JSONArray reservations = parkingHistory == null ? null : parkingHistory.optJSONArray("visitorReservations");
        JSONArray visitors = parkingHistory == null ? null : parkingHistory.optJSONArray("visitorActivity");
        JSONArray household = parkingHistory == null ? null : parkingHistory.optJSONArray("householdActivity");
        if (reservations == null) reservations = new JSONArray();
        if (visitors == null || household == null) {
            visitors = new JSONArray();
            household = new JSONArray();
            JSONArray legacy = parkingHistory == null ? null : parkingHistory.optJSONArray("activity");
            if (legacy == null && status != null) {
                legacy = object(status, "parkingActivity").optJSONArray("recent");
            }
            if (legacy != null) {
                for (int index = 0; index < legacy.length(); index++) {
                    JSONObject item = legacy.optJSONObject(index);
                    if (item == null) continue;
                    if ("registered".equals(item.optString("vehicleType"))) household.put(item);
                    else visitors.put(item);
                }
            }
        }
        JSONObject sources = parkingHistory == null
                ? new JSONObject() : parkingHistory.optJSONObject("sourceStatus");
        if (sources == null) sources = new JSONObject();
        renderParkingSection(card, "등록 차량 내역", "입차 전 · 길게 눌러 삭제",
                reservations, 3, "방문등록", false, true,
                "현재 입차 전 등록 차량이 없습니다", sources.optJSONObject("apartmenti"));
        addGap(card, 12);
        card.addView(divider());
        addGap(card, 12);
        renderParkingSection(card, "최근 방문차량 입·출차", "실제 입차 기준 · 최근 3건",
                visitors, 3, "방문차량", false, false,
                "최근 방문차량 입·출차 기록이 없습니다", sources.optJSONObject("apartmenti"));
        addGap(card, 12);
        card.addView(divider());
        addGap(card, 12);
        JSONObject wallpadSource = sources.optJSONObject("wallpad");
        boolean wallpadLive = wallpadSource != null && wallpadSource.optBoolean("available", false);
        renderParkingSection(card, "세대보유 차량",
                wallpadLive ? "월패드 실시간 · 최근 7건" : "코맥스 기록 · 최근 7건",
                household, 7, "세대차량", true, false,
                "세대차량 입·출차 내역을 불러오지 못했습니다",
                wallpadLive ? wallpadSource : sources.optJSONObject("commax"));
    }

    private void renderParkingSection(LinearLayout card, String title, String source,
                                      JSONArray items, int limit, String badgeLabel, boolean registered,
                                      boolean cancellable, String emptyMessage,
                                      JSONObject sourceState) {
        LinearLayout heading = horizontal(0);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(title, 15, label, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heading.addView(text(source, 11, secondary, Typeface.NORMAL));
        card.addView(heading);
        addGap(card, 6);
        String sourceError = sourceState != null && !sourceState.optBoolean("available", true)
                ? sourceState.optString("error", "연결 확인 필요") : "";
        int actual = Math.min(items == null ? 0 : items.length(), limit);
        if (!sourceError.isEmpty() && actual > 0) {
            String warning = sourceState != null && sourceState.optBoolean("stale", false)
                    ? "마지막 정상 데이터를 표시합니다 · 로그인 세션 확인 필요"
                    : sourceError;
            card.addView(text(warning, 12, Color.rgb(234, 118, 28), Typeface.NORMAL));
            addGap(card, 6);
        }
        int rows = Math.max(1, actual);
        for (int index = 0; index < rows; index++) {
            if (index > 0) {
                addGap(card, 8);
                card.addView(divider());
                addGap(card, 8);
            }
            JSONObject item = index < actual ? items.optJSONObject(index) : null;
            renderParkingRow(card, item, badgeLabel, registered, cancellable, emptyMessage,
                    item == null && index == 0 ? sourceError : "");
        }
    }

    private void renderParkingRow(LinearLayout card, JSONObject item,
                                  String badgeLabel, boolean registered, boolean cancellable,
                                  String emptyMessage, String sourceError) {
        if (item == null) {
            card.addView(text(sourceError.isEmpty() ? emptyMessage : sourceError,
                    12, sourceError.isEmpty() ? secondary : danger, Typeface.NORMAL));
            return;
        }
        LinearLayout row = vertical(dp(2));
        int badgeColor = registered ? tint : Color.rgb(234, 118, 28);
        LinearLayout top = horizontal(0);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(badgeLabel, 11, badgeColor, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        badge.setBackground(rounded(soft(badgeColor), dp(12), 0, 0));
        top.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String plateText = item.optString(
                "displayCarNumber", item.optString("carNumber", "차량번호 확인 필요"));
        if (plateText.trim().isEmpty()) plateText = "차량번호 확인 필요";
        TextView plate = text(plateText, 16, label, Typeface.BOLD);
        LinearLayout.LayoutParams plateParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plateParams.leftMargin = dp(10);
        top.addView(plate, plateParams);
        String movementText = item.optString("status", "입차 대기");
        if (registered && "주차 중".equals(movementText)) movementText = "입차";
        boolean parked = "주차 중".equals(movementText) || "입차".equals(movementText);
        TextView movement = text(movementText, 13,
                parked ? success : secondary,
                Typeface.BOLD);
        top.addView(movement);
        row.addView(top);
        String entryAt = formatParkingTimestamp(item.optString("entryAt", ""));
        String exitAt = formatParkingTimestamp(item.optString("exitAt", ""));
        String detail;
        if (entryAt.isEmpty()) {
            String visitDate = formatParkingDate(item.optString("visitDate", ""));
            detail = "방문일 " + (visitDate.isEmpty() ? "확인 중" : visitDate)
                    + "  ·  입차 전";
        } else {
            detail = "입차 " + entryAt + "  ·  출차 " + (exitAt.isEmpty() ? "—" : exitAt);
        }
        String visitor = item.optString("visitorName", "").trim();
        if (!registered && !visitor.isEmpty()) detail += " · " + visitor;
        row.addView(text(detail, 12, secondary, Typeface.NORMAL));
        if (cancellable) {
            String reservationId = item.optString("reservationId", "").trim();
            if (!reservationId.isEmpty()) {
                row.setLongClickable(true);
                row.setOnLongClickListener(v -> {
                    confirmCancelParkingReservation(item);
                    return true;
                });
            }
        }
        card.addView(row);
    }

    private void confirmCancelParkingReservation(JSONObject item) {
        String reservationId = item.optString("reservationId", "").trim();
        String carNumber = item.optString("carNumber", "").trim();
        if (reservationId.isEmpty()) {
            toast("삭제할 등록 차량 식별자를 확인하지 못했습니다");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("등록 차량 삭제")
                .setMessage((carNumber.isEmpty() ? "이 방문차량" : carNumber)
                        + " 등록을 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) ->
                        cancelParkingReservation(reservationId, carNumber))
                .show();
    }

    private void cancelParkingReservation(String reservationId, String carNumber) {
        controls.execute(() -> {
            try {
                api.postJson("/api/parking/reservations/" + Uri.encode(reservationId) + "/cancel",
                        new JSONObject());
                main.post(() -> {
                    toast((carNumber.isEmpty() ? "방문차량" : carNumber) + " 등록을 삭제했습니다");
                    refreshParkingHistory(true);
                });
            } catch (Exception error) {
                main.post(() -> new AlertDialog.Builder(this)
                        .setTitle("등록 차량을 삭제하지 못했습니다")
                        .setMessage(message(error))
                        .setPositiveButton("확인", null)
                        .show());
            }
        });
    }

    private String parkingRemainingLabel() {
        JSONObject policy = parkingHistory == null
                ? new JSONObject() : parkingHistory.optJSONObject("parkingPolicy");
        if (policy == null) policy = new JSONObject();
        int total = Math.max(0, policy.optInt("totalMinutes", 100 * 60));
        int remaining = Math.max(0, policy.optInt("remainingMinutes", total));
        return "잔여 " + formatParkingMinutes(remaining);
    }

    private String formatParkingMinutes(int minutes) {
        int safe = Math.max(0, minutes);
        int hours = safe / 60;
        int remainder = safe % 60;
        if (hours > 0 && remainder > 0) return hours + "시간 " + remainder + "분";
        if (hours > 0) return hours + "시간";
        return remainder + "분";
    }

    private void showParkingReservationForm() {
        LinearLayout form = vertical(dp(8));
        form.setPadding(dp(20), dp(4), dp(20), 0);
        form.addView(text(PARKING_VISITOR_NAME + " · " + PARKING_PHONE, 13, secondary, Typeface.NORMAL));
        EditText carNumber = new EditText(this);
        carNumber.setHint("차량 번호 · 예: 12가 3456");
        carNumber.setSingleLine(true);
        carNumber.setText(parkingCarNumber);
        Button date = button(parkingVisitDate.format(PARKING_DATE_LABEL), tintSoft, tint);
        date.setOnClickListener(v -> chooseParkingDate(date));
        form.addView(carNumber, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        form.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        new AlertDialog.Builder(this)
                .setTitle("방문차량 등록")
                .setView(form)
                .setNegativeButton("취소", null)
                .setPositiveButton("다음", (dialog, which) -> {
                    parkingCarNumber = carNumber.getText().toString().trim();
                    confirmParkingReservation();
                })
                .show();
    }

    private void chooseParkingDate(Button target) {
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, year, month, day) -> {
            parkingVisitDate = LocalDate.of(year, month + 1, day);
            target.setText(parkingVisitDate.format(PARKING_DATE_LABEL));
        }, parkingVisitDate.getYear(), parkingVisitDate.getMonthValue() - 1, parkingVisitDate.getDayOfMonth());
        dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis() + 90L * 24 * 60 * 60 * 1000);
        dialog.show();
    }

    private void confirmParkingReservation() {
        String car = parkingCarNumber.trim();
        if (car.replaceAll("\\s", "").length() < 7) {
            toast("차량 번호를 확인해 주세요");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("방문차량 등록")
                .setMessage(car + "\n" + parkingVisitDate.format(PARKING_DATE_LABEL)
                        + "\n" + PARKING_VISITOR_NAME + " · " + PARKING_PHONE)
                .setNegativeButton("취소", null)
                .setPositiveButton("등록", (dialog, which) -> reserveParking(car))
                .show();
    }

    private void reserveParkingFromPage() {
        String car = parkingCarNumber.trim();
        if (car.replaceAll("\\s", "").length() < 7) {
            toast("차량 번호를 확인해 주세요");
            return;
        }
        reserveParking(car);
    }

    private void reserveParking(String carNumber) {
        JSONObject body = new JSONObject();
        try {
            body.put("carNumber", carNumber);
            body.put("visitDate", parkingVisitDate.format(PARKING_DATE));
            body.put("phone", PARKING_PHONE);
        } catch (Exception ignored) { }
        controls.execute(() -> {
            try {
                JSONObject result = api.postJson("/api/parking/reservations", body);
                main.post(() -> {
                    parkingCarNumber = "";
                    addOptimisticParkingReservation(result, carNumber,
                            parkingVisitDate.format(PARKING_DATE));
                    toast("방문차량을 등록했습니다");
                    refreshParkingHistory(true);
                });
            } catch (Exception error) {
                main.post(() -> new AlertDialog.Builder(this)
                        .setTitle("방문차량을 등록하지 못했습니다")
                        .setMessage(message(error))
                        .setPositiveButton("확인", null)
                        .show());
            }
        });
    }

    private void addOptimisticParkingReservation(JSONObject result, String carNumber, String visitDate) {
        if (parkingHistory == null) parkingHistory = new JSONObject();
        JSONArray current = parkingHistory.optJSONArray("visitorReservations");
        if (current == null) current = new JSONArray();
        String normalizedCar = carNumber.replaceAll("\\s", "");
        JSONArray merged = new JSONArray();
        JSONObject pending = new JSONObject();
        try {
            pending.put("id", "local-" + UUID.randomUUID());
            pending.put("notificationId", "local-" + UUID.randomUUID());
            pending.put("reservationId", result.optString("reservationId",
                    result.optString("reservationVisitCarId", "")));
            pending.put("carNumber", normalizedCar);
            pending.put("vehicleType", "visitor");
            pending.put("vehicleTypeLabel", "방문차량");
            pending.put("status", "입차 대기");
            pending.put("entryAt", "");
            pending.put("exitAt", "");
            pending.put("visitDate", visitDate);
            pending.put("visitorName", PARKING_VISITOR_NAME);
            pending.put("source", "apartmenti");
            pending.put("localPending", true);
            pending.put("createdLocalEpoch", System.currentTimeMillis() / 1000L);
            merged.put(pending);
            for (int index = 0; index < current.length(); index++) {
                JSONObject item = current.optJSONObject(index);
                if (item == null || sameParkingReservation(item, normalizedCar, visitDate)) continue;
                merged.put(item);
            }
            parkingHistory.put("visitorReservations", merged);
            parkingLoadedAt = SystemClock.elapsedRealtime();
        } catch (Exception ignored) { }
        renderPage();
    }

    private JSONObject mergeOptimisticParkingReservations(JSONObject fresh) {
        if (parkingHistory == null) return fresh;
        JSONArray previous = parkingHistory.optJSONArray("visitorReservations");
        if (previous == null) return fresh;
        JSONArray received = fresh.optJSONArray("visitorReservations");
        if (received == null) received = new JSONArray();
        JSONArray merged = new JSONArray();
        long now = System.currentTimeMillis() / 1000L;
        for (int index = 0; index < previous.length(); index++) {
            JSONObject item = previous.optJSONObject(index);
            if (item == null || !item.optBoolean("localPending", false)) continue;
            if (now - item.optLong("createdLocalEpoch", 0) > 120L) continue;
            String carNumber = item.optString("carNumber", "");
            String visitDate = item.optString("visitDate", "");
            boolean confirmed = false;
            for (int freshIndex = 0; freshIndex < received.length(); freshIndex++) {
                JSONObject freshItem = received.optJSONObject(freshIndex);
                if (freshItem != null && sameParkingReservation(freshItem, carNumber, visitDate)) {
                    confirmed = true;
                    break;
                }
            }
            if (!confirmed) merged.put(item);
        }
        for (int index = 0; index < received.length(); index++) {
            JSONObject item = received.optJSONObject(index);
            if (item != null) merged.put(item);
        }
        try { fresh.put("visitorReservations", merged); } catch (Exception ignored) { }
        return fresh;
    }

    private boolean sameParkingReservation(JSONObject item, String carNumber, String visitDate) {
        String leftCar = item.optString("carNumber", "").replaceAll("\\s", "");
        String rightCar = carNumber == null ? "" : carNumber.replaceAll("\\s", "");
        return !rightCar.isEmpty() && rightCar.equals(leftCar)
                && (visitDate == null || visitDate.isEmpty()
                || visitDate.equals(item.optString("visitDate", "")));
    }

    private void refreshParkingHistory(boolean force) {
        if (currentTab != VISITORS || parkingLoading || apiToken().isEmpty()) return;
        if (!force && parkingHistory != null
                && SystemClock.elapsedRealtime() - parkingLoadedAt < 20_000L) return;
        boolean hadHistory = parkingHistory != null;
        boolean hadError = !parkingError.isEmpty();
        String previousFingerprint = parkingPayloadFingerprint(parkingHistory);
        parkingLoading = true;
        parkingError = "";
        if (!hadHistory) renderPage();
        final ApiClient requestApi = api;
        network.execute(() -> {
            try {
                JSONObject fetched = requestApi.getJson("/api/parking/activity?months=6");
                main.post(() -> {
                    if (api != requestApi) return;
                    JSONObject value = mergeOptimisticParkingReservations(ParkingHistory.merge(parkingHistory, fetched));
                    parkingLoading = false;
                    boolean changed = !previousFingerprint.equals(parkingPayloadFingerprint(value));
                    parkingHistory = value;
                    parkingLoadedAt = SystemClock.elapsedRealtime();
                    parkingError = "";
                    rememberParkingHistory();
                    if (!hadHistory || hadError || changed) renderPage();
                    else if (force) toast("최신 내역입니다");
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (api != requestApi) return;
                    parkingLoading = false;
                    if (!hadHistory) {
                        parkingError = message(error);
                        renderPage();
                    } else if (force) {
                        toast("내역 갱신 실패: " + message(error));
                    }
                });
            }
        });
    }

    private String parkingPayloadFingerprint(JSONObject value) {
        if (value == null) return "";
        JSONArray visitors = value.optJSONArray("visitorActivity");
        JSONArray reservations = value.optJSONArray("visitorReservations");
        JSONArray household = value.optJSONArray("householdActivity");
        return (visitors == null ? "[]" : visitors.toString())
                + "|" + (reservations == null ? "[]" : reservations.toString())
                + "|" + (household == null ? "[]" : household.toString())
                + "|" + String.valueOf(value.optJSONObject("parkingPolicy"))
                + "|" + parkingSourceFingerprint(value.optJSONObject("sourceStatus"));
    }

    private String parkingSourceFingerprint(JSONObject sources) {
        if (sources == null) return "";
        StringBuilder result = new StringBuilder();
        for (String key : new String[]{"apartmenti", "wallpad", "commax"}) {
            JSONObject source = sources.optJSONObject(key);
            result.append('|').append(key);
            if (source == null) continue;
            result.append(':').append(source.optBoolean("available", false))
                    .append(':').append(source.optBoolean("stale", false))
                    .append(':').append(source.optString("error", ""))
                    .append(':').append(source.optInt("count", 0))
                    .append(':').append(source.optInt("reservationCount", 0))
                    .append(':').append(source.optBoolean("appPackageRequired", false));
        }
        return result.toString();
    }

    private String formatParkingDate(String value) {
        try { return LocalDate.parse(value, PARKING_DATE).format(PARKING_DATE_LABEL); }
        catch (Exception ignored) { return value; }
    }

    private String formatParkingTimestamp(String value) {
        return ParkingHistory.formatTimestamp(value);
    }

    private void restoreParkingHistory() {
        parkingHistory = null;
        parkingLoadedAt = 0;
        parkingLoading = false;
        parkingError = "";
        if (!ParkingHistory.owner(serverUrl(), apiToken()).equals(
                preferences.getString("parking_history_owner", ""))) return;
        try {
            String saved = preferences.getString("parking_history_json", "");
            if (!saved.isEmpty()) parkingHistory = new JSONObject(saved);
        } catch (Exception ignored) { }
    }

    private void rememberParkingHistory() {
        if (parkingHistory == null) return;
        preferences.edit().putString("parking_history_owner", ParkingHistory.owner(serverUrl(), apiToken()))
                .putString("parking_history_json", parkingHistory.toString()).apply();
    }

    private void readLaunchIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(VisitorNotificationService.EXTRA_OPEN_VISITORS, false)) {
            currentTab = VISITORS;
            parkingLoadedAt = 0;
        }
        if (intent.getBooleanExtra(VisitorNotificationService.EXTRA_REQUEST_ANSWER, false)) {
            pendingAnswerEventId = intent.getStringExtra(VisitorNotificationService.EXTRA_VISITOR_EVENT_ID);
            currentTab = VISITORS;
        }
        if (intent.getBooleanExtra(VisitorNotificationService.EXTRA_REQUEST_UNLOCK, false)) {
            pendingUnlockEventId = intent.getStringExtra(VisitorNotificationService.EXTRA_VISITOR_EVENT_ID);
            currentTab = VISITORS;
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_ELEVATOR, false)) {
            currentTab = ELEVATOR;
        }
        String camera = intent.getStringExtra(EXTRA_CCTV_CAMERA);
        if ("cam1".equals(camera) || "cam2".equals(camera)) {
            currentTab = CCTV;
            cctvChannel = "cam1".equals(camera) ? 101 : 102;
            cctvChannelName = "cam1".equals(camera) ? "거실 CAM" : "현관 CAM";
            cctvLoading = false;
            cctvError = "";
        }
    }

    private void maybeStartPendingAnswer() {
        if (pendingAnswerEventId == null || status == null || isFinishing()) return;
        String requestedId = pendingAnswerEventId;
        pendingAnswerEventId = null;
        JSONObject visitors = object(status, "visitors");
        JSONObject event = visitors.optJSONObject("lastEvent");
        if (!visitors.optBoolean("ringing", false)
                || event == null
                || !requestedId.equals(event.optString("id", ""))) {
            new AlertDialog.Builder(this)
                    .setTitle("호출이 종료되었습니다")
                    .setMessage("알림에 있던 호출이 더 이상 활성 상태가 아니어서 통화를 연결하지 않았습니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        if (!object(status, "capabilities").optBoolean("visitorCallControl", false)) {
            JSONObject detail = object(object(status, "capabilityDetails"), "visitorCallControl");
            new AlertDialog.Builder(this)
                    .setTitle("통화 연결 준비 중")
                    .setMessage(detail.optString("reason", "월패드 통화 제어가 아직 준비되지 않았습니다."))
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        controlVisitorCall(requestedId, "answer");
    }

    private void maybeConfirmPendingUnlock() {
        if (pendingUnlockEventId == null || status == null || isFinishing()) return;
        String requestedId = pendingUnlockEventId;
        pendingUnlockEventId = null;
        JSONObject visitors = object(status, "visitors");
        JSONObject event = visitors.optJSONObject("lastEvent");
        if (!visitors.optBoolean("ringing", false)
                || event == null
                || !requestedId.equals(event.optString("id", ""))) {
            new AlertDialog.Builder(this)
                    .setTitle("호출이 종료되었습니다")
                    .setMessage("알림에 있던 호출이 더 이상 활성 상태가 아니어서 문열림 요청을 보내지 않았습니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        boolean central = "central_entrance".equals(event.optString("kind"));
        String capability = central ? "centralDoorUnlock" : "unitDoorUnlock";
        if (!object(status, "capabilities").optBoolean(capability, false)) {
            JSONObject detail = object(object(status, "capabilityDetails"), capability);
            new AlertDialog.Builder(this)
                    .setTitle("문열림 연결 준비 중")
                    .setMessage(detail.optString("reason", "이 출입구의 문열림 장치가 아직 연결되지 않았습니다."))
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        confirmUnlock(event);
    }

    private void confirmUnlock(JSONObject event) {
        String eventId = event.optString("id", "");
        if (eventId.isEmpty()) return;
        String location = event.optString("location", event.optString("title", "현관"));
        new AlertDialog.Builder(this)
                .setTitle(location + " 문 열기")
                .setMessage("현재 진행 중인 방문 호출에만 한 번 전송합니다. 문을 여시겠습니까?")
                .setNegativeButton("취소", null)
                .setPositiveButton("문 열기", (dialog, which) -> unlockVisitorDoor(eventId))
                .show();
    }

    private void unlockVisitorDoor(String eventId) {
        JSONObject body = json("action", "unlock");
        try { body.put("requestId", UUID.randomUUID().toString()); } catch (Exception ignored) { }
        network.execute(() -> {
            try {
                api.postJson("/api/calls/" + eventId + "/unlock", body);
                main.post(() -> {
                    toast("문열림 요청을 한 번 전송했습니다");
                    refreshStatus(false);
                });
            } catch (Exception error) {
                main.post(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("문을 열지 못했습니다")
                            .setMessage(message(error))
                            .setPositiveButton("확인", null)
                            .show();
                    refreshStatus(false);
                });
            }
        });
    }

    private void controlVisitorCall(String eventId, String action) {
        if (eventId.isEmpty() || !pendingCallAction.isEmpty()) return;
        if ("answer".equals(action)
                && visitorAudioReady(eventId)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicrophoneAnswerEventId = eventId;
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_VISITOR_MICROPHONE);
            return;
        }
        if ("hangup".equals(action)) VisitorAudioService.stop(this, eventId);
        pendingCallEventId = eventId;
        pendingCallAction = action;
        pendingCallStartedAt = SystemClock.elapsedRealtime();
        renderPage();
        JSONObject body = json("action", action);
        try { body.put("requestId", UUID.randomUUID().toString()); } catch (Exception ignored) { }
        network.execute(() -> {
            try {
                JSONObject response = api.postJson("/api/calls/" + eventId + "/" + action, body);
                main.post(() -> {
                    clearPendingCall(eventId, action);
                    String nextState = response.optString("callState",
                            "answer".equals(action) ? "connected" : "ended");
                    updateLocalCallState(eventId, nextState);
                    if ("answer".equals(action) && visitorAudioReady(eventId)) {
                        VisitorAudioService.start(this, eventId);
                        toast("월패드 통화 수락 · 휴대폰 음성 연결 중");
                    } else {
                        toast("answer".equals(action) ? "통화를 연결했습니다" : "통화를 종료했습니다");
                    }
                    renderPage();
                    refreshStatus(false);
                });
            } catch (Exception error) {
                main.post(() -> {
                    clearPendingCall(eventId, action);
                    renderPage();
                    new AlertDialog.Builder(this)
                            .setTitle("answer".equals(action)
                                    ? "통화를 연결하지 못했습니다"
                                    : "통화를 종료하지 못했습니다")
                            .setMessage(message(error))
                            .setPositiveButton("확인", null)
                            .show();
                    refreshStatus(false);
                });
            }
        });
    }

    private void clearPendingCall(String eventId, String action) {
        if (eventId.equals(pendingCallEventId) && action.equals(pendingCallAction)) {
            pendingCallEventId = "";
            pendingCallAction = "";
            pendingCallStartedAt = 0;
        }
    }

    private void updateLocalCallState(String eventId, String callState) {
        JSONObject visitors = object(status, "visitors");
        JSONObject event = visitors.optJSONObject("lastEvent");
        if (event == null || !eventId.equals(event.optString("id", ""))) return;
        try {
            event.put("callState", callState);
            visitors.put("ringing", !"ended".equals(callState));
        } catch (Exception ignored) { }
        if ("connected".equals(callState)) {
            connectedCallEventId = eventId;
            connectedCallStartedAt = SystemClock.elapsedRealtime();
        } else if (eventId.equals(connectedCallEventId)) {
            connectedCallEventId = "";
            connectedCallStartedAt = 0;
        }
    }

    private String visitorCallStatusLabel(JSONObject event, String callState) {
        String eventId = event.optString("id", "");
        if (eventId.equals(pendingCallEventId) && !pendingCallAction.isEmpty()) {
            long seconds = Math.max(0, (SystemClock.elapsedRealtime() - pendingCallStartedAt) / 1000);
            return "answer".equals(pendingCallAction)
                    ? "● 통화 연결 중 · " + seconds + "초"
                    : "● 통화 종료 중 · " + seconds + "초";
        }
        if ("connected".equals(callState)) {
            if (!visitorAudioReady(event)) return "● 월패드 통화 수락됨 · 휴대폰 음성 미연결";
            String audioState = VisitorAudioService.stateFor(eventId);
            if ("connecting".equals(audioState)) return "● 휴대폰 음성 연결 중…";
            if ("error".equals(audioState)) return "● 월패드 통화 연결 · 휴대폰 음성 오류";
            if (!"connected".equals(audioState)) return "● 월패드 통화 연결";
            if (!eventId.equals(connectedCallEventId) || connectedCallStartedAt == 0) {
                connectedCallEventId = eventId;
                connectedCallStartedAt = SystemClock.elapsedRealtime();
            }
            long audioStartedAt = VisitorAudioService.connectedAtFor(eventId);
            if (audioStartedAt > 0) connectedCallStartedAt = audioStartedAt;
            long elapsed = Math.max(0, (SystemClock.elapsedRealtime() - connectedCallStartedAt) / 1000);
            setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
            return String.format(Locale.KOREA, "● 스피커폰 통화 중 · %02d:%02d", elapsed / 60, elapsed % 60);
        }
        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
        if ("ringing".equals(callState)) return "● 통화 연결 대기";
        return "호출 종료";
    }

    private boolean visitorAudioReady(JSONObject event) {
        if (event == null) return false;
        JSONObject capabilities = object(status, "capabilities");
        String key = "unit_entrance".equals(event.optString("kind", ""))
                ? "visitorUnitAudio" : "visitorCentralAudio";
        return capabilities.optBoolean(key, capabilities.optBoolean("visitorAudio", false));
    }

    private boolean visitorAudioReady(String eventId) {
        JSONObject event = object(object(status, "visitors"), "lastEvent");
        return eventId != null && eventId.equals(event.optString("id", ""))
                && visitorAudioReady(event);
    }

    private void postHeatingPowerAll(List<String> keys, boolean enabled) {
        controls.execute(() -> {
            try {
                for (String key : keys) api.postJson("/api/heating/zones/" + key + "/power", json("enabled", enabled));
                main.post(() -> { toast("전체 난방 " + (enabled ? "켜기" : "끄기") + "를 요청했습니다"); refreshStatus(false); });
            } catch (Exception error) { main.post(() -> toast(message(error))); }
        });
    }

    private void postHeatingTemperatureAll(List<String> keys, int temperature) {
        controls.execute(() -> {
            try {
                for (String key : keys) api.postJson("/api/heating/zones/" + key + "/setpoint", json("temperature", temperature));
                main.post(() -> { toast("4개 구역에 " + temperature + "°를 적용했습니다"); refreshStatus(false); });
            } catch (Exception error) { main.post(() -> toast(message(error))); }
        });
    }

    private void post(String path, JSONObject body, String successMessage) {
        controls.execute(() -> {
            try {
                api.postJson(path, body);
                main.post(() -> { toast(successMessage); main.postDelayed(() -> refreshStatus(false), 450); });
            } catch (Exception error) { main.post(() -> { toast(message(error)); refreshStatus(false); }); }
        });
    }

    private void postPower(String path, boolean enabled, String successMessage) {
        PendingPower request = new PendingPower(enabled);
        pendingPower.put(path, request);
        renderPage();
        scheduleRefresh();
        controls.execute(() -> {
            try {
                api.postJson(path, json("enabled", enabled));
                main.post(() -> {
                    request.accepted = true;
                    toast(successMessage);
                    main.postDelayed(() -> refreshStatus(false), 700);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (pendingPower.get(path) == request) pendingPower.remove(path);
                    renderPage();
                    scheduleRefresh();
                    toast(message(error));
                    refreshStatus(false);
                });
            }
        });
    }

    private void showSettings() {
        int dialogLabel = Color.rgb(28, 28, 30);
        int dialogSecondary = Color.rgb(99, 99, 102);
        LinearLayout form = vertical(dp(10));
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText server = new EditText(this);
        server.setHint("서버 주소");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(serverUrl());
        EditText token = new EditText(this);
        token.setHint("API 토큰");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(apiToken());
        TextView officialTitle = text("코맥스 공식 계정", 16, dialogLabel, Typeface.BOLD);
        officialTitle.setPadding(0, dp(14), 0, 0);
        JSONObject official = object(object(status, "integration"), "officialSession");
        boolean officialAuthenticated = official.optBoolean("authenticated", false);
        boolean officialSaved = credentialStore.hasCredentials();
        TextView officialState = text(officialAuthenticated
                ? "로그인 · 장치 " + official.optInt("deviceCount", 0) + "개"
                        + (officialSaved ? " · 정보 저장됨" : "")
                : officialSaved ? "비로그인 · 저장 계정으로 자동 재연결합니다"
                        : "비로그인 · 아이디와 비밀번호가 필요합니다",
                12, officialAuthenticated ? Color.rgb(34, 197, 94) : dialogSecondary, Typeface.NORMAL);
        CredentialStore.Credentials savedOfficial = null;
        try {
            savedOfficial = credentialStore.load();
        } catch (Exception ignored) {
            credentialStore.clear();
            officialSaved = false;
        }
        EditText officialUser = new EditText(this);
        officialUser.setHint("코맥스 앱 아이디");
        officialUser.setSingleLine(true);
        officialUser.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        officialUser.setText(savedOfficial == null ? "" : savedOfficial.username);
        EditText officialPassword = new EditText(this);
        officialPassword.setHint("코맥스 앱 비밀번호");
        officialPassword.setSingleLine(true);
        officialPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        officialPassword.setText(savedOfficial == null ? "" : savedOfficial.password);
        CheckBox rememberOfficial = new CheckBox(this);
        rememberOfficial.setText("정보 저장 · 이 기기의 보안 저장소 사용");
        rememberOfficial.setTextColor(dialogLabel);
        rememberOfficial.setChecked(officialSaved);
        TextView aptiTitle = text("아파트아이 방문차량", 16, dialogLabel, Typeface.BOLD);
        aptiTitle.setPadding(0, dp(14), 0, 0);
        JSONObject apti = object(object(status, "integration"), "aptiParking");
        AptiConnectionState.State aptiLoginState = aptiConnectionState(apti);
        TextView aptiState = text(AptiConnectionState.title(aptiLoginState),
                14, aptiLoginState == AptiConnectionState.State.READY
                        ? Color.rgb(22, 122, 62) : dialogSecondary, Typeface.BOLD);
        final AlertDialog[] settingsDialog = new AlertDialog[1];
        Button aptiManage = button("아파트아이 로그인·연결 관리", Color.rgb(232, 243, 255), Color.rgb(5, 98, 201));
        aptiManage.setOnClickListener(v -> {
            settingsDialog[0].dismiss();
            showAptiConnection();
        });
        boolean aptiInstalled = getPackageManager().getLaunchIntentForPackage("aptip.app") != null;
        boolean aptiNotificationAccess = aptiInstalled && aptiNotificationAccessEnabled();
        TextView aptiNotificationState = text(
                aptiNotificationAccess
                        ? "세대차량 입·출차 알림 연동됨"
                        : aptiInstalled ? "아파트아이 차량 알림 전달 · 권한 설정 필요"
                        : "아파트아이 앱이 없어 차량 알림 전달은 사용하지 않습니다",
                12,
                aptiNotificationAccess ? Color.rgb(34, 197, 94) : dialogSecondary,
                Typeface.NORMAL);
        Button aptiNotificationSettings = button(
                aptiNotificationAccess ? "차량 알림 연동됨" : "차량 알림 권한 설정",
                aptiNotificationAccess ? Color.rgb(34, 197, 94) : Color.rgb(10, 132, 255),
                Color.WHITE);
        aptiNotificationSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception error) {
                toast("알림 읽기 권한 설정을 열지 못했습니다");
            }
        });
        form.addView(server, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        form.addView(token, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        form.addView(officialTitle);
        form.addView(officialState);
        form.addView(officialUser, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        form.addView(officialPassword, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        form.addView(rememberOfficial, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        Button doorSettings = button("현관 통화·호출음", Color.rgb(232, 243, 255), Color.rgb(5, 98, 201));
        doorSettings.setOnClickListener(v -> {
            settingsDialog[0].dismiss();
            showDoorCallSettings();
        });
        LinearLayout.LayoutParams doorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        doorParams.topMargin = dp(12);
        form.addView(doorSettings, doorParams);
        form.addView(aptiTitle);
        form.addView(aptiState);
        LinearLayout.LayoutParams aptiManageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        aptiManageParams.topMargin = dp(8);
        aptiManageParams.bottomMargin = dp(12);
        form.addView(aptiManage, aptiManageParams);
        form.addView(text("차량 알림 전달 · 로그인과 별도", 13, dialogLabel, Typeface.BOLD));
        form.addView(aptiNotificationState);
        form.addView(aptiNotificationSettings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        settingsDialog[0] = new AlertDialog.Builder(this)
                .setTitle("연결 및 로그인 설정")
                .setMessage("코맥스는 집 제어, 아파트아이는 방문차량 등록에 사용합니다. 로그인 상태는 각각 확인합니다.")
                .setView(formScroll)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (dialog, which) -> {
                    String url = server.getText().toString().trim().replaceAll("/+$", "");
                    String value = token.getText().toString().trim();
                    String commaxUser = officialUser.getText().toString().trim();
                    String commaxPassword = officialPassword.getText().toString();
                    boolean saveCommax = rememberOfficial.isChecked();
                    preferences.edit().putString("server", url).putString("token", value)
                            .remove("official_user").apply();
                    if (!saveCommax) credentialStore.clear();
                    api = new ApiClient(url, value);
                    restoreParkingHistory();
                    status = null;
                    autoOfficialAttempted = false;
                    renderPage();
                    refreshStatus(true);
                    enableVisitorNotifications();
                    if (!commaxUser.isEmpty() && !commaxPassword.isEmpty()) {
                        enrollOfficial(commaxUser, commaxPassword, saveCommax);
                    } else if (saveCommax) {
                        toast("저장할 코맥스 아이디와 비밀번호를 입력해 주세요");
                    }
                })
                .create();
        settingsDialog[0].show();
    }

    private void showDoorCallSettings() {
        new DoorCallSettingsDialog(this, api, controls, main).show();
    }

    private void enrollOfficial(String username, String password, boolean saveCredentials) {
        if (enrollingOfficial) return;
        enrollingOfficial = true;
        controls.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);
                JSONObject result = api.postJson("/api/official/enroll", body);
                int count = result.optInt("deviceCount", 0);
                Exception saveError = null;
                if (saveCredentials) {
                    try {
                        credentialStore.save(username, password);
                    } catch (Exception error) {
                        saveError = error;
                    }
                }
                Exception finalSaveError = saveError;
                main.post(() -> {
                    enrollingOfficial = false;
                    toast("코맥스 공식 계정 연결됨 · 장치 " + count + "개");
                    if (finalSaveError != null) {
                        new AlertDialog.Builder(this)
                                .setTitle("로그인 정보는 저장되지 않았습니다")
                                .setMessage("서버 로그인에는 성공했지만 기기 보안 저장소에 계정을 보관하지 못했습니다.")
                                .setPositiveButton("확인", null)
                                .show();
                    }
                    refreshStatus(false);
                });
            } catch (Exception error) {
                main.post(() -> {
                    enrollingOfficial = false;
                    new AlertDialog.Builder(this)
                            .setTitle("코맥스 계정을 연결하지 못했습니다")
                            .setMessage(message(error))
                            .setPositiveButton("확인", null)
                            .show();
                });
            }
        });
    }

    private void maybeAutoEnrollOfficial() {
        if (autoOfficialAttempted || enrollingOfficial || status == null) return;
        JSONObject official = object(object(status, "integration"), "officialSession");
        if (official.optBoolean("authenticated", false) || !credentialStore.hasCredentials()) return;
        autoOfficialAttempted = true;
        try {
            CredentialStore.Credentials saved = credentialStore.load();
            if (saved != null && !saved.username.isEmpty() && !saved.password.isEmpty()) {
                enrollOfficial(saved.username, saved.password, true);
            }
        } catch (Exception error) {
            credentialStore.clear();
            toast("저장된 코맥스 로그인 정보를 읽지 못해 삭제했습니다");
        }
    }

    private AptiConnectionState.State aptiConnectionState(JSONObject parking) {
        return AptiConnectionState.resolve(parking.optBoolean("available", false),
                parking.optBoolean("authenticated", false),
                parking.optBoolean("apiVerified", false), parking.optString("apiError", ""));
    }

    private void showAptiConnection() {
        if (apiToken().isEmpty()) {
            toast("먼저 서버 연결 정보를 저장해 주세요");
            showSettings();
            return;
        }
        final ApiClient loginApi = api;
        new AptiLoginDialog(this, loginApi, main, controls,
                object(object(status, "integration"), "aptiParking"), dark, value -> {
                    if (api != loginApi) return;
                    parkingLoadedAt = 0;
                    applyStatus(value);
                    if (currentTab == VISITORS) refreshParkingHistory(true);
                }).show();
    }

    private void enableVisitorNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        VisitorNotificationService.restart(this);
    }

    private boolean aptiNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private String serverUrl() { return preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL); }
    private String apiToken() { return preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN); }

    private JSONObject object(JSONObject source, String name) {
        JSONObject value = source == null ? null : source.optJSONObject(name);
        return value == null ? new JSONObject() : value;
    }

    private List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys, (left, right) -> Integer.compare(parseInt(left), parseInt(right)));
        return keys;
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private JSONObject json(String key, Object value) {
        JSONObject object = new JSONObject();
        try { object.put(key, value); } catch (Exception ignored) { }
        return object;
    }

    private LinearLayout pageTitle(String title, String subtitle) {
        LinearLayout block = vertical(2);
        block.addView(text(title, 25, label, Typeface.BOLD));
        block.addView(text(subtitle, 13, secondary, Typeface.NORMAL));
        return block;
    }

    private LinearLayout card() {
        LinearLayout card = vertical(dp(6));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(surface, dp(18), separator, 1));
        return card;
    }

    private View empty(String value) {
        TextView text = centered(value, 14, secondary);
        text.setMinHeight(dp(90));
        text.setBackground(rounded(surface, dp(18), separator, 1));
        return text;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(separator);
        line.setAlpha(.65f);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return line;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView centered(String value, float size, int color) {
        TextView view = text(value, size, color, Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(background, dp(12), 0, 0));
        return button;
    }

    private Button step(String value) {
        Button button = button(value, tintSoft, tint);
        button.setTextSize(22);
        return button;
    }

    @SuppressWarnings("deprecation")
    private Switch toggle(String description, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText("");
        toggle.setContentDescription(description + (checked ? " 켜짐" : " 꺼짐"));
        toggle.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, Color.WHITE}));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{success, Color.parseColor(dark ? "#48484A" : "#D1D1D6")}));
        toggle.setGravity(Gravity.CENTER);
        return toggle;
    }

    private LinearLayout vertical(int spacing) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (Build.VERSION.SDK_INT >= 35) layout.setDividerPadding(spacing);
        return layout;
    }

    private LinearLayout horizontal(int spacing) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    private int soft(int color) {
        int alpha = dark ? 50 : 25;
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void addGap(LinearLayout layout, int dp) {
        View gap = new View(this);
        layout.addView(gap, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? "요청을 처리하지 못했습니다" : value;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
