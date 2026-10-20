package com.homepad.wallpadbridge;

/** A daily interval; the end is exclusive, including intervals crossing midnight. */
final class QuietHoursWindow {
    static int minute(String time) {
        if (time == null || !time.matches("[0-2][0-9]:[0-5][0-9]"))
            throw new IllegalArgumentException("Invalid quiet time");
        int value = Integer.parseInt(time.substring(0, 2)) * 60
                + Integer.parseInt(time.substring(3));
        if (value >= 1440) throw new IllegalArgumentException("Invalid quiet time");
        return value;
    }

    static boolean active(int now, int start, int end) {
        return start < end ? now >= start && now < end : now >= start || now < end;
    }
}
