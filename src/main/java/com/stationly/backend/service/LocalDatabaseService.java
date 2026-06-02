package com.stationly.backend.service;

import com.stationly.backend.model.LineStatusResponse;
import com.stationly.backend.model.Station;
import java.util.List;
import java.util.Set;

public interface LocalDatabaseService {
    void initialize();
    String getLastSyncTime(String collection);
    void updateLastSyncTime(String collection, String time);

    // Durable mirror of the subscribed-station set — read on cold start so the
    // poller never misses a subscribed station before the realtime listener warms.
    void replaceSubscribedStations(Set<String> naptanIds);
    Set<String> getSubscribedStationIds();
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
