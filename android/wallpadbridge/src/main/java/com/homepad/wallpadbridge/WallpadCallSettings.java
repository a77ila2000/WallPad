package com.homepad.wallpadbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Private persisted wallpad settings and bounded, upload-only audit queue. */
final class WallpadCallSettings {
    private static final String PREFS = "call_settings";
    private static final Object LOCK = new Object();
    private static long lastReport;
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return prefs(context).getBoolean("centralAutoUnlock", false); }
    static long revision(Context context) { return prefs(context).getLong("revision", 0L); }
    static String[] sources(Context context) {
        return prefs(context).getString("sources", "").split(",");
    }
    static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
    static boolean claim(Context context, long sipEpoch, String source, JSONObject audit) throws Exception {
        synchronized (LOCK) {
            SharedPreferences state = prefs(context);
            String signature = source + ":" + sipEpoch;
            if (signature.equals(state.getString("lastClaim", ""))) return false;
            if (!state.edit().putString("lastClaim", signature)
                    .putString("auditQueue", withAudit(state, audit).toString())
                    .putString("lastAutoUnlockAudit", audit.toString()).commit())
                throw new IllegalStateException("문열림 호출 및 사진 기록을 저장하지 못했습니다");
            return true;
        }
    }
    static void sync(Context context, JSONObject desired) throws Exception {
        if (desired == null) return;
        SharedPreferences state = prefs(context);
        long revision = desired.optLong("revision", 0);
        boolean changed = revision > state.getLong("revision", 0);
        if (changed) {
            JSONObject quietHours = desired.optJSONObject("quietHours");
            WallpadQuietHours.configure(context, quietHours);
            JSONArray allowed = desired.optJSONArray("sourceIds");
            StringBuilder sources = new StringBuilder();
            if (allowed != null) for (int i = 0; i < allowed.length(); i++) {
                String value = allowed.optString(i);
                if (!value.matches("lobby[0-9]{4}")) continue;
                if (sources.length() > 0) sources.append(',');
                sources.append(value);
            }
            if (!state.edit().putLong("revision", revision)
                    .putBoolean("centralAutoUnlock", desired.optBoolean("centralAutoUnlock", false))
                    .putString("quietHours", quietHours == null ? "{}" : quietHours.toString())
                    .putString("sources", sources.toString()).commit()) {
                throw new IllegalStateException("월패드 설정을 저장하지 못했습니다");
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (changed || lastReport == 0L || now - lastReport > 30000L) {
            JSONObject report = WallpadQuietHours.report(context)
                    .put("revision", state.getLong("revision", 0))
                    .put("centralAutoUnlock", enabled(context)).put("version", BuildConfig.VERSION_NAME);
            if (state.contains("quietHours")) report.put("quietHours", new JSONObject(state.getString("quietHours", "{}")));
            new WallpadApiClient(context, 8000, 8000).postJson("/api/wallpad/call-settings/report", report);
            lastReport = now;
        }
    }
    static void saveAudit(Context context, JSONObject value) throws Exception {
        synchronized (LOCK) {
            SharedPreferences state = prefs(context);
            if (!state.edit().putString("auditQueue", withAudit(state, value).toString())
                    .putString("lastAutoUnlockAudit", value.toString()).commit()) {
                throw new IllegalStateException("방문 호출 기록을 저장하지 못했습니다");
            }
        }
    }
    private static JSONArray withAudit(SharedPreferences state, JSONObject value) throws Exception {
        JSONArray old = new JSONArray(state.getString("auditQueue", "[]"));
        JSONArray queue = new JSONArray();
        queue.put(value);
        for (int i = 0; i < old.length() && queue.length() < 50; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && !value.optString("localCallId").equals(item.optString("localCallId"))) queue.put(item);
        }
        return queue;
    }
    static void flushAudit(Context context) throws Exception {
        JSONObject audit;
        synchronized (LOCK) {
            JSONArray queue = new JSONArray(prefs(context).getString("auditQueue", "[]"));
            audit = queue.optJSONObject(queue.length() - 1);
        }
        if (audit == null) return;
        String queuedValue = audit.toString();
        if (!audit.optString("sourceId").matches("lobby004[123]")) {
            // A rejected old diagnostic must not block the upload of later
            // valid calls. Keep it locally; never turn it into a door command.
            synchronized (LOCK) {
                SharedPreferences state = prefs(context);
                JSONArray unknown = new JSONArray(state.getString("unmappedAuditQueue", "[]"));
                JSONArray archived = new JSONArray();
                archived.put(audit);
                for (int i = 0; i < unknown.length() && archived.length() < 50; i++) {
                    JSONObject item = unknown.optJSONObject(i);
                    if (item != null && !audit.optString("localCallId").equals(item.optString("localCallId")))
                        archived.put(item);
                }
                if (!state.edit().putString("unmappedAuditQueue", archived.toString()).commit())
                    throw new IllegalStateException("호출 위치 미확인 기록을 보관하지 못했습니다");
            }
            removeUploadedAudit(context, queuedValue);
            return;
        }
        if ("attempted".equals(audit.optString("autoUnlockStatus"))) {
            if (System.currentTimeMillis() - audit.optLong("attemptedEpochMs") < 15000L) return;
            audit.put("autoUnlockStatus", "unconfirmed");
            audit.put("detail", "문열림 시도 후 결과 확인이 중단됐습니다. 재전송하지 않습니다");
        }
        WallpadApiClient api = new WallpadApiClient(context, 8000, 12000);
        JSONObject response = api.postJson("/api/wallpad/auto-unlock/events", audit);
        String eventId = response.optString("eventId");
        File photo = new File(audit.optString("photoPath", ""));
        if (photo.isFile() && eventId.length() == 32) {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("X-Visitor-Kind", "central_entrance");
            headers.put("X-Visitor-Event-Id", eventId);
            headers.put("X-Visitor-Captured-At", audit.optString("capturedAt"));
            api.postFile("/api/visitors/photo", "image/jpeg", photo, headers);
        }
        removeUploadedAudit(context, queuedValue);
    }
    private static void removeUploadedAudit(Context context, String queuedValue) throws Exception {
        synchronized (LOCK) {
            SharedPreferences state = prefs(context);
            JSONArray old = new JSONArray(state.getString("auditQueue", "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !queuedValue.equals(item.toString())) kept.put(item);
            }
            if (!state.edit().putString("auditQueue", kept.toString()).commit())
                throw new IllegalStateException("방문 호출 큐를 갱신하지 못했습니다");
        }
    }
}
