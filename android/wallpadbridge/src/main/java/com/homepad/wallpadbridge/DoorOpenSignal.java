package com.homepad.wallpadbridge;

/** Wait for fresh native door-open or monitor-ready signals without fixed delays. */
final class DoorOpenSignal {
    private long central, unit, videoReady;

    synchronized void mark(int type, long observedAt) {
        if (type == 31) central = observedAt;
        else if (type == 33) unit = observedAt;
        else if (type == 15) videoReady = observedAt;
        else return;
        notifyAll();
    }

    synchronized long latest(String kind) {
        if ("unit_monitor_video".equals(kind)) return videoReady;
        return "unit_entrance".equals(kind) ? unit : central;
    }

    synchronized boolean await(String kind, long after, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        while (latest(kind) <= after) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) return false;
            wait(remaining / 1000000L, (int) (remaining % 1000000L));
        }
        return true;
    }
}
