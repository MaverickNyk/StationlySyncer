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
    /** Stations whose modes map contains the given mode key (e.g. "elizabeth-line"). */
    List<Station> getStationsByMode(String mode);
    List<Station> getStationsExceptStopType(String stopTypeToExclude);

    // Durable mirror of routes/{lineId}.directions — read on cold start so the
    // terminus direction resolver works without any Firestore or TfL reads.
    /** One transaction per Firestore snapshot — the cold-boot catch-up carries every doc. Rows: [lineId, directionsJson, lastUpdatedMs]. */
    void upsertAllRouteDirections(java.util.List<Object[]> rows);
    java.util.Map<String, String> getAllRouteDirections();
    long getRouteDirectionsWatermark();
}
