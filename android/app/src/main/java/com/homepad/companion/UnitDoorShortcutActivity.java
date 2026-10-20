package com.homepad.companion;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

/** Explicit launcher/RegiStar action. The existing service owns sending and deduplication. */
public final class UnitDoorShortcutActivity extends Activity {
    private boolean requested;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requested = savedInstanceState != null && savedInstanceState.getBoolean("requested");
    }

    @Override public void onResume() {
        super.onResume();
        if (!requested) {
            requested = true;
            SharedPreferences preferences = getSharedPreferences("homepad", MODE_PRIVATE);
            String message;
            if (preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN).trim().isEmpty()) {
                message = "우리집 앱에서 서버 연결을 먼저 설정해 주세요";
            } else if (UnitDoorWidgetProvider.pending(preferences)) {
                message = "이미 세대현관 문열기를 요청 중입니다";
            } else {
                try {
                    UnitDoorWidgetProvider.openDoor(this, true);
                    message = "세대현관 문열기 요청 중 · 결과는 알림으로 표시됩니다";
                } catch (RuntimeException error) {
                    message = "문열기를 시작하지 못했습니다. 우리집 앱에서 다시 시도해 주세요";
                }
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("requested", requested);
        super.onSaveInstanceState(state);
    }
}
