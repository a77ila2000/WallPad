package com.homepad.companion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Parking timestamps are instants when zoned, and Korea local time otherwise. */
final class ParkingHistory {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("MM.dd HH:mm");

    private ParkingHistory() {}

    private static Instant instant(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String text = value.trim().replace(' ', 'T');
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (Exception ignored) { }
        try { return LocalDateTime.parse(text).atZone(SEOUL).toInstant(); }
        catch (Exception ignored) { }
        if (text.matches("\\d{12}|\\d{14}")) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(
                        text.length() == 14 ? "yyyyMMddHHmmss" : "yyyyMMddHHmm"))
                        .atZone(SEOUL).toInstant();
            } catch (Exception ignored) { }
        }
        return null;
    }

    static String formatTimestamp(String value) {
        Instant parsed = instant(value);
        return parsed == null ? (value == null ? "" : value) : LABEL.format(parsed.atZone(SEOUL));
    }

    static String owner(String server, String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (server.replaceAll("/+$", "") + "\n" + token.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte b : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static long epoch(JSONObject row) {
        Instant parsed = instant(row.optString("entryAt", row.optString("occurredAt", "")));
        if (parsed == null) parsed = instant(row.optString("exitAt", ""));
        return parsed == null ? 0 : parsed.getEpochSecond();
    }

    private static boolean sameVisit(JSONObject a, JSONObject b) {
        String id = a.optString("id", "");
        if (!id.isEmpty() && id.equals(b.optString("id", ""))) return true;
        String plate = a.optString("carNumber", "").replaceAll("[\\s-]+", "");
        return !plate.isEmpty() && plate.equals(b.optString("carNumber", "").replaceAll("[\\s-]+", ""))
                && epoch(a) != 0 && epoch(a) == epoch(b);
    }

    private static JSONArray mergeVisits(JSONArray previous, JSONArray fresh) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        for (JSONArray batch : new JSONArray[]{fresh, previous}) {
            if (batch == null) continue;
            for (int i = 0; i < batch.length(); i++) {
                JSONObject row = batch.optJSONObject(i);
                if (row == null) continue;
                JSONObject match = null;
                for (JSONObject added : rows) if (sameVisit(added, row)) { match = added; break; }
                if (match == null) rows.add(new JSONObject(row.toString()));
                else if (match.optString("exitAt", "").isEmpty() && !row.optString("exitAt", "").isEmpty()) {
                    // A delayed or partial response must not erase a confirmed exit.
                    match.put("exitAt", row.getString("exitAt"));
                    match.put("status", row.optString("status", "출차"));
                }
            }
        }
        rows.sort((a, b) -> Long.compare(epoch(b), epoch(a)));
        JSONArray merged = new JSONArray();
        for (int i = 0; i < Math.min(rows.size(), 100); i++) merged.put(rows.get(i));
        return merged;
    }

    static JSONObject merge(JSONObject previous, JSONObject fresh) {
        if (previous == null) return fresh;
        try {
            JSONObject result = new JSONObject(fresh.toString());
            for (String section : new String[]{"visitorActivity", "householdActivity"})
                result.put(section, mergeVisits(previous.optJSONArray(section), fresh.optJSONArray(section)));
            JSONObject sources = fresh.optJSONObject("sourceStatus");
            JSONObject apti = sources == null ? null : sources.optJSONObject("apartmenti");
            // Reservations are an authoritative snapshot: don't resurrect cancellations or arrivals.
            if (apti != null && !apti.optBoolean("available", false) && !apti.optBoolean("stale", false)) {
                if (previous.has("visitorReservations")) result.put("visitorReservations", previous.get("visitorReservations"));
                if (previous.has("parkingPolicy")) result.put("parkingPolicy", previous.get("parkingPolicy"));
            }
            return result;
        } catch (Exception error) { throw new IllegalArgumentException("Invalid parking history", error); }
    }
}
