package com.homepad.wallpadbridge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read only the newest vendor offering, without borrowing a previous caller. */
final class SipOfferingParser {
    private static final Pattern PLACE = Pattern.compile("Offering ==> Place : (\\d+), Status : (\\d+)");

    static Context parse(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).contains("SIP_CALL_LEG_STATE_OFFERING")) continue;
            String number = "", caller = "", display = "", remoteIp = "";
            for (int j = i - 1; j >= 0 && j >= i - 40; j--) {
                String line = lines.get(j);
                if (line.contains("SIP_CALL_LEG_STATE_")) break;
                if (number.length() == 0 && line.contains("Call Number :")) {
                    number = value(line, "Call Number :");
                    int comma = number.indexOf(',');
                    if (comma >= 0) number = number.substring(0, comma).trim();
                }
                if (caller.length() == 0 && line.contains("CallerID :")) caller = value(line, "CallerID :");
                if (display.length() == 0 && line.contains("DisplayName :")) display = value(line, "DisplayName :");
                if (remoteIp.length() == 0 && line.contains("ip :")) remoteIp = value(line, "ip :");
            }
            // This wallpad logs central entrance identity in the quoted
            // DisplayName. CallerID is a numeric SIP account, not "lobby0042".
            String source = number;
            if (display.matches("lobby[0-9]{4}")) source = display;
            else if (source.length() == 0) source = caller;
            int place = 0;
            String lowerDisplay = display.toLowerCase(Locale.ROOT);
            if (source.startsWith("2")) place = 2;
            else if (lowerDisplay.contains("lobby") || lowerDisplay.contains("kiosk")) place = 5;
            if (place == 0) {
                for (int j = i + 1; j < lines.size() && j <= i + 80; j++) {
                    if (lines.get(j).contains("SIP_CALL_LEG_STATE_")) break;
                    Matcher matcher = PLACE.matcher(lines.get(j));
                    if (matcher.find()) {
                        place = Integer.parseInt(matcher.group(1));
                        break;
                    }
                }
            }
            if (place == 0) throw new IllegalStateException("Unable to classify current SIP offering");
            return new Context(place, source, remoteIp, epoch(lines.get(i)));
        }
        throw new IllegalStateException("SIP offering context not present in log");
    }

    private static String value(String line, String marker) {
        String result = line.substring(line.indexOf(marker) + marker.length()).trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\""))
            result = result.substring(1, result.length() - 1).trim();
        return result;
    }

    private static long epoch(String line) {
        try {
            String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .parse(year + "-" + line.substring(0, 18)).getTime();
        } catch (Exception ignored) { return 0L; }
    }

    static final class Context {
        final int place;
        final String sourceId, remoteIp;
        final long offeredEpoch;
        Context(int place, String source, String remoteIp, long epoch) {
            this.place = place;
            this.sourceId = source;
            this.remoteIp = remoteIp;
            this.offeredEpoch = epoch;
        }
    }
}
