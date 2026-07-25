package com.stationly.backend.nationalrail.repository;

import com.stationly.backend.nationalrail.dto.NationalRailBoardDeparture;
import com.stationly.backend.nationalrail.model.NationalRailCallingPointRow;
import com.stationly.backend.nationalrail.model.NationalRailScheduleRecord;
import com.stationly.backend.nationalrail.model.NationalRailServiceRow;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed mirror. Own file (isolated from the TfL cache) and a single
 * writer (this process) with a single reader (the board engine) — so plain
 * SQLite is the right tool; no shared-DB infrastructure needed.
 */
@Repository
@Slf4j
public class NationalRailScheduleRepositoryImpl implements NationalRailScheduleRepository {

    @Value("${nationalrail.sqlite.db-path}")
    private String dbPath;

    @Override
    @PostConstruct
    public void initialize() {
        log.info("NR_SQL: 🚉 Initializing National Rail mirror at: {}", dbPath);
        File parent = new File(dbPath).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Connection conn = getConnection()) {
            createTables(conn);
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ init failed", e);
        }
    }

    private Connection getConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            log.error("NR_SQL: JDBC driver not found", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private void createTables(Connection conn) throws SQLException {
        String service = "CREATE TABLE IF NOT EXISTS nr_service (" +
                "rid TEXT PRIMARY KEY, service_date TEXT NOT NULL, uid TEXT, toc TEXT, " +
                "destination_crs TEXT, destination_name TEXT, cancelled INTEGER NOT NULL DEFAULT 0)";
        String callingPoint = "CREATE TABLE IF NOT EXISTS nr_calling_point (" +
                "rid TEXT NOT NULL, tiploc TEXT NOT NULL, crs TEXT, service_date TEXT NOT NULL, " +
                "scheduled_dep_ms INTEGER, estimated_dep_ms INTEGER, actual_dep_ms INTEGER, platform TEXT, " +
                "is_public_departure INTEGER NOT NULL DEFAULT 1, cancelled INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (rid, tiploc))";
        try (Statement st = conn.createStatement()) {
            st.execute(service);
            st.execute(callingPoint);
            st.execute("CREATE INDEX IF NOT EXISTS idx_nr_cp_board ON nr_calling_point(crs, service_date, is_public_departure)");
        }
    }

    @Override
    public void replaceBaselineForDate(LocalDate serviceDate, List<NationalRailScheduleRecord> records) {
        String delSvc = "DELETE FROM nr_service WHERE service_date = ?";
        String delCp = "DELETE FROM nr_calling_point WHERE service_date = ?";
        String insSvc = "INSERT OR REPLACE INTO nr_service " +
                "(rid, service_date, uid, toc, destination_crs, destination_name, cancelled) VALUES (?,?,?,?,?,?,?)";
        String insCp = "INSERT OR REPLACE INTO nr_calling_point " +
                "(rid, tiploc, crs, service_date, scheduled_dep_ms, estimated_dep_ms, actual_dep_ms, platform, is_public_departure, cancelled) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)";
        String date = serviceDate.toString();
        long cp = 0;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement d1 = conn.prepareStatement(delSvc);
                 PreparedStatement d2 = conn.prepareStatement(delCp)) {
                d1.setString(1, date); d1.executeUpdate();
                d2.setString(1, date); d2.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(insSvc)) {
                for (NationalRailScheduleRecord r : records) bindService(ps, r.getService());
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(insCp)) {
                for (NationalRailScheduleRecord r : records) {
                    for (NationalRailCallingPointRow c : r.getCallingPoints()) { bindCallingPoint(ps, c); cp++; }
                }
                ps.executeBatch();
            }
            conn.commit();
            log.info("NR_SQL: 📅 Baseline for {} — {} service(s), {} calling point(s).", date, records.size(), cp);
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ baseline replace failed for {}", date, e);
        }
    }

    private void bindService(PreparedStatement ps, NationalRailServiceRow s) throws SQLException {
        ps.setString(1, s.getRid());
        ps.setString(2, s.getServiceDate().toString());
        ps.setString(3, s.getUid());
        ps.setString(4, s.getToc());
        ps.setString(5, s.getDestinationCrs());
        ps.setString(6, s.getDestinationName());
        ps.setInt(7, s.isCancelled() ? 1 : 0);
        ps.addBatch();
    }

    private void bindCallingPoint(PreparedStatement ps, NationalRailCallingPointRow c) throws SQLException {
        ps.setString(1, c.getRid());
        ps.setString(2, c.getTiploc());
        ps.setString(3, c.getCrs());
        ps.setString(4, c.getServiceDate().toString());
        setNullableLong(ps, 5, c.getScheduledDepartureMs());
        setNullableLong(ps, 6, c.getEstimatedDepartureMs());
        setNullableLong(ps, 7, c.getActualDepartureMs());
        ps.setString(8, c.getPlatform());
        ps.setInt(9, c.isPublicDeparture() ? 1 : 0);
        ps.setInt(10, c.isCancelled() ? 1 : 0);
        ps.addBatch();
    }

    @Override
    public boolean hasBaselineForDate(LocalDate serviceDate) {
        String sql = "SELECT 1 FROM nr_service WHERE service_date = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serviceDate.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ hasBaseline failed", e);
            return false;
        }
    }

    @Override
    public int applyTimingUpdate(String rid, String tiploc, Long estimatedDepartureMs, Long actualDepartureMs,
                                 String platform, Boolean cancelled) {
        String sql = "UPDATE nr_calling_point SET " +
                "estimated_dep_ms = COALESCE(?, estimated_dep_ms), " +
                "actual_dep_ms = COALESCE(?, actual_dep_ms), " +
                "platform = COALESCE(?, platform), " +
                "cancelled = COALESCE(?, cancelled) " +
                "WHERE rid = ? AND tiploc = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableLong(ps, 1, estimatedDepartureMs);
            setNullableLong(ps, 2, actualDepartureMs);
            if (platform != null) ps.setString(3, platform); else ps.setNull(3, Types.VARCHAR);
            if (cancelled != null) ps.setInt(4, cancelled ? 1 : 0); else ps.setNull(4, Types.INTEGER);
            ps.setString(5, rid);
            ps.setString(6, tiploc);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ timing update failed rid={} tpl={}", rid, tiploc, e);
            return 0;
        }
    }

    @Override
    public void applyServiceCancellation(String rid, boolean cancelled) {
        String sql = "UPDATE nr_service SET cancelled = ? WHERE rid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, cancelled ? 1 : 0);
            ps.setString(2, rid);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ service cancel failed rid={}", rid, e);
        }
    }

    @Override
    public void applyScheduleChange(NationalRailScheduleRecord record) {
        String insSvc = "INSERT OR REPLACE INTO nr_service " +
                "(rid, service_date, uid, toc, destination_crs, destination_name, cancelled) VALUES (?,?,?,?,?,?,?)";
        String delCp = "DELETE FROM nr_calling_point WHERE rid = ?";
        String insCp = "INSERT OR REPLACE INTO nr_calling_point " +
                "(rid, tiploc, crs, service_date, scheduled_dep_ms, estimated_dep_ms, actual_dep_ms, platform, is_public_departure, cancelled) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(insSvc)) { bindService(ps, record.getService()); ps.executeBatch(); }
            try (PreparedStatement ps = conn.prepareStatement(delCp)) { ps.setString(1, record.getService().getRid()); ps.executeUpdate(); }
            try (PreparedStatement ps = conn.prepareStatement(insCp)) {
                for (NationalRailCallingPointRow c : record.getCallingPoints()) bindCallingPoint(ps, c);
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ schedule change failed rid={}", record.getService().getRid(), e);
        }
    }

    @Override
    public List<NationalRailBoardDeparture> queryBoard(String crs, LocalDate serviceDate, long fromMs, int limit) {
        String sql = "SELECT COALESCE(cp.estimated_dep_ms, cp.scheduled_dep_ms) AS eff, cp.platform, " +
                "s.destination_crs, s.destination_name, s.toc " +
                "FROM nr_calling_point cp JOIN nr_service s ON cp.rid = s.rid " +
                "WHERE cp.crs = ? AND cp.service_date = ? AND cp.is_public_departure = 1 " +
                "AND cp.cancelled = 0 AND s.cancelled = 0 " +
                "AND COALESCE(cp.estimated_dep_ms, cp.scheduled_dep_ms) >= ? " +
                "ORDER BY eff ASC LIMIT ?";
        List<NationalRailBoardDeparture> out = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, crs.toUpperCase());
            ps.setString(2, serviceDate.toString());
            ps.setLong(3, fromMs);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(NationalRailBoardDeparture.builder()
                            .effectiveDepartureMs(rs.getLong("eff"))
                            .platform(rs.getString("platform"))
                            .destinationCrs(rs.getString("destination_crs"))
                            .destinationName(rs.getString("destination_name"))
                            .operatorCode(rs.getString("toc"))
                            .build());
                }
            }
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ board query failed crs={}", crs, e);
        }
        return out;
    }

    @Override
    public List<String> callingCrsForRid(String rid) {
        String sql = "SELECT DISTINCT crs FROM nr_calling_point WHERE rid = ? AND crs IS NOT NULL";
        List<String> out = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ callingCrsForRid failed rid={}", rid, e);
        }
        return out;
    }

    @Override
    public void purgeBefore(LocalDate serviceDate) {
        String d = serviceDate.toString();
        try (Connection conn = getConnection();
             PreparedStatement p1 = conn.prepareStatement("DELETE FROM nr_calling_point WHERE service_date < ?");
             PreparedStatement p2 = conn.prepareStatement("DELETE FROM nr_service WHERE service_date < ?")) {
            p1.setString(1, d); int a = p1.executeUpdate();
            p2.setString(1, d); int b = p2.executeUpdate();
            log.info("NR_SQL: 🧹 Purged {} calling point(s), {} service(s) before {}", a, b, d);
        } catch (SQLException e) {
            log.error("NR_SQL: ❌ purge failed", e);
        }
    }

    private void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v != null) ps.setLong(idx, v); else ps.setNull(idx, Types.INTEGER);
    }
}
