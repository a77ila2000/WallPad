package com.homepad.wallpadbridge;

import android.os.FileObserver;
import android.os.SystemClock;
import java.io.File;

/** Short-lived JPEG close/rename watches, only during an actual incoming call. */
final class VisitorPhotoAwaiter {
    private final String[] directories;
    private final Object signal = new Object();
    private final FileObserver[] observers;
    private long generation;

    VisitorPhotoAwaiter(String[] directories) {
        this.directories = directories;
        observers = new FileObserver[directories.length];
        for (int i = 0; i < directories.length; i++) {
            if (!new File(directories[i]).isDirectory()) continue;
            observers[i] = new FileObserver(directories[i], FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override public void onEvent(int event, String path) {
                    if (path == null || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")) return;
                    synchronized (signal) { generation++; signal.notifyAll(); }
                }
            };
            observers[i].startWatching();
        }
    }

    File await(long earliestModified, String kind, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            long before;
            synchronized (signal) { before = generation; }
            File photo = newestComplete(earliestModified, kind);
            if (photo != null) return photo;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) return null;
            synchronized (signal) {
                // An event between the directory read and the wait must not
                // be missed. Fallback also covers filesystems without inotify.
                if (before == generation) signal.wait(Math.min(remaining, 250L));
            }
        }
    }

    private File newestComplete(long earliestModified, String kind) {
        File newest = null;
        for (String directory : directories) {
            File[] files = new File(directory).listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.lastModified() < earliestModified || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")) continue;
                if (!kind.equals(WallpadBridgeService.visitorKindFromFile(file))
                        || !WallpadBridgeService.completeJpeg(file)) continue;
                if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
            }
        }
        return newest;
    }

    void close() { for (FileObserver observer : observers) if (observer != null) observer.stopWatching(); }
}
