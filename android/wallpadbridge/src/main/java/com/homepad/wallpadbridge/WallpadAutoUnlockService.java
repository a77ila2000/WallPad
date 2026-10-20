package com.homepad.wallpadbridge;

import android.app.IntentService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;

/** Local incoming-ring action. No server round trip occurs before the native key. */
public final class WallpadAutoUnlockService extends IntentService {
    public WallpadAutoUnlockService() { super("HomePadCentralAutoUnlock"); }

    @Override protected void onHandleIntent(Intent intent) {
        if (intent == null || !WallpadCallSettings.enabled(this)) return;
        long offeredAt = intent.getLongExtra("offeredAt", 0L);
        String localId = intent.getStringExtra("localCallId");
        if (localId == null || offeredAt <= 0L || SystemClock.elapsedRealtime() - offeredAt > 2500L) return;
        JSONObject audit = new JSONObject();
        JSONObject timing = new JSONObject();
        VisitorPhotoAwaiter photos = null;
        try {
            timing.put("serviceStartMs", SystemClock.elapsedRealtime() - offeredAt);
            photos = new VisitorPhotoAwaiter(new String[] { "/sdcard/cmx_data/visitor/", "/mnt/extsd/cmx_data/visitor/" });
            WallpadBridgeService.SipCallContext call = WallpadBridgeService.inspectSipCall();
            timing.put("contextReadyMs", SystemClock.elapsedRealtime() - offeredAt);
            // The vendor log must belong to this fresh broadcast; an old log
            // and a subsequently opened call screen must never authorize a key.
            if (call.place != 5 || Math.abs(System.currentTimeMillis() - call.offeredEpoch) > 5000L) return;
            audit.put("localCallId", localId);
            audit.put("source", "wallpad-sipua");
            audit.put("signal", "sipua-offering-wallpad");
            audit.put("kind", "central_entrance");
            audit.put("callPlace", 5);
            audit.put("sourceId", call.sourceId);
            audit.put("deviceIp", call.remoteIp);
            audit.put("detectedAt", intent.getStringExtra("detectedAt"));
            audit.put("autoUnlockStatus", "skipped");
            File photo = photos.await(call.offeredEpoch - 1000L, "central_entrance",
                    Math.max(0L, offeredAt + 3000L - SystemClock.elapsedRealtime()));
            photos.close();
            timing.put("photoReadyMs", SystemClock.elapsedRealtime() - offeredAt);
            audit.put("timing", timing);
            if (photo != null) {
                audit.put("photoPath", photo.getAbsolutePath());
                audit.put("capturedAt", WallpadBridgeService.captureTime(photo));
            }
            boolean eligible = CentralAutoUnlockPolicy.eligible(
                    WallpadCallSettings.enabled(this), call.place, call.sourceId,
                    WallpadCallSettings.sources(this), SystemClock.elapsedRealtime() - offeredAt,
                    WallpadCommandService.sipCallViewActive(this), photo != null);
            if (!eligible || offeredAt != WallpadEventReceiver.lastOfferingAt()) {
                String detail;
                if (!call.sourceId.matches("lobby[0-9]{4}")) detail = "중앙현관 호출 위치를 읽지 못해 자동 문열림을 실행하지 않았습니다";
                else if (photo == null) detail = "방문자 사진을 확보하지 못해 자동 문열림을 실행하지 않았습니다";
                else detail = "활성 중앙현관 호출 또는 허용 위치를 확인하지 못해 자동 문열림을 실행하지 않았습니다";
                audit.put("detail", detail);
                WallpadCallSettings.saveAudit(this, audit);
                Log.w("HomePadBridge", "Central auto-unlock skipped source=" + call.sourceId + " reason=" + detail);
                return;
            }
            audit.put("autoUnlockStatus", "attempted");
            audit.put("attemptedEpochMs", System.currentTimeMillis());
            // Durable audit precedes the physical action. After interruption,
            // only this record is retried; the door command is never replayed.
            if (!WallpadCallSettings.claim(this, call.offeredEpoch, call.sourceId, audit)) return;
            try {
                // Disk commit may have coincided with call termination. Never
                // issue a key to a different or already ended session.
                if (!CentralAutoUnlockPolicy.eligible(WallpadCallSettings.enabled(this), call.place, call.sourceId,
                        WallpadCallSettings.sources(this), SystemClock.elapsedRealtime() - offeredAt,
                        WallpadCommandService.sipCallViewActive(this), photo != null)
                        || offeredAt != WallpadEventReceiver.lastOfferingAt())
                    throw new IllegalStateException("기록 저장 중 호출이 종료되거나 바뀌어 문열기 신호를 보내지 않았습니다");
                timing.put("doorCommandMs", SystemClock.elapsedRealtime() - offeredAt);
                audit.put("commandIssuedEpochMs", System.currentTimeMillis());
                Log.i("HomePadBridge", "Central auto-unlock key source=" + call.sourceId + " timing=" + timing);
                String detail = WallpadCommandService.executeNativeDoor(this, "central_entrance", true);
                timing.put("confirmedMs", SystemClock.elapsedRealtime() - offeredAt);
                audit.put("autoUnlockStatus", "confirmed");
                audit.put("unlockedAt", WallpadCallSettings.isoNow());
                audit.put("detail", detail);
            } catch (Exception error) {
                audit.put("autoUnlockStatus", "failed");
                audit.put("detail", "자동 문열림 신호 확인 실패: " + error.getMessage());
            }
            WallpadCallSettings.saveAudit(this, audit);
            Log.i("HomePadBridge", "Central auto-unlock completed source=" + call.sourceId
                    + " status=" + audit.optString("autoUnlockStatus"));
        } catch (Exception error) {
            Log.e("HomePadBridge", "Central auto-unlock stopped without retry", error);
        } finally {
            if (photos != null) photos.close();
        }
    }
}
