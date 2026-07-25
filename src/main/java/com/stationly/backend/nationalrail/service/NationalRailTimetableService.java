package com.stationly.backend.nationalrail.service;

import java.time.LocalDate;

/**
 * Owns the daily baseline: ingest Darwin's full-day timetable file into the SQL
 * mirror so every service the Push Port later corrects is already there. Run at
 * ~03:00 and on startup; a mid-day (re)deploy always finds today's timetable.
 */
public interface NationalRailTimetableService {

    /** Idempotently ensure today's baseline is loaded (skips if already present), then purge stale days. */
    void loadBaselineForToday();

    /** Force a (re)load for a specific service date. */
    void reloadBaseline(LocalDate serviceDate);
}
