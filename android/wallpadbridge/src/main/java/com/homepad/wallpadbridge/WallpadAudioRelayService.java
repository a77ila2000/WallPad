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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Bridges the active COMMAX PCMU stream without replacing the stock SIP app.
 *
 * Incoming visitor RTP is observed passively with tcpdump.  Phone microphone
 * frames replace only the payload of the already-running wallpad RTP packets,
 * which preserves the vendor sequence number, timestamp and SSRC.  The single
 * temporary OUTPUT redirect is removed on every exit, and an independent root
 * watchdog removes it after 150 seconds if Android terminates this process.
 */
public final class WallpadAudioRelayService extends Service {
    private static final String TAG = "HomePadAudio";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int PCMU_FRAME_BYTES = 160;
    private static final int PROXY_PORT = 62101;
    private static final long MAX_SESSION_MS = 150_000L;
    private static final Object STATE_LOCK = new Object();
    private static String readyEventId = "";
    private static String readyDetail = "";
    private static boolean ready;

    private final ArrayBlockingQueue<byte[]> visitorUpload = new ArrayBlockingQueue<byte[]>(100);
    private final ArrayBlockingQueue<byte[]> phoneFrames = new ArrayBlockingQueue<byte[]>(100);
    private volatile boolean stopped;
    private volatile boolean phoneReady;
    private volatile Process capture;
    private volatile DatagramSocket proxy;
    private volatile RtpTuple tuple;
    private volatile String eventId = "";

    static boolean awaitReady(String requestedEventId, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (STATE_LOCK) {
            // startService() schedules onStartCommand asynchronously.  Wait for
            // this event to publish either a ready state or a concrete error;
            // an older/empty readyEventId must not make the caller fail early.
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
            return requestedEventId.equals(readyEventId) ? readyDetail : "오디오 중계 상태를 찾지 못했습니다";
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final String requested = intent == null ? null : intent.getStringExtra("eventId");
        if (requested == null || requested.length() != 32) return START_NOT_STICKY;
        stopCurrent();
        stopped = false;
        eventId = requested;
        synchronized (STATE_LOCK) {
            readyEventId = requested;
            readyDetail = "";
            ready = false;
            STATE_LOCK.notifyAll();
        }
        new Thread(new Runnable() {
            @Override public void run() { runRelay(requested); }
        }, "HomePadAudioRelay").start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        stopCurrent();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void runRelay(String requested) {
        Thread captureThread = null;
        Thread uploadThread = null;
        Thread phoneThread = null;
        boolean redirectInstalled = false;
        try {
            cleanupKnownRedirects();
            final CountDownLatch tupleReady = new CountDownLatch(1);
            captureThread = new Thread(new Runnable() {
                @Override public void run() { captureRtp(tupleReady); }
            }, "HomePadRtpCapture");
            captureThread.start();
            if (!tupleReady.await(8, TimeUnit.SECONDS) || tuple == null) {
                throw new IllegalStateException("활성 PCMU RTP 포트를 8초 안에 찾지 못했습니다");
            }

            uploadThread = new Thread(new Runnable() {
                @Override public void run() { uploadVisitorAudio(); }
            }, "HomePadAudioUpload");
            phoneThread = new Thread(new Runnable() {
                @Override public void run() { pollPhoneAudio(); }
            }, "HomePadPhoneAudio");
            uploadThread.start();
            phoneThread.start();

            proxy = new DatagramSocket(PROXY_PORT, InetAddress.getByName("0.0.0.0"));
            proxy.setSoTimeout(1000);
            installRedirect(tuple);
            redirectInstalled = true;
            startCleanupWatchdog(tuple);
            setReady(requested, true, "PCMU 양방향 중계가 연결되었습니다");
            Log.i(TAG, "Audio relay ready " + tuple);

            byte[] buffer = new byte[512];
            long startedAt = SystemClock.elapsedRealtime();
            while (!stopped && requested.equals(eventId)
                    && SystemClock.elapsedRealtime() - startedAt < MAX_SESSION_MS) {
                if (!sipCallViewActive()) break;
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    proxy.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                byte[] value = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), value, 0, value.length);
                int payloadOffset = rtpPayloadOffset(value, value.length);
                if (payloadOffset > 0 && value.length - payloadOffset == PCMU_FRAME_BYTES) {
                    byte[] replacement = phoneFrames.poll();
                    if (replacement != null) {
                        System.arraycopy(replacement, 0, value, payloadOffset, PCMU_FRAME_BYTES);
                    } else if (phoneReady) {
                        for (int index = payloadOffset; index < value.length; index++) value[index] = (byte) 0xff;
                    }
                }
                DatagramPacket outbound = new DatagramPacket(
                        value, value.length, InetAddress.getByName(tuple.remoteIp), tuple.remotePort);
                proxy.send(outbound);
            }
        } catch (Exception error) {
            if (!stopped) {
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                setReady(requested, false, detail);
                Log.e(TAG, "Audio relay failed", error);
            }
        } finally {
            stopped = true;
            if (redirectInstalled && tuple != null) removeRedirect(tuple);
            stopCapture();
            if (proxy != null) proxy.close();
            postAudioStopBestEffort(requested);
            synchronized (STATE_LOCK) {
                if (requested.equals(readyEventId)) {
                    ready = false;
                    if (readyDetail.length() == 0) readyDetail = "통화가 종료되었습니다";
                    STATE_LOCK.notifyAll();
                }
            }
            stopSelf();
        }
    }

    private void setReady(String requested, boolean value, String detail) {
        synchronized (STATE_LOCK) {
            if (!requested.equals(readyEventId)) return;
            ready = value;
            readyDetail = detail == null ? "" : detail;
            STATE_LOCK.notifyAll();
        }
    }

    private void stopCurrent() {
        stopped = true;
        stopCapture();
        DatagramSocket value = proxy;
        if (value != null) value.close();
        RtpTuple current = tuple;
        if (current != null) removeRedirect(current);
        tuple = null;
        visitorUpload.clear();
        phoneFrames.clear();
    }

    private void stopCapture() {
        Process value = capture;
        capture = null;
        if (value != null) value.destroy();
    }

    private void captureRtp(CountDownLatch tupleReady) {
        String localIp = localIpv4();
        if (localIp.length() == 0) {
            setReady(eventId, false, "월패드 IPv4 주소를 찾지 못했습니다");
            tupleReady.countDown();
            return;
        }
        try {
            Process process = new ProcessBuilder(
                    "/system/xbin/tcpdump", "-i", "any", "-s", "256", "-U", "-w", "-",
                    "udp", "portrange", "40000-65535")
                    .redirectErrorStream(false).start();
            capture = process;
            PcapReader reader = new PcapReader(new BufferedInputStream(process.getInputStream(), 32768));
            RtpTuple candidate = new RtpTuple(localIp);
            List<byte[]> batch = new ArrayList<byte[]>(5);
            int sipUid = getPackageManager().getApplicationInfo("com.commax.sipua", 0).uid;
            Set<Integer> sipPorts = new HashSet<Integer>();
            long portsRefreshedAt = 0L;
            while (!stopped) {
                UdpValue udp = reader.nextUdp();
                if (udp == null) break;
                int payloadOffset = rtpPayloadOffset(udp.payload, udp.payload.length);
                if (payloadOffset <= 0 || udp.payload.length - payloadOffset != PCMU_FRAME_BYTES) continue;
                boolean sourceIsLocal = localIp.equals(udp.sourceIp);
                boolean destinationIsLocal = localIp.equals(udp.destinationIp);
                boolean outgoing = sourceIsLocal && !destinationIsLocal;
                boolean incoming = destinationIsLocal && !sourceIsLocal;

                // COMMAX's unit entrance runs its door endpoint and wallpad SIP
                // endpoint on the same wallpad host.  In that case both IPs
                // are identical, so direction has to be derived from the UDP
                // socket owned by com.commax.sipua rather than from the IPs.
                if (sourceIsLocal && destinationIsLocal) {
                    long now = SystemClock.elapsedRealtime();
                    if (sipPorts.isEmpty() || now - portsRefreshedAt >= 500L) {
                        sipPorts = udpPortsForUid(sipUid);
                        portsRefreshedAt = now;
                    }
                    boolean sourceOwned = sipPorts.contains(udp.sourcePort);
                    boolean destinationOwned = sipPorts.contains(udp.destinationPort);
                    outgoing = sourceOwned && !destinationOwned;
                    incoming = destinationOwned && !sourceOwned;
                }

                if (outgoing) {
                    candidate.localPort = udp.sourcePort;
                    candidate.remoteIp = udp.destinationIp;
                    candidate.remotePort = udp.destinationPort;
                } else if (incoming) {
                    if (candidate.localPort == 0) candidate.localPort = udp.destinationPort;
                    if (candidate.remoteIp.length() == 0) candidate.remoteIp = udp.sourceIp;
                    if (candidate.remotePort == 0) candidate.remotePort = udp.sourcePort;
                    byte[] frame = new byte[PCMU_FRAME_BYTES];
                    System.arraycopy(udp.payload, payloadOffset, frame, 0, PCMU_FRAME_BYTES);
                    batch.add(frame);
                    if (batch.size() == 5) {
                        for (byte[] item : batch) {
                            if (!visitorUpload.offer(item)) visitorUpload.poll();
                        }
                        batch.clear();
                    }
                }
                if (tuple == null && candidate.complete()) {
                    tuple = candidate.copy();
                    tupleReady.countDown();
                }
            }
        } catch (Exception error) {
            if (!stopped) Log.e(TAG, "RTP capture stopped", error);
        } finally {
            tupleReady.countDown();
        }
    }

    private Set<Integer> udpPortsForUid(int uid) {
        Set<Integer> ports = new HashSet<Integer>();
        readUdpPortsForUid("/proc/net/udp", uid, ports);
        readUdpPortsForUid("/proc/net/udp6", uid, ports);
        return ports;
    }

    private void readUdpPortsForUid(String path, int uid, Set<Integer> ports) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(path), UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.trim().split("\\s+");
                if (values.length < 8 || !Integer.toString(uid).equals(values[7])) continue;
                int colon = values[1].lastIndexOf(':');
                if (colon < 0 || colon + 1 >= values[1].length()) continue;
                ports.add(Integer.parseInt(values[1].substring(colon + 1), 16));
            }
            reader.close();
        } catch (Exception ignored) { }
    }

    private void uploadVisitorAudio() {
        while (!stopped) {
            try {
                byte[] first = visitorUpload.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                ByteArrayOutputStream raw = new ByteArrayOutputStream(PCMU_FRAME_BYTES * 5);
                raw.write(first);
                for (int count = 1; count < 5; count++) {
                    byte[] next = visitorUpload.poll();
                    if (next == null) break;
                    raw.write(next);
                }
                JSONObject body = new JSONObject();
                body.put("eventId", eventId);
                body.put("frames", Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP));
                postJson("/api/wallpad/audio/visitor", body);
            } catch (Exception error) {
                if (!stopped) Log.w(TAG, "Visitor audio upload retry", error);
                SystemClock.sleep(200);
            }
        }
    }

    private void pollPhoneAudio() {
        long sequence = 0;
        while (!stopped) {
            try {
                JSONObject value = getJson("/api/wallpad/audio/phone?eventId="
                        + URLEncoder.encode(eventId, "UTF-8") + "&after=" + sequence + "&timeout=2");
                if (!value.optBoolean("active", false)) {
                    stopped = true;
                    return;
                }
                sequence = value.optLong("sequence", sequence);
                phoneReady = value.optBoolean("phoneReady", phoneReady);
                byte[] raw = Base64.decode(value.optString("frames", ""), Base64.DEFAULT);
                for (int offset = 0; offset + PCMU_FRAME_BYTES <= raw.length; offset += PCMU_FRAME_BYTES) {
                    byte[] frame = new byte[PCMU_FRAME_BYTES];
                    System.arraycopy(raw, offset, frame, 0, PCMU_FRAME_BYTES);
                    if (!phoneFrames.offer(frame)) phoneFrames.poll();
                }
            } catch (Exception error) {
                if (!stopped) Log.w(TAG, "Phone audio poll retry", error);
                SystemClock.sleep(200);
            }
        }
    }

    private void installRedirect(RtpTuple value) throws Exception {
        String source = value.localIp + ":" + value.localPort;
        root(true, "/system/bin/iptables", "-t", "nat", "-I", "POSTROUTING", "1",
                "-p", "udp", "--sport", Integer.toString(PROXY_PORT),
                "-d", value.remoteIp, "--dport", Integer.toString(value.remotePort),
                "-j", "SNAT", "--to-source", source);
        try {
            root(true, "/system/bin/iptables", "-t", "nat", "-I", "OUTPUT", "1",
                    "-p", "udp", "--sport", Integer.toString(value.localPort),
                    "-d", value.remoteIp, "--dport", Integer.toString(value.remotePort),
                    "-j", "REDIRECT", "--to-ports", Integer.toString(PROXY_PORT));
        } catch (Exception error) {
            removeRedirect(value);
            throw error;
        }
    }

    private void removeRedirect(RtpTuple value) {
        root(false, "/system/bin/iptables", "-t", "nat", "-D", "OUTPUT",
                "-p", "udp", "--sport", Integer.toString(value.localPort),
                "-d", value.remoteIp, "--dport", Integer.toString(value.remotePort),
                "-j", "REDIRECT", "--to-ports", Integer.toString(PROXY_PORT));
        root(false, "/system/bin/iptables", "-t", "nat", "-D", "POSTROUTING",
                "-p", "udp", "--sport", Integer.toString(PROXY_PORT),
                "-d", value.remoteIp, "--dport", Integer.toString(value.remotePort),
                "-j", "SNAT", "--to-source", value.localIp + ":" + value.localPort);
    }

    private void cleanupKnownRedirects() {
        // Calls use a narrow high-port range.  Remove only rules containing our
        // private proxy port; never flush Android's NAT tables.
        try {
            String listing = root(false, "/system/bin/iptables", "-t", "nat", "-S");
            for (String line : listing.split("\\n")) {
                if (!line.contains(Integer.toString(PROXY_PORT))) continue;
                String[] values = line.trim().split("\\s+");
                if (values.length < 3 || !"-A".equals(values[0])) continue;
                values[0] = "-D";
                String[] command = new String[values.length + 3];
                command[0] = "/system/bin/iptables";
                command[1] = "-t";
                command[2] = "nat";
                System.arraycopy(values, 0, command, 3, values.length);
                root(false, command);
            }
        } catch (Exception ignored) { }
    }

    private void startCleanupWatchdog(RtpTuple value) {
        String deleteOutput = "/system/bin/iptables -t nat -D OUTPUT -p udp --sport "
                + value.localPort + " -d " + value.remoteIp + " --dport " + value.remotePort
                + " -j REDIRECT --to-ports " + PROXY_PORT + " 2>/dev/null";
        String deleteSnat = "/system/bin/iptables -t nat -D POSTROUTING -p udp --sport "
                + PROXY_PORT + " -d " + value.remoteIp + " --dport " + value.remotePort
                + " -j SNAT --to-source " + value.localIp + ":" + value.localPort + " 2>/dev/null";
        root(false, "/sbin/busybox", "sh", "-c",
                "(/sbin/busybox sleep 150; " + deleteOutput + "; " + deleteSnat + ") &");
    }

    private void postAudioStopBestEffort(String requested) {
        try {
            JSONObject body = new JSONObject();
            body.put("eventId", requested);
            postJson("/api/wallpad/audio/stop", body);
        } catch (Exception ignored) { }
    }

    private boolean sipCallViewActive() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) return false;
        ComponentName top = tasks.get(0).topActivity;
        return top != null && "com.commax.sipua".equals(top.getPackageName())
                && "com.commax.sipua.SipCallView".equals(top.getClassName());
    }

    private String localIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface value : Collections.list(interfaces)) {
                if (!"eth0".equals(value.getName())) continue;
                for (InetAddress address : Collections.list(value.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private JSONObject getJson(String path) throws Exception {
        return new WallpadApiClient(this, 5000, 5000, true).getJson(path);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        return new WallpadApiClient(this, 5000, 5000, true).postJson(path, body);
    }

    private static String root(boolean required, String... command) {
        try {
            String[] root = new String[command.length + 2];
            root[0] = "su";
            root[1] = "-c";
            System.arraycopy(command, 0, root, 2, command.length);
            ProcessBuilder builder = new ProcessBuilder(root).redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.remove("CLASSPATH");
            environment.put("PATH", "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin");
            environment.put("LD_LIBRARY_PATH", "/vendor/lib:/system/lib");
            Process process = builder.start();
            String value = readText(process.getInputStream());
            int status = process.waitFor();
            if (required && status != 0) throw new IllegalStateException("root command failed " + status + ": " + value.trim());
            return value;
        } catch (Exception error) {
            if (required) throw new IllegalStateException(error);
            return "";
        }
    }

    private static String readText(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line).append('\n');
        return text.toString();
    }

    private static int rtpPayloadOffset(byte[] value, int length) {
        if (length < 12 || (value[0] & 0xc0) != 0x80 || (value[1] & 0x7f) != 0) return -1;
        int offset = 12 + (value[0] & 0x0f) * 4;
        if ((value[0] & 0x10) != 0) {
            if (offset + 4 > length) return -1;
            int words = ((value[offset + 2] & 0xff) << 8) | (value[offset + 3] & 0xff);
            offset += 4 + words * 4;
        }
        return offset <= length ? offset : -1;
    }

    private static final class RtpTuple {
        final String localIp;
        int localPort;
        String remoteIp = "";
        int remotePort;
        RtpTuple(String localIp) { this.localIp = localIp; }
        boolean complete() { return localPort > 0 && remotePort > 0 && remoteIp.length() > 0; }
        RtpTuple copy() {
            RtpTuple value = new RtpTuple(localIp);
            value.localPort = localPort;
            value.remoteIp = remoteIp;
            value.remotePort = remotePort;
            return value;
        }
        @Override public String toString() { return localIp + ":" + localPort + " -> " + remoteIp + ":" + remotePort; }
    }

    private static final class UdpValue {
        String sourceIp;
        String destinationIp;
        int sourcePort;
        int destinationPort;
        byte[] payload;
    }

    /** Minimal pcap reader for tcpdump's Linux cooked/Ethernet IPv4 output. */
    private static final class PcapReader {
        private final InputStream input;
        private final boolean little;
        private final int linkType;

        PcapReader(InputStream input) throws Exception {
            this.input = input;
            byte[] header = readExact(input, 24);
            int magic = be32(header, 0);
            little = magic == 0xd4c3b2a1 || magic == 0x4d3cb2a1;
            if (!little && magic != 0xa1b2c3d4 && magic != 0xa1b23c4d) throw new IllegalStateException("invalid pcap header");
            linkType = int32(header, 20, little);
        }

        UdpValue nextUdp() throws Exception {
            while (true) {
                byte[] record;
                try { record = readExact(input, 16); }
                catch (EOFException end) { return null; }
                int captured = int32(record, 8, little);
                if (captured <= 0 || captured > 65535) throw new IllegalStateException("invalid pcap record");
                byte[] packet = readExact(input, captured);
                int offset = linkType == 113 ? 16 : (linkType == 1 ? 14 : 0);
                if (offset + 28 > packet.length || (packet[offset] & 0xf0) != 0x40) continue;
                int ipLength = (packet[offset] & 0x0f) * 4;
                if (ipLength < 20 || offset + ipLength + 8 > packet.length || (packet[offset + 9] & 0xff) != 17) continue;
                int udp = offset + ipLength;
                int udpLength = be16(packet, udp + 4);
                int payloadLength = Math.min(packet.length - udp - 8, udpLength - 8);
                if (payloadLength <= 0) continue;
                UdpValue value = new UdpValue();
                value.sourceIp = ipv4(packet, offset + 12);
                value.destinationIp = ipv4(packet, offset + 16);
                value.sourcePort = be16(packet, udp);
                value.destinationPort = be16(packet, udp + 2);
                value.payload = new byte[payloadLength];
                System.arraycopy(packet, udp + 8, value.payload, 0, payloadLength);
                return value;
            }
        }

        private static byte[] readExact(InputStream input, int count) throws Exception {
            byte[] value = new byte[count];
            int offset = 0;
            while (offset < count) {
                int read = input.read(value, offset, count - offset);
                if (read < 0) throw new EOFException();
                offset += read;
            }
            return value;
        }

        private static int int32(byte[] value, int offset, boolean little) {
            if (little) return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8)
                    | ((value[offset + 2] & 0xff) << 16) | ((value[offset + 3] & 0xff) << 24);
            return be32(value, offset);
        }
        private static int be32(byte[] value, int offset) {
            return ((value[offset] & 0xff) << 24) | ((value[offset + 1] & 0xff) << 16)
                    | ((value[offset + 2] & 0xff) << 8) | (value[offset + 3] & 0xff);
        }
        private static int be16(byte[] value, int offset) {
            return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
        }
        private static String ipv4(byte[] value, int offset) {
            return (value[offset] & 0xff) + "." + (value[offset + 1] & 0xff) + "."
                    + (value[offset + 2] & 0xff) + "." + (value[offset + 3] & 0xff);
        }
    }
}
