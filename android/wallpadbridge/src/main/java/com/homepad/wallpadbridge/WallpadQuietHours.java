package com.homepad.wallpadbridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Calendar;
import java.util.TimeZone;

/** Switches only the two COMMAX doorbell selections, never a global audio stream. */
final class WallpadQuietHours {
    static final String ACTION = "com.homepad.wallpadbridge.QUIET_HOURS";
    private static final int[] DOORBELLS = {13, 15};
    private static final String PREFS = "quiet_hours";
    private static long lastCheck;
    private static boolean ready, active;
    private static String error = "";

    static synchronized void apply(Context context, boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && lastCheck > 0 && now - lastCheck < 30000L) return;
        lastCheck = now;
        ready = active = false;
        error = "";
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject config = new JSONObject(state.getString("config", "{}"));
            int start = QuietHoursWindow.minute(config.optString("start", "22:00"));
            int end = QuietHoursWindow.minute(config.optString("end", "02:00"));
            if (start == end) throw new IllegalArgumentException("야간 시작·종료 시간이 같습니다");
            int percent = config.optInt("percent", 50);
            if (percent != 15 && percent != 50) throw new IllegalArgumentException("지원하지 않는 벨소리 음량입니다");
            Calendar clock = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
            boolean enabled = config.optBoolean("enabled", false);
            boolean quiet = enabled && QuietHoursWindow.active(
                    clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE), start, end);
            schedule(context, clock, enabled, start, end);
            if (!quiet) restore(context, state);
            File manifest = new File(context.getFilesDir(), "quiet-tones.json");
            if (!manifest.isFile()) {
                if (enabled) error = "월패드 벨소리 준비가 필요합니다";
                return;
            }
            FileInputStream input = new FileInputStream(manifest);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 4096) throw new IllegalStateException("Invalid tone manifest");
                    bytes.write(buffer, 0, count);
                }
            } finally { input.close(); }
            JSONObject tones = new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
            // Validate both before switching either; a changed native ringtone needs re-preparation.
            for (int id : DOORBELLS) {
                JSONObject tone = tones.getJSONObject(Integer.toString(id));
                String original = tone.getString("original"), reduced = tone.getString("reduced");
                if (!original.matches("[a-zA-Z0-9_.-]{1,80}")
                        || !reduced.matches("homepad_quiet_[a-f0-9]{16}_" + percent + "\\.wav")
                        || !new File("/user/app/bin/sound", reduced).isFile())
                    throw new IllegalStateException("월패드 벨소리 준비가 필요합니다");
                String current = selection(context, id);
                String owned = state.getString("reduced" + id, "");
                if (!current.equals(original) && !current.equals(reduced) && !current.equals(owned))
                    throw new IllegalStateException("월패드 벨소리가 변경됐습니다. 다시 준비해 주세요");
            }
            ready = true;
            if (!quiet) return;
            for (int id : DOORBELLS) {
                JSONObject tone = tones.getJSONObject(Integer.toString(id));
                String reduced = tone.getString("reduced");
                if (!state.edit().putString("original" + id, tone.getString("original"))
                        .putString("reduced" + id, reduced).commit())
                    throw new IllegalStateException("원래 벨소리를 보관하지 못했습니다");
                select(context, id, reduced);
            }
            active = true;
        } catch (Exception failure) {
            error = failure.getMessage() == null ? "야간 벨소리 설정 실패" : failure.getMessage();
            ready = active = false;
            // A partial switch is rolled back; native ringing must stay usable.
            try { restore(context, state); } catch (Exception ignored) { }
            Log.w("HomePadQuietHours", error);
        }
    }

    static synchronized void configure(Context context, JSONObject value) throws Exception {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("config", value == null ? "{}" : value.toString()).commit())
            throw new IllegalStateException("야간 호출음 설정을 저장하지 못했습니다");
        apply(context, true);
    }

    static synchronized JSONObject report(Context context) throws Exception {
        apply(context, false);
        return new JSONObject().put("quietHoursReady", ready).put("quietHoursActive", active)
                .put("quietHoursError", error);
    }

    private static void restore(Context context, SharedPreferences state) throws Exception {
        for (int id : DOORBELLS) {
            String reduced = state.getString("reduced" + id, "");
            if (reduced.length() == 0) continue;
            // Preserve any later change made on the wallpad itself.
            if (selection(context, id).equals(reduced)) select(context, id, state.getString("original" + id, ""));
            if (!state.edit().remove("original" + id).remove("reduced" + id).commit())
                throw new IllegalStateException("벨소리 복원 기록 저장 실패");
        }
    }

    private static String selection(Context context, int id) throws Exception {
        Cursor cursor = context.getContentResolver().query(uri(id), null, null, null, null);
        try {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("월패드 벨소리를 읽지 못했습니다");
            String value = cursor.getString(cursor.getColumnIndexOrThrow("value"));
            return value == null ? "" : value;
        } finally { if (cursor != null) cursor.close(); }
    }

    private static void select(Context context, int id, String value) throws Exception {
        if (selection(context, id).equals(value)) return;
        ContentValues values = new ContentValues();
        values.put("value", value);
        if (context.getContentResolver().update(uri(id), values, null, null) != 1
                || !selection(context, id).equals(value))
            throw new IllegalStateException("월패드 벨소리 설정을 확인하지 못했습니다");
    }

    private static Uri uri(int id) {
        return Uri.parse("content://com.commax.provider.settings/setting/" + id);
    }

    private static void schedule(Context context, Calendar clock, boolean enabled, int start, int end) {
        PendingIntent alarm = PendingIntent.getBroadcast(context, 2202,
                new Intent(context, WallpadInternalReceiver.class).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (!enabled) { manager.cancel(alarm); return; }
        long next = Long.MAX_VALUE;
        for (int minute : new int[] {start, end}) {
            Calendar boundary = (Calendar) clock.clone();
            boundary.set(Calendar.HOUR_OF_DAY, minute / 60);
            boundary.set(Calendar.MINUTE, minute % 60);
            boundary.set(Calendar.SECOND, 0);
            boundary.set(Calendar.MILLISECOND, 0);
            if (!boundary.after(clock)) boundary.add(Calendar.DATE, 1);
            next = Math.min(next, boundary.getTimeInMillis());
        }
        if (Build.VERSION.SDK_INT >= 19) manager.setExact(AlarmManager.RTC_WAKEUP, next, alarm);
        else manager.set(AlarmManager.RTC_WAKEUP, next, alarm);
    }
}
