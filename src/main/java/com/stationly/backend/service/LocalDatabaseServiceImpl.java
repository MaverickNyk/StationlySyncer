package com.stationly.backend.service;

import com.stationly.backend.model.LineStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class LocalDatabaseServiceImpl implements LocalDatabaseService {

    @Value("${sqlite.db-path}")
    private String dbPath;

    @Override
    @PostConstruct
    public void initialize() {
        log.info("SQLITE: 📡 Initializing SQLite local database at: {}", dbPath);
        // Ensure parent directories exist
        File file = new File(dbPath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            log.info("SQLITE: Created parent directory status: {}", created);
        }

        try (Connection conn = getConnection()) {
            if (conn != null) {
                log.info("SQLITE: 📁 Connected successfully to SQLite database.");
                createTables(conn);
            }
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to initialize database", e);
        }
    }

    private Connection getConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            log.error("SQLITE: JDBC Driver not found", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private void createTables(Connection conn) throws SQLException {
        String createMetadata = "CREATE TABLE IF NOT EXISTS sync_metadata (" +
                "key TEXT PRIMARY KEY, " +
                "value TEXT" +
                ")";

        String createLineStatuses = "CREATE TABLE IF NOT EXISTS line_statuses (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT, " +
                "mode TEXT, " +
                "statusSeverityDescription TEXT, " +
                "reason TEXT, " +
                "lastUpdatedTime TEXT" +
                ")";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createMetadata);
            stmt.execute(createLineStatuses);
            log.info("SQLITE: ✅ Database tables verified/created successfully.");
        }
    }

    @Override
    public String getLastSyncTime(String collection) {
        String sql = "SELECT value FROM sync_metadata WHERE key = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, "last_sync_" + collection);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to get last sync time for {}", collection, e);
        }
        return null;
    }

    @Override
    public void updateLastSyncTime(String collection, String time) {
        String sql = "INSERT OR REPLACE INTO sync_metadata (key, value) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, "last_sync_" + collection);
            pstmt.setString(2, time);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to update last sync time for {}", collection, e);
        }
    }

    @Override
    public List<LineStatusResponse> getAllLineStatuses() {
        List<LineStatusResponse> results = new ArrayList<>();
        String sql = "SELECT id, name, mode, statusSeverityDescription, reason, lastUpdatedTime FROM line_statuses";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                results.add(LineStatusResponse.builder()
                        .id(rs.getString("id"))
                        .name(rs.getString("name"))
                        .mode(rs.getString("mode"))
                        .statusSeverityDescription(rs.getString("statusSeverityDescription"))
                        .reason(rs.getString("reason"))
                        .lastUpdatedTime(rs.getString("lastUpdatedTime"))
                        .build());
            }
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to retrieve line statuses", e);
        }
        return results;
    }

    @Override
    public void upsertLineStatus(LineStatusResponse status) {
        String sql = "INSERT OR REPLACE INTO line_statuses (id, name, mode, statusSeverityDescription, reason, lastUpdatedTime) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status.getId());
            pstmt.setString(2, status.getName());
            pstmt.setString(3, status.getMode());
            pstmt.setString(4, status.getStatusSeverityDescription());
            pstmt.setString(5, status.getReason());
            pstmt.setString(6, status.getLastUpdatedTime());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to upsert line status for {}", status.getId(), e);
        }
    }

    @Override
    public void deleteLineStatus(String id) {
        String sql = "DELETE FROM line_statuses WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to delete line status for {}", id, e);
        }
    }
}
