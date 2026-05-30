package com.stationly.backend.service;

import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.model.Station;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class LocalDatabaseServiceImpl implements LocalDatabaseService {

    @Value("${sqlite.db-path}")
    private String dbPath;

    @Autowired
    private ObjectMapper objectMapper;

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

        String createStations = "CREATE TABLE IF NOT EXISTS stations (" +
                "naptanId TEXT PRIMARY KEY, " +
                "commonName TEXT, " +
                "lat REAL, " +
                "lon REAL, " +
                "geoHash TEXT, " +
                "stopType TEXT, " +
                "indicator TEXT, " +
                "stopLetter TEXT, " +
                "lastUpdatedTime TEXT, " +
                "icsCode TEXT, " +
                "towards TEXT, " +
                "compassPoint TEXT, " +
                "stationNaptan TEXT, " +
                "modes_json TEXT, " +
                "search_keys_json TEXT" +
                ")";

        String indexStopType = "CREATE INDEX IF NOT EXISTS idx_stations_stoptype ON stations(stopType)";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createMetadata);
            stmt.execute(createLineStatuses);
            stmt.execute(createStations);
            stmt.execute(indexStopType);
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

    // 🆕 Reactive Station Methods implementation

    @Override
    public int getStationCount() {
        String sql = "SELECT COUNT(*) FROM stations";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to get station count", e);
        }
        return 0;
    }

    @Override
    public void upsertStation(Station station) {
        String sql = "INSERT OR REPLACE INTO stations (" +
                "naptanId, commonName, lat, lon, geoHash, stopType, indicator, stopLetter, " +
                "lastUpdatedTime, icsCode, towards, compassPoint, stationNaptan, modes_json, search_keys_json" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            String modesJson = "{}";
            String searchKeysJson = "[]";
            try {
                if (station.getModes() != null) {
                    modesJson = objectMapper.writeValueAsString(station.getModes());
                }
                if (station.getSearchKeys() != null) {
                    searchKeysJson = objectMapper.writeValueAsString(station.getSearchKeys());
                }
            } catch (Exception e) {
                log.error("SQLITE: ❌ Failed to serialize JSON for station {}", station.getNaptanId(), e);
            }

            pstmt.setString(1, station.getNaptanId());
            pstmt.setString(2, station.getCommonName());
            pstmt.setDouble(3, station.getLat());
            pstmt.setDouble(4, station.getLon());
            pstmt.setString(5, station.getGeoHash());
            pstmt.setString(6, station.getStopType());
            pstmt.setString(7, station.getIndicator());
            pstmt.setString(8, station.getStopLetter());
            pstmt.setString(9, station.getLastUpdatedTime());
            pstmt.setString(10, station.getIcsCode());
            pstmt.setString(11, station.getTowards());
            pstmt.setString(12, station.getCompassPoint());
            pstmt.setString(13, station.getStationNaptan());
            pstmt.setString(14, modesJson);
            pstmt.setString(15, searchKeysJson);

            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to upsert station for {}", station.getNaptanId(), e);
        }
    }

    @Override
    public void saveAllStations(List<Station> stations) {
        if (stations == null || stations.isEmpty()) {
            return;
        }
        String sql = "INSERT OR REPLACE INTO stations (" +
                "naptanId, commonName, lat, lon, geoHash, stopType, indicator, stopLetter, " +
                "lastUpdatedTime, icsCode, towards, compassPoint, stationNaptan, modes_json, search_keys_json" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Station station : stations) {
                    String modesJson = "{}";
                    String searchKeysJson = "[]";
                    try {
                        if (station.getModes() != null) {
                            modesJson = objectMapper.writeValueAsString(station.getModes());
                        }
                        if (station.getSearchKeys() != null) {
                            searchKeysJson = objectMapper.writeValueAsString(station.getSearchKeys());
                        }
                    } catch (Exception e) {
                        log.error("SQLITE: ❌ Failed to serialize JSON for station {}", station.getNaptanId(), e);
                    }

                    pstmt.setString(1, station.getNaptanId());
                    pstmt.setString(2, station.getCommonName());
                    pstmt.setDouble(3, station.getLat());
                    pstmt.setDouble(4, station.getLon());
                    pstmt.setString(5, station.getGeoHash());
                    pstmt.setString(6, station.getStopType());
                    pstmt.setString(7, station.getIndicator());
                    pstmt.setString(8, station.getStopLetter());
                    pstmt.setString(9, station.getLastUpdatedTime());
                    pstmt.setString(10, station.getIcsCode());
                    pstmt.setString(11, station.getTowards());
                    pstmt.setString(12, station.getCompassPoint());
                    pstmt.setString(13, station.getStationNaptan());
                    pstmt.setString(14, modesJson);
                    pstmt.setString(15, searchKeysJson);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
                log.info("SQLITE: ✅ Successfully saved {} stations in a batch transaction.", stations.size());
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            log.error("SQLITE: ❌ Failed to saveAllStations", e);
        }
    }

    @Override
    public void deleteStation(String naptanId) {
        String sql = "DELETE FROM stations WHERE naptanId = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, naptanId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to delete station {}", naptanId, e);
        }
    }

    @Override
    public List<Station> getStationsBySearchKey(String searchKey) {
        List<Station> results = new ArrayList<>();
        String sql = "SELECT * FROM stations WHERE EXISTS (SELECT 1 FROM json_each(search_keys_json) WHERE value = ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, searchKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToStation(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to get stations by search key {}", searchKey, e);
        }
        return results;
    }

    @Override
    public List<Station> getStationsExceptStopType(String stopTypeToExclude) {
        List<Station> results = new ArrayList<>();
        String sql = "SELECT * FROM stations WHERE stopType IS NULL OR stopType != ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, stopTypeToExclude);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToStation(rs));
                }
            }
        } catch (SQLException e) {
            log.error("SQLITE: ❌ Failed to get stations except stopType {}", stopTypeToExclude, e);
        }
        return results;
    }

    private Station mapResultSetToStation(ResultSet rs) throws SQLException {
        String modesJson = rs.getString("modes_json");
        String searchKeysJson = rs.getString("search_keys_json");
        
        Map<String, Station.ModeGroup> modes = new HashMap<>();
        List<String> searchKeys = new ArrayList<>();
        
        try {
            if (modesJson != null && !modesJson.isEmpty()) {
                modes = objectMapper.readValue(modesJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Station.ModeGroup>>() {});
            }
            if (searchKeysJson != null && !searchKeysJson.isEmpty()) {
                searchKeys = objectMapper.readValue(searchKeysJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            log.error("SQLITE: ❌ Failed to deserialize JSON for station {}", rs.getString("naptanId"), e);
        }

        return Station.builder()
                .naptanId(rs.getString("naptanId"))
                .commonName(rs.getString("commonName"))
                .lat(rs.getDouble("lat"))
                .lon(rs.getDouble("lon"))
                .geoHash(rs.getString("geoHash"))
                .stopType(rs.getString("stopType"))
                .indicator(rs.getString("indicator"))
                .stopLetter(rs.getString("stopLetter"))
                .lastUpdatedTime(rs.getString("lastUpdatedTime"))
                .icsCode(rs.getString("icsCode"))
                .towards(rs.getString("towards"))
                .compassPoint(rs.getString("compassPoint"))
                .stationNaptan(rs.getString("stationNaptan"))
                .modes(modes)
                .searchKeys(searchKeys)
                .build();
    }
}
