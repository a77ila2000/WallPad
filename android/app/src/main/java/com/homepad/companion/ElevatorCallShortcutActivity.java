package com.homepad.companion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.UUID;

/** Explicit user-invoked shortcut, suitable for RegiStar's app action selector. */
public final class ElevatorCallShortcutActivity extends Activity {
    static final String EXTRA_REQUEST_ID = "elevator_shortcut_request_id";
    static final String ACTION_RESULT = "com.homepad.companion.ELEVATOR_SHORTCUT_RESULT";
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView message;
    private ProgressBar progress;
    private Button close;
    private SharedPreferences preferences;
    private String requestId;
    private boolean waiting;
    private String displayed = "우리층으로 호출 중…";
    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (requestId.equals(intent.getStringExtra(EXTRA_REQUEST_ID))) readResult();
        }
    };

    // Android 8-12 lack NOT_EXPORTED; result IDs are unguessable and results
    // are read from private preferences, never from broadcast message data.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        preferences = getSharedPreferences("homepad", MODE_PRIVATE);
        requestId = saved == null ? UUID.randomUUID().toString() : saved.getString("request_id");
        waiting = saved == null || saved.getBoolean("waiting");
        if (saved != null) displayed = saved.getString("displayed", displayed);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText(R.string.elevator_call_shortcut_long);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);
        message = new TextView(this);
        message.setTextSize(17);
        message.setPadding(0, dp(16), 0, dp(16));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(message);
        progress = new ProgressBar(this);
        content.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        close = new Button(this);
        close.setText("닫기");
        close.setMinHeight(dp(48));
        close.setOnClickListener(v -> finish());
        content.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(content);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(resultReceiver, filter);
        render();
        if (saved == null) requestCall();
        else readResult();
        if (waiting) awaitResult();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (waiting) return;
        // A visible failure dialog is singleTop. A new side-key press must
        // start a new attempt rather than leaving the previous failure on screen.
        main.removeCallbacksAndMessages(null);
        requestId = UUID.randomUUID().toString();
        waiting = true;
        displayed = "우리층으로 호출 중…";
        render();
        requestCall();
        if (waiting) awaitResult();
    }

    @Override protected void onResume() {
        super.onResume();
        readResult();
    }

    private void awaitResult() {
        String pendingId = requestId;
        main.postDelayed(() -> {
            if (waiting && pendingId.equals(requestId))
                complete("전송 결과를 확인하지 못했습니다. 앱에서 상태를 확인해 주세요.", false);
        }, 25_000L);
    }

    private void requestCall() {
        String token = preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN);
        if (token == null || token.trim().isEmpty()) {
            complete("우리집 앱의 설정에서 서버 연결을 먼저 완료해 주세요.", false);
            return;
        }
        try {
            Intent command = new Intent(this, VisitorNotificationService.class)
                    .setAction(VisitorNotificationService.ACTION_ELEVATOR_CALL)
                    .putExtra(EXTRA_REQUEST_ID, requestId);
            startForegroundService(command);
        } catch (Exception error) {
            complete("호출을 시작하지 못했습니다. 우리집 앱에서 연결을 확인해 주세요.", false);
        }
    }

    private void readResult() {
        if (!waiting || !requestId.equals(preferences.getString("shortcut_result_id", ""))) return;
        complete(preferences.getString("shortcut_result_message", "호출 결과를 확인해 주세요."),
                preferences.getBoolean("shortcut_result_ok", false));
    }

    private void complete(String value, boolean success) {
        waiting = false;
        displayed = value;
        render();
        if (success) main.postDelayed(this::finish, 1800L);
    }

    private void render() {
        message.setText(displayed);
        progress.setVisibility(waiting ? View.VISIBLE : View.GONE);
        close.setText(waiting ? "닫기 · 호출은 계속 진행" : "닫기");
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("request_id", requestId);
        out.putBoolean("waiting", waiting);
        out.putString("displayed", displayed);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(resultReceiver);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
