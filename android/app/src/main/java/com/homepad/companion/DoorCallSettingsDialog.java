package com.homepad.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.Locale;

/** The switch is confirmed only by a persisted wallpad acknowledgement. */
final class DoorCallSettingsDialog {
    private final Activity activity;
    private final ApiClient api;
    private final ExecutorService network;
    private final Handler main;
    private Switch toggle;
    private TextView state;
    private AlertDialog dialog;
    private boolean updating;
    private Switch quietToggle;
    private Button startButton, endButton, saveQuiet;
    private TextView quietState;
    private TextView quietDescription;
    private int quietPercent = 15;
    private String quietStart = "22:00", quietEnd = "02:00";

    DoorCallSettingsDialog(Activity activity, ApiClient api, ExecutorService network, Handler main) {
        this.activity = activity;
        this.api = api;
        this.network = network;
        this.main = main;
    }

    void show() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, padding);
        TextView audio = label("통화는 스피커폰으로 연결됩니다\n세대현관·중앙현관 모두 음량 버튼으로 조절할 수 있습니다.");
        form.addView(audio);
        toggle = new Switch(activity);
        toggle.setText("중앙현관 자동 문열림");
        toggle.setTextSize(16);
        toggle.setPadding(0, padding, 0, padding / 2);
        toggle.setEnabled(false);
        form.addView(toggle);
        form.addView(label("지하 바깥쪽 · 지하 안쪽 · 1층 중앙현관\n켜 두면 우리집을 호출한 방문자에게 문이 자동으로 열립니다.\n사진과 시간을 월패드 및 앱 방문 기록에 남깁니다.\n세대현관은 직접 문을 엽니다."));
        state = label("월패드 설정 확인 중…");
        state.setPadding(0, padding / 2, 0, 0);
        form.addView(state);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (updating) return;
            setControls(false);
            state.setText("월패드에 저장 중…");
            try { transfer(new JSONObject().put("centralAutoUnlock", enabled)); }
            catch (Exception ignored) { }
        });
        quietToggle = new Switch(activity);
        quietToggle.setText("야간 월패드 호출음 줄이기");
        quietToggle.setTextSize(16);
        quietToggle.setPadding(0, padding, 0, padding / 2);
        quietToggle.setEnabled(false);
        form.addView(quietToggle);
        quietDescription = label("");
        updateQuietDescription();
        form.addView(quietDescription);
        LinearLayout times = new LinearLayout(activity);
        startButton = new Button(activity);
        endButton = new Button(activity);
        startButton.setOnClickListener(view -> pickTime(true));
        endButton.setOnClickListener(view -> pickTime(false));
        times.addView(startButton, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(endButton, new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(times);
        saveQuiet = new Button(activity);
        saveQuiet.setText("야간 호출음 설정 저장");
        saveQuiet.setEnabled(false);
        saveQuiet.setOnClickListener(view -> {
            if (quietStart.equals(quietEnd)) {
                quietState.setText("시작과 종료 시간을 다르게 선택해 주세요.");
                return;
            }
            setControls(false);
            quietState.setText("월패드에 저장 중…");
            try {
                transfer(new JSONObject().put("quietHours", new JSONObject()
                        .put("enabled", quietToggle.isChecked()).put("start", quietStart)
                        .put("end", quietEnd).put("percent", quietPercent)));
            } catch (Exception ignored) { }
        });
        form.addView(saveQuiet);
        quietState = label("야간 호출음 설정 확인 중…");
        form.addView(quietState);
        updateTimes();
        setControls(false);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        dialog = new AlertDialog.Builder(activity).setTitle("현관 통화·호출음 설정")
                .setView(scroll).setPositiveButton("닫기", null).create();
        dialog.show();
        transfer(null);
    }

    private TextView label(String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(90, 90, 90));
        return view;
    }

    private void updateTimes() {
        startButton.setText("시작 " + quietStart);
        endButton.setText("종료 " + quietEnd);
    }

    private void updateQuietDescription() {
        quietDescription.setText("세대현관 · 중앙현관 3곳의 벨소리만 " + (100 - quietPercent)
                + "% 줄여 평소의 " + quietPercent + "%로 재생합니다.\n"
                + "매일 적용하고 종료 시간이 되면 원래 벨소리로 돌아갑니다.\n시간은 한국 시간 기준입니다.");
    }

    private void pickTime(boolean start) {
        String value = start ? quietStart : quietEnd;
        new TimePickerDialog(activity, (picker, hour, minute) -> {
            String selected = String.format(Locale.US, "%02d:%02d", hour, minute);
            if (start) quietStart = selected; else quietEnd = selected;
            updateTimes();
            quietState.setText("시간 변경 · 저장을 눌러 적용해 주세요.");
        }, Integer.parseInt(value.substring(0, 2)), Integer.parseInt(value.substring(3)), true).show();
    }

    private void setControls(boolean enabled) {
        toggle.setEnabled(enabled);
        quietToggle.setEnabled(enabled);
        startButton.setEnabled(enabled);
        endButton.setEnabled(enabled);
        saveQuiet.setEnabled(enabled);
    }

    private void transfer(JSONObject change) {
        network.execute(() -> {
            try {
                JSONObject value = change == null ? api.getJson("/api/calls/settings")
                        : api.postJson("/api/calls/settings", change, 15000);
                // A successful server write is not a successful wallpad write.
                for (int attempt = 0; !value.optBoolean("synced") && attempt < 8; attempt++) {
                    Thread.sleep(1000);
                    value = api.getJson("/api/calls/settings");
                }
                JSONObject result = value;
                main.post(() -> {
                    if (!dialog.isShowing()) return;
                    updating = true;
                    toggle.setChecked(result.optBoolean("centralAutoUnlock", false));
                    updating = false;
                    JSONObject quiet = result.optJSONObject("quietHours");
                    if (quiet != null) {
                        quietToggle.setChecked(quiet.optBoolean("enabled", false));
                        quietStart = quiet.optString("start", "22:00");
                        quietEnd = quiet.optString("end", "02:00");
                        quietPercent = quiet.optInt("percent", 50);
                        updateQuietDescription();
                        updateTimes();
                    }
                    setControls(true);
                    boolean synced = result.optBoolean("synced", false);
                    state.setText(synced ? "월패드 저장 완료 · 자동 문열림 "
                            + (toggle.isChecked() ? "켜짐" : "꺼짐")
                            : "월패드 확인 대기 · 연결되면 설정이 적용됩니다");
                    state.setTextColor(synced ? Color.rgb(22, 122, 62) : Color.rgb(164, 100, 0));
                    String problem = result.optString("quietHoursError", "");
                    boolean ready = result.optBoolean("quietHoursReady", false);
                    quietState.setText(!result.optBoolean("quietHoursSynced")
                            ? "월패드 확인 대기 · 연결 후 적용 여부를 확인합니다"
                            : quietToggle.isChecked() && !ready
                            ? (problem.length() == 0 ? "월패드 벨소리 준비가 필요합니다" : problem)
                            : quietToggle.isChecked()
                            ? (result.optBoolean("quietHoursActive") ? "지금 음량 " + quietPercent + "% 적용 중"
                                    : "월패드 저장 완료 · 매일 야간에 음량 " + quietPercent + "% 적용")
                            : "야간 호출음 줄이기 꺼짐");
                    quietState.setTextColor(quietToggle.isChecked() && (!ready || !result.optBoolean("quietHoursSynced"))
                            ? Color.rgb(164, 100, 0) : Color.rgb(22, 122, 62));
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!dialog.isShowing()) return;
                    setControls(false);
                    state.setText("설정을 확인하지 못했습니다. 화면을 다시 열어 확인해 주세요.");
                    state.setTextColor(Color.rgb(190, 40, 40));
                    quietState.setText("설정 조회 실패 · 다시 열어 확인해 주세요.");
                });
            }
        });
    }
}
