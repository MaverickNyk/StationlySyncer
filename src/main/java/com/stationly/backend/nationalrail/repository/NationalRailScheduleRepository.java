package com.stationly.backend.nationalrail.repository;

import com.stationly.backend.nationalrail.dto.NationalRailBoardDeparture;
import com.stationly.backend.nationalrail.model.NationalRailScheduleRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * The SQL mirror of Darwin: today's timetable baseline, kept corrected by live
 * Push Port deltas. Own SQLite file, isolated from the TfL-flavored cache. This
 * is the single source every National Rail board is computed from — no external
 * call on the read/heartbeat path.
 */
public interface NationalRailScheduleRepository {

    void initialize();

    // ── Baseline (daily) ────────────────────────────────────────────────
    void replaceBaselineForDate(LocalDate serviceDate, List<NationalRailScheduleRecord> records);
    boolean hasBaselineForDate(LocalDate serviceDate);

    // ── Live deltas (Push Port) ─────────────────────────────────────────
    /** Apply a TS timing/platform/cancel update to one calling point. Returns rows affected (0 = unknown). */
    int applyTimingUpdate(String rid, String tiploc,
                          Long estimatedDepartureMs, Long actualDepartureMs,
                          String platform, Boolean cancelled);
    /** Flip a whole service cancelled/reinstated (SC message). */
    void applyServiceCancellation(String rid, boolean cancelled);
    /** Upsert a service + replace its calling points (SC re-plan / new service). */
    void applyScheduleChange(NationalRailScheduleRecord record);

    // ── Board query (read path) ─────────────────────────────────────────
    /** Next {@code limit} public departures at {@code crs} on {@code serviceDate} with effective time ≥ fromMs. */
    List<NationalRailBoardDeparture> queryBoard(String crs, LocalDate serviceDate, long fromMs, int limit);

    /** Distinct CRS codes a service calls at — the stations a whole-service change (e.g. cancel) impacts. */
    List<String> callingCrsForRid(String rid);

    // ── Retention ───────────────────────────────────────────────────────
    void purgeBefore(LocalDate serviceDate);
}
