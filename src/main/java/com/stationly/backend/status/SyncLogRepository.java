package com.stationly.backend.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite persistence for sync-run telemetry. Lives in the SAME db file as
 * {@code LocalDatabaseServiceImpl} ({@code sqlite.db-path}) but owns its own
 * tables and connections.
 *
 * <p>Two tables:
 * <ul>
 *   <li>{@code sync_run} — one raw row per sync cycle (high resolution, short-lived).</li>
 *   <li>{@code sync_run_rollup} — hour/day aggregates (long-lived) produced by the
 *       retention sweep so the raw table never grows unbounded.</li>
 * </ul>
 *
 * <p>Connections enable WAL + a busy-timeout so the new high-frequency writer and
 * the read-side REST queries never deadlock against the existing SQLite writers
 * (line-status listener, station sync).
 */
@Repository
@Slf4j
public class SyncLogRepository {

    @Value("${sqlite.db-path}")
    private String dbPath;

    private final ObjectMapper objectMapper;

    public SyncLogRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Shared by both rollup tiers (raw→hour, hour→day) so the column list and the
    // additive upsert clause can't drift between the two INSERTs.
    private static final String ROLLUP_COLS =
            "bucket, bucket_start, job_type, runs, ok_runs, partial_runs, failed_runs, nap_runs, " +
            "sum_duration_ms, max_duration_ms, sum_arrivals, sum_fcm, sum_changed, sum_errors";

    private static final String ROLLUP_UPSERT =
            " ON CONFLICT(bucket, bucket_start, job_type) DO UPDATE SET " +
            "runs = runs + excluded.runs, ok_runs = ok_runs + excluded.ok_runs, " +
            "partial_runs = partial_runs + excluded.partial_runs, failed_runs = failed_runs + excluded.failed_runs, " +
            "nap_runs = nap_runs + excluded.nap_runs, sum_duration_ms = sum_duration_ms + excluded.sum_duration_ms, " +
            "max_duration_ms = MAX(max_duration_ms, excluded.max_duration_ms), " +
            "sum_arrivals = sum_arrivals + excluded.sum_arrivals, sum_fcm = sum_fcm + excluded.sum_fcm, " +
            "sum_changed = sum_changed + excluded.sum_changed, sum_errors = sum_errors + excluded.sum_errors";

    @PostConstruct
    public void initialize() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS sync_run (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "job_type TEXT NOT NULL, " +
                    "started_at INTEGER NOT NULL, " +
                    "finished_at INTEGER NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "cycle INTEGER, " +
                    "nap_mode INTEGER, " +
                    "active_subscriptions INTEGER, " +
                    "modes_processed INTEGER, " +
                    "arrivals INTEGER, " +
                    "station_groups INTEGER, " +
                    "fcm_queued INTEGER, " +
                    "total_lines INTEGER, " +
                    "changed INTEGER, " +
                    "errors INTEGER, " +
                    "error_msg TEXT, " +
                    "detail_json TEXT" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sync_run_job_time ON sync_run(job_type, started_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sync_run_finished ON sync_run(finished_at)");

            st.execute("CREATE TABLE IF NOT EXISTS sync_run_rollup (" +
                    "bucket TEXT NOT NULL, " +          // 'hour' | 'day'
                    "bucket_start INTEGER NOT NULL, " + // epoch ms, truncated to bucket
                    "job_type TEXT NOT NULL, " +
                    "runs INTEGER NOT NULL, " +
                    "ok_runs INTEGER NOT NULL, " +
                    "partial_runs INTEGER NOT NULL, " +
                    "failed_runs INTEGER NOT NULL, " +
                    "nap_runs INTEGER NOT NULL, " +
                    "sum_duration_ms INTEGER NOT NULL, " +
                    "max_duration_ms INTEGER NOT NULL, " +
                    "sum_arrivals INTEGER NOT NULL, " +
                    "sum_fcm INTEGER NOT NULL, " +
                    "sum_changed INTEGER NOT NULL, " +
                    "sum_errors INTEGER NOT NULL, " +
                    "PRIMARY KEY (bucket, bucket_start, job_type)" +
                    ")");
            log.info("SYNC-LOG: ✅ sync_run + sync_run_rollup ready (WAL) at {}", dbPath);
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ Failed to initialize sync-log tables", e);
        }
    }

    private Connection open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            log.error("SYNC-LOG: JDBC driver not found", e);
        }
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setBusyTimeout(5000);                          // wait (don't fail) on a brief lock
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);  // readers don't block the writer
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());
    }

    // ---------------------------------------------------------------- writes

    public void insertBatch(List<SyncRunRecord> records) {
        if (records == null || records.isEmpty()) return;
        String sql = "INSERT INTO sync_run (job_type, started_at, finished_at, duration_ms, status, cycle, " +
                "nap_mode, active_subscriptions, modes_processed, arrivals, station_groups, fcm_queued, " +
                "total_lines, changed, errors, error_msg, detail_json) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (SyncRunRecord r : records) {
                    ps.setString(1, r.getJobType());
                    ps.setLong(2, r.getStartedAt());
                    ps.setLong(3, r.getFinishedAt());
                    ps.setLong(4, r.getDurationMs());
                    ps.setString(5, r.getStatus());
                    setNullableInt(ps, 6, r.getCycle());
                    ps.setInt(7, r.isNapMode() ? 1 : 0);
                    setNullableInt(ps, 8, r.getActiveSubscriptions());
                    setNullableInt(ps, 9, r.getModesProcessed());
                    setNullableInt(ps, 10, r.getArrivals());
                    setNullableInt(ps, 11, r.getStationGroups());
                    setNullableInt(ps, 12, r.getFcmQueued());
                    setNullableInt(ps, 13, r.getTotalLines());
                    setNullableInt(ps, 14, r.getChanged());
                    setNullableInt(ps, 15, r.getErrors());
                    ps.setString(16, r.getErrorMsg());
                    ps.setString(17, toJson(r.getDetail()));
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ insertBatch of {} failed", records.size(), e);
        }
    }

    // ---------------------------------------------------------------- reads

    public Map<String, Object> latestRun(String jobType) {
        String sql = "SELECT * FROM sync_run WHERE job_type = ? ORDER BY started_at DESC LIMIT 1";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
            }
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ latestRun({}) failed", jobType, e);
        }
        return null;
    }

    public List<Map<String, Object>> recentRuns(String jobType, long beforeMs, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sync_run WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (jobType != null && !jobType.isBlank()) {
            sql.append(" AND job_type = ?");
            params.add(jobType);
        }
        if (beforeMs > 0) {
            sql.append(" AND started_at < ?");
            params.add(beforeMs);
        }
        sql.append(" ORDER BY started_at DESC LIMIT ?");
        params.add(limit);

        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ recentRuns failed", e);
        }
        return out;
    }

    /** Aggregate the raw rows for one job type since {@code sinceMs} (live window). */
    public Map<String, Object> aggregateSince(String jobType, long sinceMs) {
        String sql = "SELECT COUNT(*) runs, " +
                "COALESCE(SUM(status='ok'),0) ok, COALESCE(SUM(status='partial'),0) partial, " +
                "COALESCE(SUM(status='failed'),0) failed, COALESCE(SUM(status='nap'),0) nap, " +
                "COALESCE(AVG(duration_ms),0) avgDurationMs, COALESCE(MAX(duration_ms),0) maxDurationMs, " +
                "COALESCE(SUM(arrivals),0) arrivals, COALESCE(SUM(fcm_queued),0) fcm, " +
                "COALESCE(SUM(changed),0) changed, COALESCE(SUM(errors),0) errors " +
                "FROM sync_run WHERE job_type = ? AND started_at >= ?";
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobType);
            ps.setLong(2, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("runs", rs.getLong("runs"));
                    m.put("ok", rs.getLong("ok"));
                    m.put("partial", rs.getLong("partial"));
                    m.put("failed", rs.getLong("failed"));
                    m.put("nap", rs.getLong("nap"));
                    m.put("avgDurationMs", Math.round(rs.getDouble("avgDurationMs")));
                    m.put("maxDurationMs", rs.getLong("maxDurationMs"));
                    m.put("arrivals", rs.getLong("arrivals"));
                    m.put("fcm", rs.getLong("fcm"));
                    m.put("changed", rs.getLong("changed"));
                    m.put("errors", rs.getLong("errors"));
                    return m;
                }
            }
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ aggregateSince failed", e);
        }
        return Collections.emptyMap();
    }

    public List<Map<String, Object>> rollups(String bucket, String jobType, long sinceMs, long untilMs) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sync_run_rollup WHERE bucket = ?");
        List<Object> params = new ArrayList<>();
        params.add(bucket);
        if (jobType != null && !jobType.isBlank()) {
            sql.append(" AND job_type = ?");
            params.add(jobType);
        }
        if (sinceMs > 0) {
            sql.append(" AND bucket_start >= ?");
            params.add(sinceMs);
        }
        if (untilMs > 0) {
            sql.append(" AND bucket_start < ?");
            params.add(untilMs);
        }
        sql.append(" ORDER BY bucket_start ASC");

        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long runs = rs.getLong("runs");
                    m.put("bucket", rs.getString("bucket"));
                    m.put("bucketStart", rs.getLong("bucket_start"));
                    m.put("jobType", rs.getString("job_type"));
                    m.put("runs", runs);
                    m.put("okRuns", rs.getLong("ok_runs"));
                    m.put("partialRuns", rs.getLong("partial_runs"));
                    m.put("failedRuns", rs.getLong("failed_runs"));
                    m.put("napRuns", rs.getLong("nap_runs"));
                    m.put("avgDurationMs", runs > 0 ? rs.getLong("sum_duration_ms") / runs : 0);
                    m.put("maxDurationMs", rs.getLong("max_duration_ms"));
                    m.put("arrivals", rs.getLong("sum_arrivals"));
                    m.put("fcm", rs.getLong("sum_fcm"));
                    m.put("changed", rs.getLong("sum_changed"));
                    m.put("errors", rs.getLong("sum_errors"));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ rollups failed", e);
        }
        return out;
    }

    /** Row count of an internal table ({@code sync_run} | {@code sync_run_rollup}). */
    public long count(String table) {
        String sql = "SELECT COUNT(*) FROM " + table; // table is a fixed internal constant, never user input
        try (Connection conn = open(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ count({}) failed", table, e);
        }
        return 0;
    }

    // ------------------------------------------------------ retention / rollup

    /**
     * Three-tier compaction, all in one transaction:
     * <ol>
     *   <li>raw rows older than {@code rawRetentionMs} → hourly buckets, then deleted;</li>
     *   <li>hourly buckets older than {@code hourlyRetentionMs} → daily buckets, then deleted;</li>
     *   <li>daily buckets older than {@code dailyRetentionMs} → deleted.</li>
     * </ol>
     * Each raw row is rolled up exactly once (rolled + deleted together), and the
     * additive {@code ON CONFLICT} upsert correctly accumulates a partially-aged bucket.
     */
    public void rollupAndPrune(long now, long rawRetentionMs, long hourlyRetentionMs, long dailyRetentionMs) {
        long rawCutoff = now - rawRetentionMs;
        long hourCutoff = now - hourlyRetentionMs;
        long dayCutoff = now - dailyRetentionMs;

        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // (A) raw -> hourly
                st.executeUpdate(
                        "INSERT INTO sync_run_rollup (" + ROLLUP_COLS + ") " +
                        "SELECT 'hour', (started_at/3600000)*3600000, job_type, COUNT(*), " +
                        "COALESCE(SUM(status='ok'),0), COALESCE(SUM(status='partial'),0), COALESCE(SUM(status='failed'),0), " +
                        "COALESCE(SUM(status='nap'),0), COALESCE(SUM(duration_ms),0), COALESCE(MAX(duration_ms),0), " +
                        "COALESCE(SUM(arrivals),0), COALESCE(SUM(fcm_queued),0), COALESCE(SUM(changed),0), COALESCE(SUM(errors),0) " +
                        "FROM sync_run WHERE finished_at < " + rawCutoff + " GROUP BY (started_at/3600000)*3600000, job_type" +
                        ROLLUP_UPSERT);
                int rawDeleted = st.executeUpdate("DELETE FROM sync_run WHERE finished_at < " + rawCutoff);

                // (B) hourly -> daily
                st.executeUpdate(
                        "INSERT INTO sync_run_rollup (" + ROLLUP_COLS + ") " +
                        "SELECT 'day', (bucket_start/86400000)*86400000, job_type, SUM(runs), SUM(ok_runs), SUM(partial_runs), " +
                        "SUM(failed_runs), SUM(nap_runs), SUM(sum_duration_ms), MAX(max_duration_ms), " +
                        "SUM(sum_arrivals), SUM(sum_fcm), SUM(sum_changed), SUM(sum_errors) " +
                        "FROM sync_run_rollup WHERE bucket='hour' AND bucket_start < " + hourCutoff +
                        " GROUP BY (bucket_start/86400000)*86400000, job_type" +
                        ROLLUP_UPSERT);
                int hourDeleted = st.executeUpdate(
                        "DELETE FROM sync_run_rollup WHERE bucket='hour' AND bucket_start < " + hourCutoff);

                // (C) expire old daily buckets
                int dayDeleted = st.executeUpdate(
                        "DELETE FROM sync_run_rollup WHERE bucket='day' AND bucket_start < " + dayCutoff);

                conn.commit();
                if (rawDeleted + hourDeleted + dayDeleted > 0) {
                    log.info("SYNC-LOG: 🧹 retention — rolled+pruned {} raw rows, {} hourly→daily, {} daily expired",
                            rawDeleted, hourDeleted, dayDeleted);
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("SYNC-LOG: ❌ rollupAndPrune failed", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setNullableInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, v);
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("jobType", rs.getString("job_type"));
        m.put("startedAt", rs.getLong("started_at"));
        m.put("finishedAt", rs.getLong("finished_at"));
        m.put("durationMs", rs.getLong("duration_ms"));
        m.put("status", rs.getString("status"));
        putNullableLong(m, "cycle", rs, "cycle");
        m.put("napMode", rs.getInt("nap_mode") == 1);
        putNullableLong(m, "activeSubscriptions", rs, "active_subscriptions");
        putNullableLong(m, "modesProcessed", rs, "modes_processed");
        putNullableLong(m, "arrivals", rs, "arrivals");
        putNullableLong(m, "stationGroups", rs, "station_groups");
        putNullableLong(m, "fcmQueued", rs, "fcm_queued");
        putNullableLong(m, "totalLines", rs, "total_lines");
        putNullableLong(m, "changed", rs, "changed");
        putNullableLong(m, "errors", rs, "errors");
        m.put("errorMsg", rs.getString("error_msg"));
        String detailJson = rs.getString("detail_json");
        if (detailJson != null && !detailJson.isEmpty()) {
            try {
                m.put("detail", objectMapper.readValue(detailJson, Map.class));
            } catch (Exception ignored) {
                m.put("detail", detailJson);
            }
        }
        return m;
    }

    private void putNullableLong(Map<String, Object> m, String key, ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        if (!rs.wasNull()) m.put(key, v);
    }
}
