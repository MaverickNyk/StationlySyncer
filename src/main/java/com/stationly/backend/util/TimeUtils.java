package com.stationly.backend.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Canonical epoch-millis helpers for the replication watermark.
 *
 * Internally the watermark is epoch milliseconds (a long). {@link #toEpochMs}
 * coerces whatever a Firestore document or SQLite row hands us — a Number
 * (post-migration), an epoch-string, an ISO-8601 string, or even the legacy
 * local "no-Z" format — so reads stay tolerant across the string→integer
 * cutover and never throw.
 */
public final class TimeUtils {
    private TimeUtils() {}

    /** Current time as epoch milliseconds — the canonical watermark unit. */
    public static long nowMs() {
        return Instant.now().toEpochMilli();
    }

    /** Coerce any stored/incoming watermark value to epoch millis. 0 if unknown. */
    public static long toEpochMs(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) return 0L;
            try { return Long.parseLong(s); } catch (NumberFormatException ignore) {}
            try { return Instant.parse(s).toEpochMilli(); } catch (Exception ignore) {}
            // Legacy local format with no zone (e.g. "2026-06-01T02:06:13.0326").
            try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignore) {}
        }
        return 0L;
    }
}
