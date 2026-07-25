package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.Station;
import com.stationly.backend.service.LocalDatabaseService;
import com.stationly.backend.sync.FirestoreDatabaseSyncer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Decides which National Rail stations the module actually works on:
 * the intersection of the globally-subscribed station set and the stations our
 * store knows as national-rail. Filtering here keeps the national Push Port
 * firehose and the OpenLDBWS call volume bounded to stations someone is watching.
 *
 * Subscribed-set resolution mirrors {@code TflPollingService.getActiveSubscribedStations}
 * (live Firestore snapshot, SQLite fallback). Result is cached in memory and
 * rebuilt by {@link #refresh()} on the heartbeat cadence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailCoverageService {

    static final String MODE_NATIONAL_RAIL = "national-rail";

    private final FirestoreDatabaseSyncer firestoreDatabaseSyncer;
    private final LocalDatabaseService localDatabaseService;

    // naptanId (upper) -> Station, for covered NR stations only.
    private volatile Map<String, Station> coveredByNaptan = Map.of();

    public void refresh() {
        Set<String> subscribed = readSubscribedStationIds().stream()
                .map(String::toUpperCase).collect(Collectors.toSet());

        Map<String, Station> next = new ConcurrentHashMap<>();
        for (Station station : localDatabaseService.getStationsByMode(MODE_NATIONAL_RAIL)) {
            String naptanId = station.getNaptanId();
            if (naptanId != null && subscribed.contains(naptanId.toUpperCase())) {
                next.put(naptanId.toUpperCase(), station);
            }
        }
        coveredByNaptan = next;
        log.info("NR_COVERAGE: 🎯 {} subscribed National Rail station(s) in coverage.", next.size());
    }

    public Collection<Station> coveredStations() {
        return coveredByNaptan.values();
    }

    public Optional<Station> find(String naptanId) {
        if (naptanId == null) return Optional.empty();
        return Optional.ofNullable(coveredByNaptan.get(naptanId.toUpperCase()));
    }

    public boolean isCovered(String naptanId) {
        return naptanId != null && coveredByNaptan.containsKey(naptanId.toUpperCase());
    }

    /** Mirrors the TfL poller's subscribed-station resolution: live snapshot, SQLite fallback. */
    @SuppressWarnings("unchecked")
    private Set<String> readSubscribedStationIds() {
        try {
            var doc = firestoreDatabaseSyncer.getDocument("metadata/subscribed_stations");
            if (doc != null && doc.exists()) {
                Map<String, Object> data = doc.getData();
                Object countsObj = (data != null) ? data.get("stationCounts") : null;
                if (countsObj instanceof Map) {
                    Map<String, Number> counts = (Map<String, Number>) countsObj;
                    return counts.entrySet().stream()
                            .filter(e -> e.getValue() != null && e.getValue().intValue() > 0)
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toSet());
                }
            }
        } catch (Exception e) {
            log.warn("NR_COVERAGE: ⚠️ live subscribed-set read failed, using SQLite fallback: {}", e.getMessage());
        }
        return localDatabaseService.getSubscribedStationIds();
    }
}
