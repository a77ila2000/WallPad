package com.homepad.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** A focused login flow; it never launches or requires aptip.app. */
final class AptiLoginDialog {
    private static final String NAVER_URL = "https://www.apti.co.kr/includes/bridge.asp?outer=N&state1=I&state2=&state3=&state4=&state5=L&state6=&state7=www.apti.co.kr";
    private final Activity activity;
    private final ApiClient api;
    private final Handler main;
    private final ExecutorService executor;
    private final Consumer<JSONObject> onStatus;
    private final int surface, label, secondary, accent, accentSoft, good, warning, dialogTheme;
    private AlertDialog dialog;
    private Dialog webDialog;
    private JSONObject session;
    private TextView title, detail, lastCheck;
    private final TextView[] steps = new TextView[3];
    private Button naver, check, alternate;
    private ProgressBar progress;
    private boolean busy;
    private int stage;
    private String localError = "";
    private String notice = "";

    AptiLoginDialog(Activity activity, ApiClient api, Handler main, ExecutorService executor,
                    JSONObject initial, boolean dark, Consumer<JSONObject> onStatus) {
        this.activity = activity;
        this.api = api;
        this.main = main;
        this.executor = executor;
        this.session = initial;
        this.onStatus = onStatus;
        dialogTheme = dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        surface = Color.parseColor(dark ? "#1C1C1E" : "#FFFFFF");
        label = Color.parseColor(dark ? "#F2F2F7" : "#1C1C1E");
        secondary = Color.parseColor(dark ? "#AEAEB2" : "#636366");
        accent = Color.parseColor(dark ? "#89BCFF" : "#0562C9");
        accentSoft = Color.parseColor(dark ? "#173653" : "#E8F3FF");
        good = Color.parseColor(dark ? "#60D889" : "#167A3E");
        warning = Color.parseColor(dark ? "#FFB96B" : "#9C4D00");
    }

    void show() {
        LinearLayout content = column(20);
        content.setBackgroundColor(surface);
        content.addView(text("방문차량 등록과 내역 조회를 위한 로그인입니다.", 14, secondary, false));
        title = text("", 18, label, true);
        title.setPadding(0, dp(20), 0, dp(8));
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        detail = text("", 14, secondary, false);
        lastCheck = text("", 12, secondary, false);
        lastCheck.setPadding(0, dp(8), 0, dp(16));
        content.addView(title);
        content.addView(detail);
        content.addView(lastCheck);
        LinearLayout stepBox = column(14);
        stepBox.setBackground(background(accentSoft));
        for (int i = 0; i < steps.length; i++) {
            steps[i] = text("", 14, secondary, i == 2);
            steps[i].setPadding(0, dp(6), 0, dp(6));
            stepBox.addView(steps[i]);
        }
        content.addView(stepBox);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        content.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
        naver = action("네이버로 로그인", Color.parseColor("#03C75A"), Color.parseColor("#062E16"));
        naver.setOnClickListener(v -> openNaver());
        addAction(content, naver);
        check = action("연결 상태 확인", accentSoft, accent);
        check.setOnClickListener(v -> checkConnection());
        addAction(content, check);
        alternate = action("아파트아이 자체 아이디로 로그인", surface, accent);
        alternate.setOnClickListener(v -> openAccountLogin());
        addAction(content, alternate);
        TextView help = text("아파트아이 앱을 설치하지 않아도 로그인할 수 있습니다.\n네이버 비밀번호는 네이버 로그인 화면에서만 입력합니다.", 12, secondary, false);
        help.setPadding(0, dp(12), 0, 0);
        content.addView(help);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog = new AlertDialog.Builder(activity, dialogTheme).setTitle("아파트아이 연결")
                .setView(scroll).setPositiveButton("닫기", null).create();
        dialog.setOnDismissListener(value -> { if (webDialog != null) webDialog.dismiss(); });
        dialog.show();
        draw();
        // Check the current server state instead of silently trusting a cached screen.
        checkConnection();
    }

    private boolean visible() {
        return dialog != null && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed();
    }

    private boolean connected() {
        return session.optBoolean("connected", session.optBoolean("authenticated", false));
    }

    private boolean enabled() {
        return session.optBoolean("enabled", session.optBoolean("available", true));
    }

    private void draw() {
        if (!visible()) return;
        String error = localError.isEmpty() ? session.optString("apiError", "") : localError;
        AptiConnectionState.State state = AptiConnectionState.resolve(
                enabled(), connected(), session.optBoolean("apiVerified", false), error);
        String method = "naver".equals(session.optString("loginMethod")) ? "네이버" : "아파트아이";
        boolean ready = state == AptiConnectionState.State.READY;
        String[] waiting = {"네이버 로그인 화면에서 인증해 주세요.", "아파트아이 계정을 연결하고 있습니다.", "실제 주차 내역을 조회해 연결을 확인하고 있습니다."};
        title.setText(busy ? (stage == 1 ? "네이버 인증 중" : stage == 2 ? "아파트아이 연결 중" : "주차 기능 확인 중")
                : AptiConnectionState.title(state));
        title.setTextColor(busy ? accent : ready ? good : connected() || !error.isEmpty() ? warning : label);
        detail.setText(busy ? waiting[Math.max(0, stage - 1)] : AptiConnectionState.detail(state)
                + (error.isEmpty() ? "" : "\n" + error) + (notice.isEmpty() ? "" : "\n" + notice));
        boolean confirmed = ready && !busy;
        steps[0].setText((confirmed ? "✓ " : "1  ") + "계정 인증" + (confirmed ? " · " + method + " 로그인 확인" : connected() && stage != 1 ? " · 저장된 " + method + " 정보 있음" : ""));
        steps[1].setText((confirmed ? "✓ " : "2  ") + "아파트아이 연결" + (confirmed ? " · 완료" : connected() && stage != 1 ? " · 확인 필요" : ""));
        steps[2].setText((ready && !busy ? "✓ " : "3  ") + "주차 기능 확인" + (ready && !busy ? " · 등록·조회 가능" : ""));
        long checkedAt = session.optLong("apiCheckedAt", 0);
        lastCheck.setText(checkedAt > 0 ? "최근 확인 " + DateTimeFormatter.ofPattern("MM.dd HH:mm").format(
                Instant.ofEpochSecond(checkedAt).atZone(ZoneId.systemDefault())) : "아직 주차 연결을 확인하지 않았습니다");
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        naver.setText(connected() ? "네이버로 다시 로그인" : "네이버로 로그인");
        naver.setEnabled(!busy && enabled());
        check.setText(busy ? "확인 중…" : "연결 상태 확인");
        check.setEnabled(!busy);
        alternate.setEnabled(!busy && enabled());
        naver.setAlpha(naver.isEnabled() ? 1f : 0.5f);
        check.setAlpha(check.isEnabled() ? 1f : 0.5f);
        alternate.setAlpha(alternate.isEnabled() ? 1f : 0.5f);
    }

    private void checkConnection() {
        if (busy) return;
        busy = true;
        stage = 3;
        localError = "";
        notice = "";
        draw();
        executor.execute(() -> {
            try {
                JSONObject value = api.getJson("/api/parking/status");
                main.post(() -> { session = value; draw(); });
                if (value.optBoolean("enabled", false) && value.optBoolean("connected", false)) {
                    verifyParking();
                }
                finishStatus("");
            } catch (Exception error) { finishStatus(errorMessage(error)); }
        });
    }

    private void verifyParking() throws Exception {
        // This endpoint performs a fresh read, without accepting cached activity as proof.
        api.getJson("/api/parking/history?month=" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM")), 35000);
    }

    private void finishStatus(String error) {
        JSONObject latest = null;
        JSONObject summary = null;
        try { summary = api.getJson("/api/parking/status"); } catch (Exception ignored) { }
        try { latest = api.getJson("/api/status"); } catch (Exception ignored) { }
        final JSONObject current = summary;
        final JSONObject home = latest;
        main.post(() -> {
            busy = false;
            stage = 0;
            localError = error;
            if (current != null) session = current;
            if (home != null && !activity.isDestroyed()) onStatus.accept(home);
            draw();
        });
    }

    private void enroll(String account, String password, String method) {
        stage = 2;
        localError = "";
        notice = "";
        draw();
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("account", account).put("password", password).put("method", method);
                JSONObject value = api.postJson("/api/parking/enroll", body, 25000);
                main.post(() -> { session = value; stage = 3; draw(); });
                verifyParking();
                finishStatus("");
            } catch (Exception error) { finishStatus(errorMessage(error)); }
        });
    }

    private void openAccountLogin() {
        LinearLayout form = column(20);
        EditText account = new EditText(activity);
        account.setHint("아파트아이 자체 아이디");
        account.setSingleLine(true);
        account.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = new EditText(activity);
        password.setHint("아파트아이 비밀번호");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(account);
        form.addView(password);
        AlertDialog entry = new AlertDialog.Builder(activity, dialogTheme).setTitle("아이디로 로그인")
                .setMessage("아파트아이 자체 계정이 있을 때만 사용하세요. 비밀번호는 저장하지 않습니다.")
                .setView(form).setNegativeButton("취소", null).setPositiveButton("로그인", null).create();
        entry.setOnShowListener(d -> entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String user = account.getText().toString().trim();
            String pass = password.getText().toString();
            if (user.isEmpty()) { account.setError("아이디를 입력해 주세요"); return; }
            if (pass.isEmpty()) { password.setError("비밀번호를 입력해 주세요"); return; }
            password.setText("");
            entry.dismiss();
            busy = true;
            enroll(user, pass, "id");
        }));
        entry.show();
    }

    private void openNaver() {
        if (busy) return;
        busy = true;
        stage = 1;
        localError = "";
        notice = "";
        draw();
        LinearLayout content = column(12);
        content.setBackgroundColor(surface);
        TextView heading = text("네이버로 로그인", 20, label, true);
        heading.setPadding(dp(4), dp(8), dp(4), dp(8));
        content.addView(heading);
        TextView instruction = text("1/3 네이버 인증 · 아파트아이에 가입한 계정을 사용하세요", 14, label, true);
        instruction.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.addView(instruction);
        ProgressBar pageProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        content.addView(pageProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        WebView web = new WebView(activity);
        web.setBackgroundColor(Color.WHITE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        content.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final boolean[] captured = {false};
        final boolean[] loadFailed = {false};
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                pageProgress.setProgress(value);
                pageProgress.setVisibility(value < 100 ? View.VISIBLE : View.GONE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            private boolean capture(String url) {
                if (captured[0]) return true;
                try {
                    Uri uri = Uri.parse(url);
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || !"www.apti.co.kr".equalsIgnoreCase(uri.getHost())) return false;
                    String profile = uri.getQueryParameter("outerId");
                    if (profile == null || !profile.matches("[0-9A-Za-z_-]{4,128}")) return false;
                    captured[0] = true;
                    web.stopLoading();
                    webDialog.dismiss();
                    enroll(profile, "", "naver");
                    return true;
                } catch (Exception ignored) { return false; }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (capture(uri.toString())) return true;
                if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                    instruction.setText("웹 로그인으로 계속해 주세요. 앱 실행 대신 로그인 화면을 이용합니다.");
                    return true;
                }
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!capture(url)) super.onPageStarted(view, url, favicon);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame() || captured[0]) return;
                loadFailed[0] = true;
                instruction.setText("로그인 화면을 불러오지 못했습니다. 아래 ‘다시 불러오기’를 눌러 주세요.");
                pageProgress.setVisibility(View.GONE);
            }
        });
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button reload = action("다시 불러오기", accentSoft, accent);
        Button cancel = action("취소", surface, accent);
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        reloadParams.rightMargin = dp(8);
        actions.addView(reload, reloadParams);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(actions);
        webDialog = new Dialog(activity, dialogTheme);
        webDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Dialog owns an exactly measured full-height root; legacy AlertDialog's
        // custom panel gives a weighted WebView zero height on some Android themes.
        webDialog.setContentView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webDialog.setOnDismissListener(d -> {
            web.stopLoading();
            web.destroy();
            if (!captured[0]) {
                busy = false;
                stage = 0;
                localError = loadFailed[0] ? "네이버 로그인 화면에 연결하지 못했습니다." : "";
                notice = loadFailed[0] ? "" : "로그인을 취소했습니다. 기존 연결 정보는 유지됩니다.";
                draw();
            }
        });
        webDialog.show();
        webDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cancel.setOnClickListener(v -> webDialog.dismiss());
        reload.setOnClickListener(v -> {
            loadFailed[0] = false;
            instruction.setText("1/3 네이버 인증 · 아파트아이에 가입한 계정을 사용하세요");
            web.loadUrl(NAVER_URL);
        });
        web.loadUrl(NAVER_URL);
    }

    private static String errorMessage(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "연결 확인 시간이 초과되었습니다. 연결 상태 확인을 다시 눌러 주세요.";
        if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException) return "인터넷 또는 서버 연결을 확인한 뒤 다시 시도해 주세요.";
        return error.getMessage() == null ? "연결하지 못했습니다. 잠시 후 다시 시도해 주세요." : error.getMessage();
    }

    private LinearLayout column(int padding) {
        LinearLayout value = new LinearLayout(activity);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return value;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }
    private Button action(String value, int bg, int color) {
        Button view = new Button(activity);
        view.setText(value);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setTextColor(color);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(background(bg));
        return view;
    }
    private void addAction(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        parent.addView(button, params);
    }
    private GradientDrawable background(int color) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(dp(12));
        return value;
    }
    private int dp(int value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }
}
