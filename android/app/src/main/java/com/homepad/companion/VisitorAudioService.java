package com.homepad.companion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground PCMU bridge for an already accepted COMMAX visitor call. */
public final class VisitorAudioService extends Service {
    private static final String ACTION_START = "com.homepad.companion.START_VISITOR_AUDIO";
    private static final String ACTION_STOP = "com.homepad.companion.STOP_VISITOR_AUDIO";
    private static final String EXTRA_EVENT_ID = "event_id";
    private static final String CHANNEL = "visitor_audio_v1";
    private static final int NOTIFICATION_ID = 1202;
    private static final int SAMPLE_RATE = 8000;
    private static final int SAMPLES_PER_FRAME = 160;
    private static final Object STATE_LOCK = new Object();
    private static String currentEventId = "";
    private static String currentState = "idle";
    private static String currentDetail = "";
    private static long connectedAt;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private volatile Thread controller;
    private VisitorSpeakerphone speakerphone;
    private AcousticEchoCanceler echoCanceler;

    static void start(Context context, String eventId) {
        Intent intent = new Intent(context, VisitorAudioService.class)
                .setAction(ACTION_START).putExtra(EXTRA_EVENT_ID, eventId);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context, String eventId) {
        Intent intent = new Intent(context, VisitorAudioService.class)
                .setAction(ACTION_STOP).putExtra(EXTRA_EVENT_ID, eventId);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static String stateFor(String eventId) {
        synchronized (STATE_LOCK) {
            return eventId != null && eventId.equals(currentEventId) ? currentState : "idle";
        }
    }

    static String detailFor(String eventId) {
        synchronized (STATE_LOCK) {
            return eventId != null && eventId.equals(currentEventId) ? currentDetail : "";
        }
    }

    static long connectedAtFor(String eventId) {
        synchronized (STATE_LOCK) {
            return eventId != null && eventId.equals(currentEventId) ? connectedAt : 0;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "방문자 통화", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("공동현관과 세대현관 양방향 음성 통화");
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String eventId = intent == null ? "" : intent.getStringExtra(EXTRA_EVENT_ID);
        if (eventId == null) eventId = "";
        if (ACTION_STOP.equals(intent == null ? "" : intent.getAction())) {
            beginForeground(eventId, "통화를 종료하는 중입니다");
            stopCall(eventId, true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent == null ? "" : intent.getAction()) || eventId.length() != 32) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // A previous controller must finish before a new one can acquire the
        // global audio route or stop the relay belonging to its successor.
        if (controller != null && controller.isAlive()) return START_NOT_STICKY;
        beginForeground(eventId, "스피커폰으로 방문자 음성을 연결하는 중입니다");
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setState(eventId, "error", "마이크 권한이 필요합니다", 0);
            updateNotification("마이크 권한이 필요합니다");
            stopSelf();
            return START_NOT_STICKY;
        }
        stopCall(eventId, false);
        running.set(true);
        final String requested = eventId;
        controller = new Thread(() -> runCall(requested), "HomePadPhoneCall");
        controller.start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        stopCall(currentEventId, false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void beginForeground(String eventId, String text) {
        setState(eventId, "connecting", text, 0);
        Notification notification = notification(text, eventId);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void runCall(String eventId) {
        ApiClient api = api();
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("마이크 권한이 필요합니다");
            }
            JSONObject start = new JSONObject();
            start.put("action", "start");
            start.put("requestId", UUID.randomUUID().toString());
            api.postJson("/api/calls/" + eventId + "/audio/start", start);

            synchronized (this) {
                if (!running.get()) return;
                speakerphone = new VisitorSpeakerphone(this, () -> {
                    running.set(false);
                    releaseAudio();
                    restoreAudioRoute();
                });
                speakerphone.open();
            }
            int recordBuffer = Math.max(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    SAMPLES_PER_FRAME * 2 * 10);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("마이크 권한이 필요합니다");
            }
            AudioRecord input = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, recordBuffer);
            if (input.getState() != AudioRecord.STATE_INITIALIZED) {
                input.release();
                throw new IllegalStateException("휴대폰 마이크를 열지 못했습니다");
            }
            int playBuffer = Math.max(
                    AudioTrack.getMinBufferSize(SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    SAMPLES_PER_FRAME * 2 * 10);
            AudioTrack output = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    playBuffer, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (output.getState() != AudioTrack.STATE_INITIALIZED) {
                input.release();
                output.release();
                throw new IllegalStateException("휴대폰 통화 스피커를 열지 못했습니다");
            }
            synchronized (this) {
                if (!running.get()) {
                    input.release();
                    output.release();
                    return;
                }
                recorder = input;
                player = output;
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(input.getAudioSessionId());
                        if (echoCanceler != null) echoCanceler.setEnabled(true);
                    } catch (RuntimeException ignored) { }
                }
                input.startRecording();
                output.play();
            }
            long now = SystemClock.elapsedRealtime();
            setState(eventId, "connected", "스피커폰으로 방문자와 통화합니다 · 음량 버튼으로 조절하세요", now);
            updateNotification("스피커폰 통화 중 · 음량 버튼으로 조절");

            Thread playback = new Thread(() -> playbackLoop(api, eventId), "HomePadCallPlayback");
            Thread capture = new Thread(() -> captureLoop(api, eventId), "HomePadCallCapture");
            playback.start();
            capture.start();
            while (running.get() && playback.isAlive() && capture.isAlive()) SystemClock.sleep(100);
            running.set(false);
            playback.interrupt();
            capture.interrupt();
            playback.join(2000);
            capture.join(2000);
        } catch (Exception error) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            setState(eventId, "error", detail, 0);
            updateNotification("통화 연결 실패 · " + detail);
            SystemClock.sleep(2500);
        } finally {
            releaseAudio();
            restoreAudioRoute();
            postStop(api, eventId);
            running.set(false);
            if (!"error".equals(stateFor(eventId))) setState(eventId, "ended", "통화가 종료되었습니다", 0);
            stopForeground(true);
            stopSelf();
        }
    }

    private void playbackLoop(ApiClient api, String eventId) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        long sequence = 0;
        try {
            while (running.get()) {
                JSONObject value = api.getJson("/api/calls/" + eventId
                        + "/audio/visitor?after=" + sequence + "&timeout=2");
                if (!value.optBoolean("active", false)) break;
                sequence = value.optLong("sequence", sequence);
                byte[] encoded = Base64.decode(value.optString("frames", ""), Base64.DEFAULT);
                if (encoded.length == 0) continue;
                short[] pcm = new short[encoded.length];
                for (int index = 0; index < encoded.length; index++) pcm[index] = decodeMuLaw(encoded[index]);
                AudioTrack output = player;
                if (output != null) output.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
            }
        } catch (Exception ignored) {
        } finally {
            running.set(false);
        }
    }

    private void captureLoop(ApiClient api, String eventId) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] pcm = new short[SAMPLES_PER_FRAME];
        ByteArrayOutputStream batch = new ByteArrayOutputStream(SAMPLES_PER_FRAME * 5);
        try {
            while (running.get()) {
                AudioRecord input = recorder;
                if (input == null) break;
                int count = input.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) continue;
                for (int index = 0; index < count; index++) batch.write(encodeMuLaw(pcm[index]));
                if (batch.size() < SAMPLES_PER_FRAME * 5) continue;
                JSONObject body = new JSONObject();
                body.put("frames", Base64.encodeToString(batch.toByteArray(), Base64.NO_WRAP));
                api.postJson("/api/calls/" + eventId + "/audio/phone", body);
                batch.reset();
            }
        } catch (Exception ignored) {
        } finally {
            running.set(false);
        }
    }

    private void stopCall(String eventId, boolean endService) {
        running.set(false);
        releaseAudio();
        restoreAudioRoute();
        if (endService) {
            postStop(api(), eventId);
            setState(eventId, "ended", "통화가 종료되었습니다", 0);
            stopForeground(true);
            stopSelf();
        }
    }

    private synchronized void releaseAudio() {
        if (echoCanceler != null) {
            echoCanceler.release();
            echoCanceler = null;
        }
        AudioRecord input = recorder;
        recorder = null;
        if (input != null) {
            try { input.stop(); } catch (Exception ignored) { }
            input.release();
        }
        AudioTrack output = player;
        player = null;
        if (output != null) {
            try { output.stop(); } catch (Exception ignored) { }
            output.release();
        }
    }

    private synchronized void restoreAudioRoute() {
        if (speakerphone != null) {
            try { speakerphone.close(); } catch (RuntimeException ignored) { }
            speakerphone = null;
        }
    }

    private void postStop(ApiClient api, String eventId) {
        if (eventId == null || eventId.length() != 32) return;
        try { api.postJson("/api/calls/" + eventId + "/audio/stop", new JSONObject().put("action", "stop")); }
        catch (Exception ignored) { }
    }

    private ApiClient api() {
        SharedPreferences preferences = getSharedPreferences("homepad", MODE_PRIVATE);
        return new ApiClient(
                preferences.getString("server", BuildConfig.DEFAULT_SERVER_URL),
                preferences.getString("token", BuildConfig.DEFAULT_API_TOKEN));
    }

    private Notification notification(String text, String eventId) {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(VisitorNotificationService.EXTRA_OPEN_VISITORS, true)
                .putExtra(VisitorNotificationService.EXTRA_VISITOR_EVENT_ID, eventId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 1202, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, VisitorAudioService.class)
                .setAction(ACTION_STOP).putExtra(EXTRA_EVENT_ID, eventId);
        PendingIntent stopPending = PendingIntent.getService(this, 1203, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("방문자 통화")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(pending)
                .addAction(R.drawable.ic_notification, "통화 종료", stopPending)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(text, currentEventId));
    }

    private static void setState(String eventId, String state, String detail, long started) {
        synchronized (STATE_LOCK) {
            currentEventId = eventId == null ? "" : eventId;
            currentState = state;
            currentDetail = detail == null ? "" : detail;
            connectedAt = started;
        }
    }

    private static byte encodeMuLaw(short sample) {
        final int bias = 0x84;
        int value = sample;
        int sign = (value >> 8) & 0x80;
        if (sign != 0) value = -value;
        if (value > 32635) value = 32635;
        value += bias;
        int exponent = 7;
        for (int mask = 0x4000; exponent > 0 && (value & mask) == 0; exponent--, mask >>= 1) { }
        int mantissa = (value >> (exponent + 3)) & 0x0f;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    private static short decodeMuLaw(byte encoded) {
        int value = (~encoded) & 0xff;
        int sign = value & 0x80;
        int exponent = (value >> 4) & 0x07;
        int mantissa = value & 0x0f;
        int sample = ((mantissa << 3) + 0x84) << exponent;
        sample -= 0x84;
        return (short) (sign != 0 ? -sample : sample);
    }
}
