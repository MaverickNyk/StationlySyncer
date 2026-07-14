package com.stationly.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.DocumentChange;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.ListenerRegistration;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the direction a train DEPARTS in from a terminus.
 *
 * At a terminus the Arrivals feed carries no direction for turning-around
 * trains, but the line's route tells us: the departing direction is the ONE
 * direction whose destinations do not include that station (trains leave
 * towards the other end of the line). Mirrors stationly-backend's
 * resolveDepartingDirection rule so REST and FCM bucket relabelled terminus
 * predictions identically.
 *
 * Data source is the `routes/{lineId}` Firestore collection the backend
 * already maintains — NO TfL calls. Same tiering as the stations sync:
 * SQLite mirror is the cold-start source, RAM is the read path, and a
 * watermark-filtered realtime listener applies deltas (routes rarely change,
 * so steady-state Firestore reads are ~zero).
 */
@Service
@Slf4j
public class RouteDirectionResolver {

    private final Firestore firestore;
    private final LocalDatabaseService localDatabaseService;
    private final ObjectMapper objectMapper;

    public RouteDirectionResolver(
            @Autowired(required = false) Firestore firestore,
            LocalDatabaseService localDatabaseService,
            ObjectMapper objectMapper) {
        this.firestore = firestore;
        this.localDatabaseService = localDatabaseService;
        this.objectMapper = objectMapper;
    }

    /** lineId -> (terminus naptanId -> departing direction) */
    private final Map<String, Map<String, String>> departingDirs = new ConcurrentHashMap<>();

    private ListenerRegistration listenerRegistration;

    /**
     * @return the single departing direction for this station on this line,
     *         or null when unknown (mid-line stations, loops, no route data) —
     *         callers must fall back.
     */
    public String resolveDepartingDirection(String naptanId, String lineId) {
        if (naptanId == null || lineId == null) return null;
        Map<String, String> byTerminus = departingDirs.get(lineId.toLowerCase());
        return byTerminus != null ? byTerminus.get(naptanId) : null;
    }

    @PostConstruct
    public void init() {
        // 1. Cold start from the durable SQLite mirror — zero remote reads.
        Map<String, String> saved = localDatabaseService.getAllRouteDirections();
        saved.forEach((lineId, directionsJson) -> {
            try {
                List<Map<String, Object>> directions = objectMapper.readValue(
                        directionsJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                applyDirections(lineId, directions);
            } catch (Exception e) {
                log.warn("🧭 [ROUTE] Skipping bad cached route for {}: {}", lineId, e.getMessage());
            }
        });
        log.info("🧭 [ROUTE] Loaded departing-direction maps for {} lines from SQLite.", departingDirs.size());

        // 2. Watermark-filtered realtime listener — only route docs written
        //    after our newest mirrored copy are ever fetched from Firestore.
        if (firestore == null) {
            log.warn("🧭 [ROUTE] Firestore not configured — resolver serves SQLite data only.");
            return;
        }
        long watermark = localDatabaseService.getRouteDirectionsWatermark();
        listenerRegistration = firestore.collection("routes")
                .whereGreaterThan("lastUpdatedTime", watermark)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        log.error("🧭 [ROUTE] Listener error: {}", e.getMessage());
                        return;
                    }
                    if (snapshots == null) return;
                    // Collect the whole snapshot's rows and mirror them in ONE
                    // batch transaction — the cold-boot catch-up delivers every
                    // route doc in a single snapshot, and per-doc connections
                    // would hold the listener thread through hundreds of
                    // connect/write/close cycles.
                    List<Object[]> rows = new java.util.ArrayList<>();
                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        QueryDocumentSnapshot doc = dc.getDocument();
                        String lineId = doc.getId();
                        if (dc.getType() == DocumentChange.Type.REMOVED) {
                            departingDirs.remove(lineId.toLowerCase());
                            continue;
                        }
                        Object dirsObj = doc.get("directions");
                        if (!(dirsObj instanceof List)) continue;
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> directions = (List<Map<String, Object>>) dirsObj;
                        applyDirections(lineId, directions);
                        Long lut = doc.getLong("lastUpdatedTime");
                        try {
                            rows.add(new Object[]{
                                    lineId,
                                    objectMapper.writeValueAsString(directions),
                                    lut != null ? lut : System.currentTimeMillis()});
                        } catch (Exception ser) {
                            log.warn("🧭 [ROUTE] Failed to serialize route {} for SQLite: {}", lineId, ser.getMessage());
                        }
                    }
                    localDatabaseService.upsertAllRouteDirections(rows);
                    log.info("🧭 [ROUTE] Applied {} route deltas. {} lines cached.",
                            snapshots.getDocumentChanges().size(), departingDirs.size());
                });
    }

    /**
     * Build terminus -> departing direction for one line. A station qualifies
     * when exactly ONE direction's destination list excludes it; both-exclude
     * (mid-line) and both-include (loops) stay unresolved.
     */
    private void applyDirections(String lineId, List<Map<String, Object>> directions) {
        Map<String, Set<String>> destsByDir = new ConcurrentHashMap<>();
        for (Map<String, Object> d : directions) {
            Object dirName = d.get("direction");
            Object dests = d.get("destinations");
            if (dirName == null || !(dests instanceof List)) continue;
            Set<String> ids = ConcurrentHashMap.newKeySet();
            for (Object destObj : (List<?>) dests) {
                if (destObj instanceof Map) {
                    Object id = ((Map<?, ?>) destObj).get("id");
                    if (id != null) ids.add(id.toString());
                }
            }
            destsByDir.put(dirName.toString().toLowerCase(), ids);
        }
        if (destsByDir.isEmpty()) return;

        Map<String, String> byTerminus = new ConcurrentHashMap<>();
        Set<String> allTermini = ConcurrentHashMap.newKeySet();
        destsByDir.values().forEach(allTermini::addAll);
        for (String terminus : allTermini) {
            List<String> away = destsByDir.entrySet().stream()
                    .filter(en -> !en.getValue().contains(terminus))
                    .map(Map.Entry::getKey)
                    .toList();
            if (away.size() == 1) byTerminus.put(terminus, away.get(0));
        }
        departingDirs.put(lineId.toLowerCase(), byTerminus);
    }

    @PreDestroy
    public void cleanup() {
        if (listenerRegistration != null) listenerRegistration.remove();
    }
}
