package com.homepad.companion;

/** A held side key may launch an activity again; never queue repeated calls. */
final class ElevatorShortcutGate {
    static final long COOLDOWN_MS = 10_000L;
    private long lastStarted;
    private boolean busy;

    ElevatorShortcutGate(long lastStarted) {
        this.lastStarted = lastStarted;
    }

    synchronized boolean tryStart(long uptime) {
        if (busy) return false;
        // An elapsed-realtime value from before a reboot must not block forever.
        if (lastStarted >= 0 && uptime >= lastStarted
                && uptime - lastStarted < COOLDOWN_MS) return false;
        busy = true;
        lastStarted = uptime;
        return true;
    }

    synchronized void finish() { busy = false; }
}
