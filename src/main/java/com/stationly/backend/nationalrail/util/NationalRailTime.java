package com.stationly.backend.nationalrail.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * All National Rail service dates/times are reckoned in UK local time — a
 * service date is the railway operating day, not a UTC calendar day.
 * Centralised so schedule ingest, delta application, the board engine and
 * retention all agree.
 */
public final class NationalRailTime {

    public static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private NationalRailTime() {}

    public static LocalDate today() {
        return LocalDate.now(LONDON);
    }

    /** "HH:mm" on a given service date (Europe/London) → epoch millis, or null if unparseable. */
    public static Long toEpochMs(String hhmm, LocalDate serviceDate) {
        if (hhmm == null || hhmm.isBlank()) return null;
        try {
            LocalTime time = LocalTime.parse(hhmm.trim());
            return serviceDate.atTime(time).atZone(LONDON).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /** Epoch millis → ISO-8601 offset string (what the app payload's `eta` expects). */
    public static String toIso(long epochMs) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), LONDON)
                .toOffsetDateTime().format(ISO);
    }

    public static long nowMs() {
        return System.currentTimeMillis();
    }
}
