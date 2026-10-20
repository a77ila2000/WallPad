package com.homepad.wallpadbridge;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Low-overhead PCMU relay for an accepted COMMAX unit-entrance call.
 *
 * Place 2 normally selects CMXMM path 32 (CIP <-> DoorCamera/SubPhone) and does
 * not create network RTP.  During a requested phone call this service creates
 * one stock CMXMM RTP pipeline on loopback and selects path 34
 * (DoorCamera/SubPhone <-> Public).  No AudioRecord, AudioTrack, tcpdump,
 * iptables or vendor JNI library is used by this process.
 */
public final class WallpadNativeRtpRelayService extends Service {
    private static final String TAG = "HomePadUnitRtp";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int UNIT_PATH = 32;
    private static final int RELAY_PATH = 34;
    private static final int CMX_RTP_PORT = 62110;
    private static final int BRIDGE_RTP_PORT = 62111;
    private static final int PCMU_BYTES = 160;
    private static final long MAX_SESSION_MS = 90_000L;
    private static final Object STATE_LOCK = new Object();
    private static String readyEventId = "";
    private static String readyDetail = "";
    private static boolean ready;
    private static volatile WallpadNativeRtpRelayService activeInstance;

    private final ArrayBlockingQueue<byte[]> visitorFrames = new ArrayBlockingQueue<byte[]>(30);
    private final ArrayBlockingQueue<byte[]> phoneFrames = new ArrayBlockingQueue<byte[]>(30);
    private volatile boolean stopped;
    private volatile boolean phoneReady;
    private volatile DatagramSocket rtpSocket;
    private volatile String eventId = "";
    private volatile Thread worker;
    private volatile int rtpHandle = -1;
    private volatile boolean pathChanged;
    private volatile int originalAudioPath = -1;
    private long offeringAt;
    private long receivedFrames;
    private long sentFrames;

    static boolean awaitReady(String requestedEventId, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (STATE_LOCK) {
            while (SystemClock.elapsedRealtime() < deadline
                    && (!requestedEventId.equals(readyEventId)
                    || (!ready && readyDetail.length() == 0))) {
                STATE_LOCK.wait(Math.max(1L, deadline - SystemClock.elapsedRealtime()));
            }
            return requestedEventId.equals(readyEventId) && ready;
        }
    }

    static String readyDetail(String requestedEventId) {
        synchronized (STATE_LOCK) {
            return requestedEventId.equals(readyEventId)
                    ? readyDetail : "세대현관 RTP 중계 상태를 찾지 못했습니다";
        }
    }

    static void stopActive() {
        WallpadNativeRtpRelayService value = activeInstance;
        if (value != null) value.requestStop();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final String requested = intent == null ? null : intent.getStringExtra("eventId");
        if (requested == null || !requested.matches("[0-9a-f]{32}")) return START_NOT_STICKY;
        if (worker != null && worker.isAlive()) {
            if (!requested.equals(eventId)) publish(requested, false, "이전 음성 중계가 종료되는 중입니다");
            return START_NOT_STICKY;
        }
        stopped = false;
        phoneReady = false;
        receivedFrames = 0;
        sentFrames = 0;
        eventId = requested;
        offeringAt = WallpadEventReceiver.lastOfferingAt();
        activeInstance = this;
        synchronized (STATE_LOCK) {
            readyEventId = requested;
            readyDetail = "";
            ready = false;
            STATE_LOCK.notifyAll();
        }
        worker = new Thread(new Runnable() {
            @Override public void run() { runRelay(requested, startId); }
        }, "HomePadUnitNativeRtp");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        requestStop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void runRelay(String requested, int startId) {
        Thread receiver = null;
        Thread uploader = null;
        Thread phone = null;
        Thread sender = null;
        try {
            if (!sameCallActive()) throw new IllegalStateException("코맥스 통화 화면이 활성 상태가 아닙니다");
            int initialPath = waitForPath(UNIT_PATH, 3000L);
            if (initialPath != UNIT_PATH) {
                throw new IllegalStateException("세대현관 경로 32가 아닙니다 (현재 " + initialPath + ")");
            }
            originalAudioPath = initialPath;
            DatagramSocket socket = new DatagramSocket(
                    BRIDGE_RTP_PORT, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(500);
            rtpSocket = socket;
            rtpHandle = CmxMediaControl.startRtpOneChannel(CMX_RTP_PORT, BRIDGE_RTP_PORT);
            CmxMediaControl.setAudioPath(RELAY_PATH);
            pathChanged = true;

            final CountDownLatch firstRtp = new CountDownLatch(1);
            receiver = new Thread(new Runnable() {
                @Override public void run() { receiveVisitorRtp(firstRtp); }
            }, "HomePadUnitRtpReceive");
            uploader = new Thread(new Runnable() {
                @Override public void run() { uploadVisitorAudio(); }
            }, "HomePadUnitRtpUpload");
            phone = new Thread(new Runnable() {
                @Override public void run() { pollPhoneAudio(); }
            }, "HomePadUnitPhonePoll");
            sender = new Thread(new Runnable() {
                @Override public void run() { sendPhoneRtp(); }
            }, "HomePadUnitRtpSend");
            receiver.start();
            uploader.start();
            phone.start();
            sender.start();
            if (!firstRtp.await(3500L, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("세대현관 음성 RTP가 3.5초 안에 시작되지 않았습니다");
            }
            publish(requested, true, "세대현관 네이티브 PCMU 중계가 연결되었습니다");
            Log.i(TAG, "Native unit RTP ready handle=" + rtpHandle);

            long deadline = SystemClock.elapsedRealtime() + MAX_SESSION_MS;
            while (!stopped && sameCallActive() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250L);
            }
        } catch (Exception error) {
            if (!stopped) {
                String detail = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                publish(requested, false, detail);
                Log.e(TAG, "Native unit RTP failed", error);
            }
        } finally {
            stopped = true;
            closeSocket();
            interrupt(receiver);
            interrupt(uploader);
            interrupt(phone);
            interrupt(sender);
            CmxMediaControl.stopRtp(rtpHandle);
            rtpHandle = -1;
            restorePathIfNeeded();
            visitorFrames.clear();
            phoneFrames.clear();
            postAudioStopBestEffort(requested);
            Log.i(TAG, "Native unit RTP stopped received=" + receivedFrames + " sent=" + sentFrames);
            synchronized (STATE_LOCK) {
                if (requested.equals(readyEventId)) {
                    ready = false;
                    if (readyDetail.length() == 0) readyDetail = "통화가 종료되었습니다";
                    STATE_LOCK.notifyAll();
                }
            }
            if (activeInstance == this) activeInstance = null;
            stopSelf(startId);
        }
    }

    private int waitForPath(int expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int path = CmxMediaControl.getAudioPath();
        while (path != expected && sameCallActive() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(200L);
            path = CmxMediaControl.getAudioPath();
        }
        return path;
    }

    private void receiveVisitorRtp(CountDownLatch firstRtp) {
        byte[] buffer = new byte[512];
        while (!stopped) {
            DatagramSocket socket = rtpSocket;
            if (socket == null) break;
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException ignored) {
                continue;
            } catch (Exception error) {
                if (!stopped) Log.w(TAG, "Unit RTP receive stopped", error);
                break;
            }
            int offset = rtpPayloadOffset(packet.getData(), packet.getOffset(), packet.getLength());
            if (offset < 0 || packet.getOffset() + packet.getLength() - offset != PCMU_BYTES) continue;
            byte[] frame = new byte[PCMU_BYTES];
            System.arraycopy(packet.getData(), offset, frame, 0, PCMU_BYTES);
            if (!visitorFrames.offer(frame)) {
                visitorFrames.poll();
                visitorFrames.offer(frame);
            }
            receivedFrames++;
            firstRtp.countDown();
        }
    }

    private void uploadVisitorAudio() {
        while (!stopped) {
            try {
                byte[] first = visitorFrames.poll(500L, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                ByteArrayOutputStream batch = new ByteArrayOutputStream(PCMU_BYTES * 5);
                batch.write(first);
                for (int count = 1; count < 5; count++) {
                    byte[] next = visitorFrames.poll(20L, TimeUnit.MILLISECONDS);
                    if (next == null) break;
                    batch.write(next);
                }
                JSONObject body = new JSONObject();
                body.put("eventId", eventId);
                body.put("frames", Base64.encodeToString(batch.toByteArray(), Base64.NO_WRAP));
                postJson("/api/wallpad/audio/visitor", body);
            } catch (InterruptedException ignored) {
                break;
            } catch (Exception error) {
                if (!stopped) Log.w(TAG, "Unit visitor upload retry", error);
                SystemClock.sleep(150L);
            }
        }
    }

    private void pollPhoneAudio() {
        long sequence = 0L;
        while (!stopped) {
            try {
                JSONObject value = getJson("/api/wallpad/audio/phone?eventId="
                        + URLEncoder.encode(eventId, "UTF-8") + "&after=" + sequence + "&timeout=2");
                if (!value.optBoolean("active", false)) {
                    stopped = true;
                    closeSocket();
                    return;
                }
                sequence = value.optLong("sequence", sequence);
                phoneReady = value.optBoolean("phoneReady", phoneReady);
                byte[] raw = Base64.decode(value.optString("frames", ""), Base64.DEFAULT);
                for (int offset = 0; offset + PCMU_BYTES <= raw.length; offset += PCMU_BYTES) {
                    byte[] frame = new byte[PCMU_BYTES];
                    System.arraycopy(raw, offset, frame, 0, PCMU_BYTES);
                    if (!phoneFrames.offer(frame)) {
                        phoneFrames.poll();
                        phoneFrames.offer(frame);
                    }
                }
            } catch (Exception error) {
                if (!stopped) Log.w(TAG, "Unit phone audio poll retry", error);
                SystemClock.sleep(150L);
            }
        }
    }

    private void sendPhoneRtp() {
        SecureRandom random = new SecureRandom();
        int sequence = random.nextInt(65536);
        long timestamp = random.nextInt() & 0xffffffffL;
        int ssrc = random.nextInt();
        long nextAt = SystemClock.elapsedRealtime();
        byte[] silence = new byte[PCMU_BYTES];
        for (int i = 0; i < silence.length; i++) silence[i] = (byte) 0xff;
        while (!stopped) {
            if (!phoneReady && phoneFrames.isEmpty()) {
                SystemClock.sleep(20L);
                nextAt = SystemClock.elapsedRealtime();
                continue;
            }
            long now = SystemClock.elapsedRealtime();
            if (now < nextAt) SystemClock.sleep(nextAt - now);
            nextAt += 20L;
            byte[] frame = phoneFrames.poll();
            if (frame == null) frame = silence;
            byte[] rtp = new byte[12 + PCMU_BYTES];
            rtp[0] = (byte) 0x80;
            rtp[1] = (byte) 0x7f; // CMXMM receive pipeline advertises payload 127.
            ByteBuffer header = ByteBuffer.wrap(rtp).order(ByteOrder.BIG_ENDIAN);
            header.position(2);
            header.putShort((short) sequence++);
            header.putInt((int) timestamp);
            header.putInt(ssrc);
            System.arraycopy(frame, 0, rtp, 12, PCMU_BYTES);
            timestamp = (timestamp + PCMU_BYTES) & 0xffffffffL;
            try {
                DatagramSocket socket = rtpSocket;
                if (socket == null) break;
                socket.send(new DatagramPacket(rtp, rtp.length,
                        InetAddress.getByName("127.0.0.1"), CMX_RTP_PORT));
                sentFrames++;
            } catch (Exception error) {
                if (!stopped) Log.w(TAG, "Unit phone RTP send stopped", error);
                break;
            }
        }
    }

    private void requestStop() {
        stopped = true;
        closeSocket();
    }

    private void closeSocket() {
        DatagramSocket value = rtpSocket;
        rtpSocket = null;
        if (value != null) value.close();
    }

    private void restorePathIfNeeded() {
        if (!pathChanged) return;
        pathChanged = false;
        try {
            int current = CmxMediaControl.getAudioPath();
            int restore = originalAudioPath;
            originalAudioPath = -1;
            // Restore only a route that is still owned by this relay. If the
            // stock COMMAX app already moved to idle path 31, leave it alone.
            if (current == RELAY_PATH && restore >= 0) CmxMediaControl.setAudioPath(restore);
        } catch (Exception error) {
            Log.w(TAG, "Unit audio path restore failed", error);
        }
    }

    private boolean sameCallActive() {
        return offeringAt > 0 && offeringAt == WallpadEventReceiver.lastOfferingAt()
                && sipCallViewActive();
    }

    private boolean sipCallViewActive() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) return false;
        ComponentName top = tasks.get(0).topActivity;
        return top != null && "com.commax.sipua".equals(top.getPackageName())
                && "com.commax.sipua.SipCallView".equals(top.getClassName());
    }

    private void publish(String requested, boolean value, String detail) {
        synchronized (STATE_LOCK) {
            if (!requested.equals(readyEventId)) readyEventId = requested;
            ready = value;
            readyDetail = detail == null ? "" : detail;
            STATE_LOCK.notifyAll();
        }
    }

    private void postAudioStopBestEffort(String requested) {
        try {
            postJson("/api/wallpad/audio/stop", new JSONObject().put("eventId", requested));
        } catch (Exception ignored) { }
    }

    private JSONObject getJson(String path) throws Exception {
        return new WallpadApiClient(this, 5000, 5000, true).getJson(path);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        return new WallpadApiClient(this, 5000, 5000, true).postJson(path, body);
    }

    private static String readText(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line).append('\n');
        return text.toString();
    }

    private static int rtpPayloadOffset(byte[] value, int base, int length) {
        if (length < 12 || (value[base] & 0xc0) != 0x80) return -1;
        int offset = base + 12 + (value[base] & 0x0f) * 4;
        int limit = base + length;
        if ((value[base] & 0x10) != 0) {
            if (offset + 4 > limit) return -1;
            int words = ((value[offset + 2] & 0xff) << 8) | (value[offset + 3] & 0xff);
            offset += 4 + words * 4;
        }
        return offset <= limit ? offset : -1;
    }

    private static void interrupt(Thread thread) {
        if (thread != null) thread.interrupt();
    }
}
