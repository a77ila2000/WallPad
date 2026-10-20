package com.homepad.wallpadbridge;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.conscrypt.Conscrypt;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class WallpadBridgeService extends IntentService {
    private static final String TAG = "HomePadBridge";
    private static final String PREFS = "bridge_state";
    private static final String QUEUE = "parking_queue";
    private static final String LAST_VISITOR_PHOTO = "last_visitor_photo";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Object SIP_CONTEXT_LOCK = new Object();
    private static long cachedOffering;
    private static SipCallContext cachedSipContext;

    public WallpadBridgeService() {
        super("HomePadWallpadBridge");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (WallpadQuietHours.ACTION.equals(action)) {
            WallpadQuietHours.apply(this, true);
            return;
        }
        if ("com.homepad.wallpadbridge.PROBE".equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            WallpadQuietHours.apply(this, true);
            ensureSipRtpCapture();
            ensureUnitDoorCameraRelay();
            probe();
            flushQueue();
            return;
        }
        if ("com.homepad.wallpadbridge.RETRY".equals(action)) {
            flushQueue();
            return;
        }
        if ("com.commax.service.sipua.SIPUA_OFFERING_ACTION".equals(action)) {
            deliverVisitorCall(intent.getStringExtra("detectedAt"), intent.getStringExtra("localCallId"));
            return;
        }
        if ("com.commax.service.visitor.INSERT_VISITOR_ACTION".equals(action)) {
            deliverVisitorPhoto(intent.getStringExtra("capturedAt"));
            return;
        }
        if ("com.commax.service.system.REG_CHECK_EVENT".equals(action)) {
            try {
                JSONObject event = new JSONObject();
                event.put("type", intent.getIntExtra("eventType", 0));
                event.put("occurredAt", intent.getStringExtra("occurredAt"));
                event.put("dummy", intent.getStringExtra("eventDummy"));
                deliver(event);
            } catch (Exception error) {
                Log.e(TAG, "Parking event delivery failed", error);
                try {
                    JSONObject event = new JSONObject();
                    event.put("type", intent.getIntExtra("eventType", 0));
                    event.put("occurredAt", intent.getStringExtra("occurredAt"));
                    event.put("dummy", intent.getStringExtra("eventDummy"));
                    enqueue(event);
                } catch (Exception ignored) { }
                scheduleRetry();
            }
        }
    }

    private void probe() {
        try {
            JSONObject body = new JSONObject();
            body.put("probe", true);
            body.put("carNumber", queryParkingCarNumber());
            post(body);
            Log.i(TAG, "Probe accepted");
        } catch (Exception error) {
            Log.e(TAG, "Probe failed", error);
            scheduleRetry();
        }
    }

    private void deliver(JSONObject event) throws Exception {
        int type = event.optInt("type", 0);
        if (type != 1 && type != 2) {
            throw new IllegalArgumentException("Unsupported parking event type");
        }
        String carNumber = event.optString("carNumber", "").trim();
        if (carNumber.length() == 0) {
            JSONArray samples = new JSONArray();
            String dummyPlate = plausiblePlate(event.optString("dummy", ""));
            if (dummyPlate.length() > 0) {
                carNumber = dummyPlate;
                samples.put("dummy:" + dummyPlate);
            } else if (type == 2) {
                // The stock exit dialog reads the same SOAP value immediately.
                // Waiting first can miss it after the service clears the value.
                carNumber = sampleParkingNumber(samples, 0, 100, 250, 500);
            } else {
                // On entry the value can change just after the broadcast, so
                // keep the previous proven delay but retry briefly if needed.
                carNumber = sampleParkingNumber(samples, 350, 200, 350);
            }
            event.put("lookupSamples", samples);
            if (carNumber.length() == 0) throw new IllegalStateException("Empty parking number");
            event.put("carNumber", carNumber);
        }
        post(event);
        Log.i(TAG, "Parking event delivered type=" + type + " car=" + carNumber
                + " dummy=" + event.optString("dummy", ""));
    }

    private String sampleParkingNumber(JSONArray samples, int... delaysMs) {
        String last = "";
        for (int delay : delaysMs) {
            if (delay > 0) SystemClock.sleep(delay);
            try {
                String value = queryParkingCarNumber().trim();
                samples.put(value.length() == 0 ? "<empty>" : value);
                if (value.length() > 0) {
                    last = value;
                    // For exit, the first non-empty sample is the important
                    // one; entry callers provide an initial delay instead.
                    return last;
                }
            } catch (Exception error) {
                samples.put("<error:" + error.getClass().getSimpleName() + ">");
            }
        }
        return last;
    }

    private static String plausiblePlate(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.length() < 4 || candidate.length() > 20) return "";
        int digits = 0;
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isDigit(candidate.charAt(i))) digits++;
        }
        return digits >= 4 ? candidate : "";
    }

    private String queryParkingCarNumber() throws Exception {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:c=\"urn:cvs\">"
                + "<soap:Body><c:getParkingCarNumber><in>0</in></c:getParkingCarNumber></soap:Body>"
                + "</soap:Envelope>";
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:29740/").openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(2500);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        byte[] bytes = envelope.getBytes(UTF8);
        connection.setFixedLengthStreamingMode(bytes.length);
        OutputStream output = connection.getOutputStream();
        output.write(bytes);
        output.close();
        String response = read(connection.getInputStream());
        connection.disconnect();
        String value = between(response, "<return", "</return>");
        int close = value.indexOf('>');
        if (close >= 0) value = value.substring(close + 1);
        return value.replace("&amp;", "&").trim();
    }

    private void post(JSONObject body) throws Exception {
        postJson("/api/parking/wallpad-event", body);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        return new WallpadApiClient(this, 8000, 8000).postJson(path, body);
    }

    private void deliverVisitorCall(String detectedAt, String localCallId) {
        try {
            long ringObservedAt = System.currentTimeMillis();
            SipCallContext call = inspectSipCall();
            if (call.place != 2 && call.place != 5) {
                Log.w(TAG, "Ignoring non-door SIP offering place=" + call.place);
                return;
            }
            JSONObject body = new JSONObject();
            body.put("source", "wallpad-sipua");
            body.put("signal", "sipua-offering-wallpad");
            body.put("kind", call.place == 5 ? "central_entrance" : "unit_entrance");
            body.put("callPlace", call.place);
            body.put("localCallId", localCallId == null ? "" : localCallId);
            body.put("detectedAt", detectedAt == null ? "" : detectedAt);
            if (call.sourceId.startsWith("lobby")) body.put("sourceId", call.sourceId);
            if (call.remoteIp.length() > 0) body.put("deviceIp", call.remoteIp);
            JSONObject accepted = postJson("/api/visitors/ring", body);
            String eventId = accepted.optString("eventId", "");
            if (eventId.length() == 32 && !"ended".equals(accepted.optString("callState"))) {
                ensureSipRtpCapture();
                Intent monitor = new Intent(this, WallpadCommandService.class);
                monitor.putExtra("eventId", eventId);
                monitor.putExtra("kind", call.place == 5 ? "central_entrance" : "unit_entrance");
                monitor.putExtra("sourceId", call.sourceId);
                startService(monitor);
            }
            Log.i(TAG, "Visitor call delivered place=" + call.place + " source=" + call.sourceId);
            // COMMAX writes the visitor JPEG near the start of a call but
            // broadcasts INSERT_VISITOR_ACTION only when the call ends. Poll
            // this read-only folder briefly so the phone can see the photo
            // while the visitor is still waiting.
            watchVisitorPhoto(call.place == 5 ? "central_entrance" : "unit_entrance",
                    ringObservedAt - 2000L, eventId);
        } catch (Exception error) {
            Log.e(TAG, "Visitor call delivery failed", error);
        }
    }

    private void ensureSipRtpCapture() {
        startService(new Intent(this, WallpadCaptureService.class));
    }

    private void ensureUnitDoorCameraRelay() {
        startService(new Intent(this, WallpadCameraService.class));
    }

    static SipCallContext inspectSipCall() throws Exception {
        synchronized (SIP_CONTEXT_LOCK) {
            long offering = WallpadEventReceiver.lastOfferingAt();
            if (offering > 0L && offering == cachedOffering && cachedSipContext != null)
                return cachedSipContext;
            SipCallContext parsed = readSipCall();
            if (offering > 0L && offering == WallpadEventReceiver.lastOfferingAt()
                    && Math.abs(System.currentTimeMillis() - parsed.offeredEpoch) <= 5000L) {
                cachedOffering = offering;
                cachedSipContext = parsed;
            }
            return parsed;
        }
    }

    private static SipCallContext readSipCall() throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) SystemClock.sleep(150);
            try {
                Process process = new ProcessBuilder(
                        "su", "-c", "/system/bin/logcat", "-d", "-v", "time", "-s", "SipUA:V")
                        .redirectErrorStream(true).start();
                List<String> lines = new ArrayList<String>();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF8));
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() > 400) lines.remove(0);
                }
                reader.close();
                process.waitFor();
                SipOfferingParser.Context parsed = SipOfferingParser.parse(lines);
                return new SipCallContext(parsed.place, parsed.sourceId, parsed.remoteIp, parsed.offeredEpoch);
            } catch (Exception error) {
                lastError = error;
            }
        }
        throw lastError == null ? new IllegalStateException("Missing SIP context") : lastError;
    }

    private void deliverVisitorPhoto(String capturedAt) {
        try {
            SystemClock.sleep(500);
            File photo = newestVisitorPhoto();
            if (photo == null || !photo.isFile()) throw new IllegalStateException("Visitor JPEG not found");
            uploadVisitorPhotoIfNew(photo, visitorKindFromFile(photo), capturedAt);
        } catch (Exception error) {
            Log.e(TAG, "Visitor photo delivery failed", error);
        }
    }

    private void watchVisitorPhoto(String expectedKind, long earliestModified, String eventId) {
        long deadline = SystemClock.elapsedRealtime() + 12000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            File photo = newestVisitorPhoto();
            if (photo != null && photo.lastModified() >= earliestModified
                    && (expectedKind.equals(visitorKindFromFile(photo))
                        || visitorKindFromFile(photo).length() == 0)
                    && completeJpeg(photo)) {
                try {
                    if (uploadVisitorPhotoIfNew(photo, expectedKind, captureTime(photo), eventId)) {
                        Log.i(TAG, "Visitor photo delivered during call");
                    }
                    return;
                } catch (Exception error) {
                    Log.w(TAG, "Early visitor photo delivery failed; end-of-call retry remains", error);
                    return;
                }
            }
            SystemClock.sleep(400L);
        }
        Log.i(TAG, "No early visitor JPEG; waiting for COMMAX insert event");
    }

    static boolean completeJpeg(File photo) {
        if (!photo.isFile() || photo.length() < 1024L) return false;
        try {
            RandomAccessFile input = new RandomAccessFile(photo, "r");
            try {
                input.seek(photo.length() - 2L);
                return input.readUnsignedByte() == 0xff && input.readUnsignedByte() == 0xd9;
            } finally { input.close(); }
        } catch (Exception ignored) { return false; }
    }

    static String captureTime(File photo) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(photo.lastModified()));
    }

    private boolean uploadVisitorPhotoIfNew(File photo, String kind, String capturedAt) throws Exception {
        return uploadVisitorPhotoIfNew(photo, kind, capturedAt, "");
    }

    private boolean uploadVisitorPhotoIfNew(File photo, String kind, String capturedAt, String eventId) throws Exception {
        String signature = photo.getAbsolutePath() + ":" + photo.length() + ":" + photo.lastModified();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (signature.equals(preferences.getString(LAST_VISITOR_PHOTO, ""))) return false;
        uploadPhoto(photo, kind, capturedAt, eventId);
        preferences.edit().putString(LAST_VISITOR_PHOTO, signature).apply();
        Log.i(TAG, "Visitor photo delivered file=" + photo.getName() + " kind=" + kind);
        return true;
    }

    static File newestVisitorPhoto() {
        String[] directories = {
                "/sdcard/cmx_data/visitor/",
                "/mnt/extsd/cmx_data/visitor/"
        };
        File newest = null;
        for (String directory : directories) {
            File[] files = new File(directory).listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jpg")) continue;
                if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
            }
        }
        return newest;
    }

    static String visitorKindFromFile(File photo) {
        String name = photo.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String[] parts = name.split("-");
        if (parts.length >= 4) {
            try {
                int placeType = Integer.parseInt(parts[parts.length - 2]);
                if (placeType == 1) return "central_entrance";
                if (placeType == 0 || placeType == 4) return "unit_entrance";
            } catch (NumberFormatException ignored) { }
        }
        return "";
    }

    private void uploadPhoto(File photo, String kind, String capturedAt, String eventId) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        if (kind.length() > 0) headers.put("X-Visitor-Kind", kind);
        if (capturedAt != null) headers.put("X-Visitor-Captured-At", capturedAt);
        if (eventId != null && eventId.length() == 32) headers.put("X-Visitor-Event-Id", eventId);
        new WallpadApiClient(this, 8000, 12000)
                .postFile("/api/visitors/photo", "image/jpeg", photo, headers);
    }

    static final class SipCallContext {
        final int place;
        final String sourceId;
        final String remoteIp;

        final long offeredEpoch;

        SipCallContext(int place, String sourceId, String remoteIp, long offeredEpoch) {
            this.offeredEpoch = offeredEpoch;
            this.place = place;
            this.sourceId = sourceId == null ? "" : sourceId;
            this.remoteIp = remoteIp == null ? "" : remoteIp;
        }
    }

    private SSLContext sslContext() throws Exception {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        InputStream input = getResources().openRawResource(R.raw.isrgrootx1);
        Certificate certificate = factory.generateCertificate(input);
        input.close();
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("isrg-root-x1", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext context = SSLContext.getInstance("TLS", "Conscrypt");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private void enqueue(JSONObject event) {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray queue;
        try { queue = new JSONArray(preferences.getString(QUEUE, "[]")); }
        catch (Exception ignored) { queue = new JSONArray(); }
        queue.put(event);
        while (queue.length() > 30) {
            JSONArray trimmed = new JSONArray();
            for (int i = 1; i < queue.length(); i++) trimmed.put(queue.opt(i));
            queue = trimmed;
        }
        preferences.edit().putString(QUEUE, queue.toString()).commit();
    }

    private void flushQueue() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray queue;
        try { queue = new JSONArray(preferences.getString(QUEUE, "[]")); }
        catch (Exception ignored) { queue = new JSONArray(); }
        JSONArray pending = new JSONArray();
        for (int i = 0; i < queue.length(); i++) {
            JSONObject event = queue.optJSONObject(i);
            if (event == null) continue;
            try { deliver(event); }
            catch (Exception error) {
                Log.e(TAG, "Queued parking event still pending", error);
                pending.put(event);
            }
        }
        preferences.edit().putString(QUEUE, pending.toString()).commit();
        if (pending.length() > 0) scheduleRetry();
    }

    private void scheduleRetry() {
        Intent intent = new Intent(this, WallpadInternalReceiver.class);
        intent.setAction("com.homepad.wallpadbridge.RETRY");
        PendingIntent pending = PendingIntent.getBroadcast(
                this, 4107, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 60_000L, pending);
        }
    }

    private static String read(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8));
        StringBuilder value = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) value.append(line);
        reader.close();
        return value.toString();
    }

    private static String between(String value, String start, String end) {
        int left = value.indexOf(start);
        if (left < 0) return "";
        int right = value.indexOf(end, left);
        if (right < 0) return "";
        return value.substring(left + start.length(), right);
    }
}
