package com.stationly.backend.service;

import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.model.Station;
import java.util.List;

public interface LocalDatabaseService {
    void initialize();
    String getLastSyncTime(String collection);
    void updateLastSyncTime(String collection, String time);
    List<LineStatusResponse> getAllLineStatuses();
    void upsertLineStatus(LineStatusResponse status);
    void deleteLineStatus(String id);

    // 🆕 Reactive Station Methods
    int getStationCount();
    void upsertStation(Station station);
    void saveAllStations(List<Station> stations);
    void deleteStation(String naptanId);
    List<Station> getStationsBySearchKey(String searchKey);
    List<Station> getStationsExceptStopType(String stopTypeToExclude);
}
