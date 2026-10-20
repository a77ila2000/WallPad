package com.homepad.wallpadbridge;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/** Keeps a SIP/RTP capture ready before the visitor presses call. */
public final class WallpadCaptureService extends Service {
    private static final String TAG = "HomePadBridge";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CAPTURE_NAME = "homepad-sip-rtp.pcap";
    private static final Object LOCK = new Object();
    private static Process capture;
    private static boolean starting;
    private volatile boolean stopped;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (LOCK) {
            if (starting || capture != null) return START_STICKY;
            stopped = false;
            starting = true;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                runCapture();
            }
        }, "HomePadSipRtpCapture").start();
        return START_STICKY;
    }

    private void runCapture() {
        try {
            String capturePath = new File(getFilesDir(), CAPTURE_NAME).getAbsolutePath();
            // A force-stop on this Android 4.2 build can orphan native child
            // processes. Remove only recorders containing our private output
            // path before starting the single replacement instance.
            stopOrphanCapture(capturePath);
            // tcpdump opens its socket with its existing vendor privilege,
            // then returns to this app's UID before writing. Create files in
            // our private directory without relying on `su` or shared tmp.
            for (int index = 0; index < 3; index++) {
                File file = new File(capturePath + index);
                if (!file.exists() && !file.createNewFile()) {
                    throw new IOException("Cannot create private capture file");
                }
                if (!file.isFile() || !file.canWrite()) {
                    throw new IOException("Private capture file is not writable");
                }
                file.setReadable(false, false);
                file.setWritable(false, false);
                if (!file.setReadable(true, true) || !file.setWritable(true, true)) {
                    throw new IOException("Cannot restrict capture file permissions");
                }
            }
            // tcpdump is already installed with the capture privilege on this
            // wallpad. Starting it directly lets Process.destroy() terminate
            // the real recorder. Starting through `su -c` only destroyed the
            // wrapper and leaked one recorder after every service restart.
            Process process = new ProcessBuilder(
                    "/system/xbin/tcpdump",
                    "-i", "any", "-s", "0", "-U",
                    "-C", "4", "-W", "3",
                    "-w", capturePath,
                    "udp port 5060 or tcp port 29715 or tcp port 29752 or udp portrange 40000-65535")
                    .redirectErrorStream(true).start();
            synchronized (LOCK) {
                if (stopped) {
                    process.destroy();
                    return;
                }
                capture = process;
                starting = false;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("listening on")) {
                    Log.i(TAG, "Private SIP/RTP capture listening (3 files, max 12 MB)");
                }
                Log.d(TAG, "tcpdump: " + line);
            }
            int status = process.waitFor();
            Log.w(TAG, "Continuous SIP/RTP capture exited status=" + status);
        } catch (Exception error) {
            Log.e(TAG, "Continuous SIP/RTP capture failed", error);
        } finally {
            synchronized (LOCK) {
                capture = null;
                starting = false;
            }
        }
    }

    private void stopOrphanCapture(String capturePath) {
        File[] processes = new File("/proc").listFiles();
        if (processes == null) return;
        for (File processDir : processes) {
            try {
                int pid = Integer.parseInt(processDir.getName());
                boolean owned = false;
                BufferedReader status = new BufferedReader(new InputStreamReader(
                        new FileInputStream(new File(processDir, "status")), UTF8));
                try {
                    String line;
                    while ((line = status.readLine()) != null) {
                        if (line.startsWith("Uid:")) {
                            owned = Integer.parseInt(line.trim().split("\\s+")[1])
                                    == android.os.Process.myUid();
                            break;
                        }
                    }
                } finally { status.close(); }
                if (!owned) continue;
                FileInputStream input = new FileInputStream(new File(processDir, "cmdline"));
                byte[] bytes = new byte[8192];
                int size;
                try { size = input.read(bytes); } finally { input.close(); }
                if (size <= 0) continue;
                String command = new String(bytes, 0, size, UTF8);
                if (command.startsWith("/system/xbin/tcpdump\u0000")
                        && command.contains("\u0000-w\u0000" + capturePath + "\u0000")) {
                    android.os.Process.sendSignal(pid, 15);
                    android.os.SystemClock.sleep(100L);
                }
            } catch (Exception ignored) {
                // Processes may exit while /proc is read. Never touch another
                // UID or a recorder using a different output path.
            }
        }
    }

    @Override public void onDestroy() {
        synchronized (LOCK) {
            stopped = true;
            if (capture != null) capture.destroy();
            capture = null;
            starting = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
