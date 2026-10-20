package com.homepad.wallpadbridge;

/** Pure local policy: never replay a ring or open a unit/unknown entrance. */
final class CentralAutoUnlockPolicy {
    static boolean eligible(boolean enabled, int place, String source, String[] allowed,
                            long ageMs, boolean callViewActive, boolean hasPhoto) {
        if (!enabled || place != 5 || ageMs < 0 || ageMs > 5000L
                || !callViewActive || !hasPhoto) return false;
        for (String value : allowed) if (value.equals(source)) return true;
        return false;
    }
}
