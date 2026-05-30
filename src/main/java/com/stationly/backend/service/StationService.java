package com.stationly.backend.service;

import ch.hsr.geohash.GeoHash;
import com.google.cloud.firestore.*;
import com.google.api.core.ApiFuture;
import com.stationly.backend.client.TflApiClient;
import com.stationly.backend.model.Station;
import com.stationly.backend.repository.DataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StationService {

    private final TflApiClient tflApiClient;
    private final DataRepository<Station, String> stationRepository;
    private final LocalDatabaseService localDatabaseService;
    private final Firestore firestore;

    @Autowired
    public StationService(TflApiClient tflApiClient,
                          DataRepository<Station, String> stationRepository,
                          LocalDatabaseService localDatabaseService,
                          @Autowired(required = false) Firestore firestore) {
        this.tflApiClient = tflApiClient;
        this.stationRepository = stationRepository;
        this.localDatabaseService = localDatabaseService;
        this.firestore = firestore;
    }

    private ListenerRegistration listenerRegistration;

    @PostConstruct
    public void init() {
        log.info("CACHE: 📡 Initializing Stations cache...");

        if (firestore != null) {
            // 1. Perform Delta Sync on boot
            try {
                String lastSync = localDatabaseService.getLastSyncTime("stations");
                log.info("CACHE: 🔄 Delta sync [stations]. Last sync: {}", lastSync != null ? lastSync : "Never");

                Query query = firestore.collection("stations");
                if (lastSync != null && !lastSync.isEmpty()) {
                    query = query.whereGreaterThan("lastUpdatedTime", lastSync);
                }

                ApiFuture<QuerySnapshot> future = query.get();
                QuerySnapshot snapshot = future.get();
                if (!snapshot.isEmpty()) {
                    log.info("CACHE: 📥 Found {} new/modified documents in [stations]. Applying deltas...", snapshot.size());
                    List<Station> toUpdate = new ArrayList<>();
                    String newestTime = lastSync != null ? lastSync : "";
                    
                    for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
                        Station station = doc.toObject(Station.class);
                        if (station != null && station.getNaptanId() != null) {
                            toUpdate.add(station);
                            if (station.getLastUpdatedTime() != null && station.getLastUpdatedTime().compareTo(newestTime) > 0) {
                                newestTime = station.getLastUpdatedTime();
                            }
                        }
                    }
                    localDatabaseService.saveAllStations(toUpdate);
                    if (!newestTime.isEmpty()) {
                        localDatabaseService.updateLastSyncTime("stations", newestTime);
                    }
                } else {
                    log.info("CACHE: 🏷️ Collection [stations] is already up to date.");
                }
            } catch (Exception e) {
                log.warn("CACHE: ⚠️ Firestore delta sync for stations failed: {}", e.getMessage());
            }

            // 2. Register Active Snapshot Listener
            try {
                String lastSync = localDatabaseService.getLastSyncTime("stations");
                if (lastSync == null || lastSync.isEmpty()) {
                    lastSync = "1970-01-01T00:00:00Z";
                }
                log.info("CACHE: ⚡ Setting up real-time listener for stations > {}", lastSync);

                final String initialSyncTime = lastSync;
                listenerRegistration = firestore.collection("stations")
                        .whereGreaterThan("lastUpdatedTime", initialSyncTime)
                        .addSnapshotListener((snapshots, e) -> {
                            if (e != null) {
                                log.error("CACHE: ❌ Listen failed on stations", e);
                                return;
                            }
                            if (snapshots != null) {
                                String newestTime = initialSyncTime;
                                boolean hasNewest = false;
                                
                                for (DocumentChange dc : snapshots.getDocumentChanges()) {
                                    QueryDocumentSnapshot doc = dc.getDocument();
                                    String id = doc.getId();
                                    switch (dc.getType()) {
                                        case ADDED:
                                        case MODIFIED:
                                            Station station = doc.toObject(Station.class);
                                            if (station != null && station.getNaptanId() != null) {
                                                log.info("CACHE: ⚡ Reactively caching station: {}", id);
                                                localDatabaseService.upsertStation(station);
                                                if (station.getLastUpdatedTime() != null && station.getLastUpdatedTime().compareTo(newestTime) > 0) {
                                                    newestTime = station.getLastUpdatedTime();
                                                    hasNewest = true;
                                                }
                                            }
                                            break;
                                        case REMOVED:
                                            log.info("CACHE: ⚡ Reactively removing station: {}", id);
                                            localDatabaseService.deleteStation(id);
                                            break;
                                    }
                                }
                                if (hasNewest) {
                                    localDatabaseService.updateLastSyncTime("stations", newestTime);
                                    log.info("CACHE: 📝 Updated [stations] lastSyncTime in SQLite metadata to {}", newestTime);
                                }
                            }
                        });
            } catch (Exception e) {
                log.error("CACHE: ❌ Failed to register real-time Firestore listener for stations", e);
            }
        } else {
            log.warn("CACHE: ⚠️ Firestore is null. Skipping Firestore sync and listener.");
        }
    }

    @PreDestroy
    public void cleanup() {
        if (listenerRegistration != null) {
            log.info("CACHE: Unregistering Firestore stations listener...");
            listenerRegistration.remove();
        }
    }

    public void syncStationsByMode(String modeName) {
        syncStationsByMode(modeName, null);
    }

    /**
     * Sync stations for a specific mode (and optionally line) from TfL API
     * and update Firestore with any changes.
     */
    public void syncStationsByMode(String modeName, String lineId) {
        log.info("╔═══════════════════════════════════════════════════════════════════");
        log.info("║ 🚀 STATION SYNC STARTED | Mode: {} | Line: {}", modeName, lineId != null ? lineId : "ALL");
        log.info("╚═══════════════════════════════════════════════════════════════════");

        // 1. Fetch stations intelligently to avoid full DB scan
        log.info("📥 [{}] Step 1: Fetching existing stations from Firestore...", modeName);
        Map<String, Station> existingStations = getSavedStations(modeName, lineId);
        log.info("✅ [{}] Step 1: Loaded {} existing stations", modeName, existingStations.size());

        // 2. Fetch lines to process
        log.info("📋 [{}] Step 2: Fetching lines to process...", modeName);
        List<Map<String, Object>> lines = tflApiClient.getLinesByMode(modeName);
        if (lines == null || lines.isEmpty()) {
            log.warn("⚠️ [{}] No lines found for mode", modeName);
            return;
        }
        log.info("✅ [{}] Step 2: Found {} lines to process", modeName, lines.size());

        // 3. Process lines in parallel to build "Fresh" state in-memory
        log.info("🔄 [{}] Step 3: Processing lines in parallel...", modeName);
        Map<String, Station> freshStationsMap = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(5);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (Map<String, Object> line : lines) {
            String currentLineId = (String) line.get("id");
            if (lineId != null && !lineId.equals(currentLineId))
                continue;

            futures.add(executor.submit(() -> {
                try {
                    processLineForBatch(currentLineId, modeName, freshStationsMap, existingStations);
                } catch (Exception e) {
                    log.error("❌ [{}] Failed to process line {}: {}", modeName, currentLineId, e.getMessage());
                }
            }));
        }

        // Wait for all
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("❌ [{}] Error waiting for future: {}", modeName, e.getMessage());
            }
        }
        executor.shutdown();
        log.info("✅ [{}] Step 3: Processed {} lines", modeName, lines.size());

        // 4. Diff and Identify Changed Stations
        log.info("🔍 [{}] Step 4: Validating changes against existing DB...", modeName);
        List<Station> changedStations = new ArrayList<>();
        int totalProcessed = freshStationsMap.size();

        for (Station fresh : freshStationsMap.values()) {
            Station existing = existingStations.get(fresh.getNaptanId());
            if (hasStationChanged(existing, fresh)) {
                changedStations.add(fresh);
            }
        }

        // 5. Save only changed stations
        if (!changedStations.isEmpty()) {
            log.info("💾 [{}] Step 5: Saving {} changed/new stations in batches...", modeName, changedStations.size());
            saveInBatches(changedStations, modeName);
        } else {
            log.info("🎉 [{}] Step 5: No changes detected. All stations up to date", modeName);
        }

        log.info("╔═══════════════════════════════════════════════════════════════════");
        log.info("║ ✅ [{}] SYNC COMPLETED | Processed: {} stations | Changed: {}",
                modeName, totalProcessed, changedStations.size());
        log.info("╚═══════════════════════════════════════════════════════════════════");
    }

    private void saveInBatches(List<Station> stations, String modeName) {
        List<Station> batch = new ArrayList<>();
        int total = stations.size();
        int savedCount = 0;
        for (Station s : stations) {
            batch.add(s);
            if (batch.size() == 100) { // Batch size 100
                stationRepository.saveAll(batch);
                savedCount += batch.size();
                log.info("💾 Saving batch of 100 stations (total: {}/{}) for mode: {}", savedCount, total, modeName);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            stationRepository.saveAll(batch);
            savedCount += batch.size();
            log.info("💾 Saving final batch of {} stations (total: {}/{}) for mode: {}", batch.size(), savedCount,
                    total,
                    modeName);
        }
    }

    private boolean hasStationChanged(Station existing, Station fresh) {
        if (existing == null)
            return true; // New station

        // Compare fields
        if (!Objects.equals(existing.getCommonName(), fresh.getCommonName()))
            return true;
        if (existing.getLat() != fresh.getLat())
            return true;
        if (existing.getLon() != fresh.getLon())
            return true;
        if (!Objects.equals(existing.getStopType(), fresh.getStopType()))
            return true;
        if (!Objects.equals(existing.getIndicator(), fresh.getIndicator()))
            return true;
        if (!Objects.equals(existing.getStopLetter(), fresh.getStopLetter()))
            return true;
        if (!Objects.equals(existing.getIcsCode(), fresh.getIcsCode()))
            return true;
        if (!Objects.equals(existing.getTowards(), fresh.getTowards()))
            return true;
        if (!Objects.equals(existing.getCompassPoint(), fresh.getCompassPoint()))
            return true;
        if (!Objects.equals(existing.getStationNaptan(), fresh.getStationNaptan()))
            return true;

        // Compare Modes/Lines - Deep compare needed?
        // Basic map size checking + logic is complex.
        // Since we rebuild the Station object based on fresh data (and merge prev
        // modes),
        // we can check if the serialized 'lines' or 'modes' differ.
        // Simplest: If logic constructed 'fresh' by merging existing modes + new mode
        // data,
        // then 'fresh' contains the targeted state.
        // For simplicity, lets rely on .equals of maps if Lombok @Data generates it
        // correctly (it does).
        return !Objects.equals(existing.getModes(), fresh.getModes()) ||
                !Objects.equals(existing.getSearchKeys(), fresh.getSearchKeys());
    }

    public void syncLine(String lineId, String modeName) {
        log.info("🔄 Starting sync for line: {}", lineId);
        Map<String, Station> stationsToSave = new HashMap<>(); // Fresh map for single line
        // We need existing stations to do a proper merge even for single line to avoid
        // overwriting other modes
        Map<String, Station> existingStations = getSavedStations(null, lineId);

        try {
            processLineForBatch(lineId, modeName, stationsToSave, existingStations);

            // Diff and Save (Simplified for single line - just save what we processed if
            // changed)
            List<Station> changedStations = new ArrayList<>();
            for (Station fresh : stationsToSave.values()) {
                Station existing = existingStations.get(fresh.getNaptanId());
                if (hasStationChanged(existing, fresh)) {
                    changedStations.add(fresh);
                }
            }

            if (!changedStations.isEmpty()) {
                stationRepository.saveAll(changedStations);
                log.info("✅ Sync completed for line: {}. Saved {} stations.", lineId, changedStations.size());
            } else {
                log.info("✅ Sync completed for line: {}. No changes detected.", lineId);
            }

        } catch (Exception e) {
            log.error("❌ Failed to sync line {}: {}", lineId, e.getMessage());
        }
    }

    private void processLineForBatch(String lineId, String modeName, Map<String, Station> freshMap,
            Map<String, Station> existingStations) {
        // 1. Fetch Basic Station Info (StopPoints)
        List<Map<String, Object>> stopPoints = tflApiClient.getStopPointsByLine(lineId);
        if (stopPoints == null || stopPoints.isEmpty()) {
            return;
        }

        // 2. Fetch Route Sequences (Inbound & Outbound)
        Set<String> inboundIds = fetchNaptanIdsFromRouteSequence(lineId, "inbound");
        Set<String> outboundIds = fetchNaptanIdsFromRouteSequence(lineId, "outbound");

        // 3. Process each StopPoint & Aggregate
        for (Map<String, Object> sp : stopPoints) {
            updateStationInBatch(sp, lineId, modeName, inboundIds, outboundIds, freshMap, existingStations);
        }
    }

    private Set<String> fetchNaptanIdsFromRouteSequence(String lineId, String direction) {
        try {
            Map<String, Object> routeSeq = tflApiClient.getRouteSequence(lineId, direction);
            if (routeSeq == null)
                return Collections.emptySet();

            List<Map<String, Object>> orderedRoutes = (List<Map<String, Object>>) routeSeq.get("orderedLineRoutes");
            if (orderedRoutes == null)
                return Collections.emptySet();

            Set<String> naptanIds = new HashSet<>();
            for (Map<String, Object> route : orderedRoutes) {
                List<String> ids = (List<String>) route.get("naptanIds");
                if (ids != null) {
                    naptanIds.addAll(ids);
                }
            }
            return naptanIds;
        } catch (Exception e) {
            log.warn("⚠️ Failed to fetch {} route sequence for line {}: {}", direction, lineId, e.getMessage());
            return Collections.emptySet();
        }
    }

    private void updateStationInBatch(Map<String, Object> sp, String lineId, String modeName,
            Set<String> inboundIds, Set<String> outboundIds, Map<String, Station> freshMap,
            Map<String, Station> existingStations) {

        String stopType = (String) sp.get("stopType");
        String naptanId = (String) sp.get("naptanId");

        String expectedStopType = com.stationly.backend.util.TflUtils.getExpectedStopType(modeName);
        if (expectedStopType == null || !expectedStopType.equals(stopType)) {
            return;
        }

        // We use compute to atomically update the FRESH map.
        // We initialize fresh entry with existing state if available, OR new if not.
        // BUT careful: if other threads updating same station, we want to merge.
        // Since we are iterating lines in parallel, same station can be hit by multiple
        // lines.
        // compute is good for this.

        freshMap.compute(naptanId, (key, currentFresh) -> {
            if (currentFresh == null) {
                // If not in fresh map, try to clone from existing or create new
                Station existing = existingStations.get(key);
                if (existing != null) {
                    // Start with deep copy of existing to preserve other modes
                    // Deep copy by serialization or manual builder. Manual is cheaper here given
                    // structure.
                    currentFresh = deepCopyStation(existing);
                } else {
                    currentFresh = Station.builder()
                            .naptanId(naptanId)
                            .modes(new HashMap<>())
                            .searchKeys(new ArrayList<>())
                            .build();
                }
            }
            // Now merge current line info into currentFresh
            mergeLineInfoIntoStation(currentFresh, sp, lineId, modeName, inboundIds, outboundIds);
            return currentFresh;
        });
    }

    private Station deepCopyStation(Station s) {
        // Poor man's deep copy for known structure
        Station.StationBuilder b = Station.builder()
                .naptanId(s.getNaptanId())
                .commonName(s.getCommonName())
                .lat(s.getLat())
                .lon(s.getLon())
                .geoHash(s.getGeoHash())
                .stopType(s.getStopType())
                .indicator(s.getIndicator())
                .stopLetter(s.getStopLetter())
                .lastUpdatedTime(s.getLastUpdatedTime())
                .icsCode(s.getIcsCode())
                .towards(s.getTowards())
                .compassPoint(s.getCompassPoint())
                .stationNaptan(s.getStationNaptan());

        // Copy Modes deeply
        Map<String, Station.ModeGroup> newModes = new HashMap<>();
        if (s.getModes() != null) {
            for (Map.Entry<String, Station.ModeGroup> entry : s.getModes().entrySet()) {
                Station.ModeGroup oldMg = entry.getValue();
                // Copy lines
                Map<String, Station.LineDetails> newLines = new HashMap<>();
                if (oldMg.getLines() != null) {
                    for (Map.Entry<String, Station.LineDetails> lineEntry : oldMg.getLines().entrySet()) {
                        Station.LineDetails oldLd = lineEntry.getValue();
                        newLines.put(lineEntry.getKey(), Station.LineDetails.builder()
                                .id(oldLd.getId())
                                .name(oldLd.getName())
                                .directions(new ArrayList<>(oldLd.getDirections()))
                                .build());
                    }
                }
                newModes.put(entry.getKey(), Station.ModeGroup.builder()
                        .modeName(oldMg.getModeName())
                        .lines(newLines)
                        .build());
            }
        }
        b.modes(newModes);
        b.searchKeys(new ArrayList<>(s.getSearchKeys()));
        return b.build();
    }

    private void mergeLineInfoIntoStation(Station station, Map<String, Object> sp, String lineId, String modeName,
            Set<String> inboundIds, Set<String> outboundIds) {
        // Update core fields (Always take latest from TfL)
        station.setCommonName((String) sp.get("commonName"));
        station.setLat((Double) sp.get("lat"));
        station.setLon((Double) sp.get("lon"));
        station.setStopType((String) sp.get("stopType"));
        station.setGeoHash(GeoHash.geoHashStringWithCharacterPrecision(station.getLat(), station.getLon(), 9));
        station.setLastUpdatedTime(
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_DATE_TIME));

        // Extract optional fields from TfL response
        station.setIndicator((String) sp.get("indicator"));
        station.setStopLetter((String) sp.get("stopLetter"));
        station.setIcsCode((String) sp.get("icsCode"));
        station.setTowards((String) sp.get("towards"));
        station.setCompassPoint((String) sp.get("compassPoint"));
        station.setStationNaptan((String) sp.get("stationNaptan"));

        // Update Mode Group
        Station.ModeGroup modeGroup = station.getModes().computeIfAbsent(modeName, k -> Station.ModeGroup.builder()
                .modeName(modeName)
                .lines(new HashMap<>())
                .build());

        // Line Details
        Station.LineDetails lineDetails = modeGroup.getLines().computeIfAbsent(lineId,
                k -> Station.LineDetails.builder()
                        .id(lineId)
                        .name(lineId)
                        .directions(new ArrayList<>())
                        .build());

        // Directions
        if (inboundIds.contains(station.getNaptanId()) && !lineDetails.getDirections().contains("inbound"))
            lineDetails.getDirections().add("inbound");
        if (outboundIds.contains(station.getNaptanId()) && !lineDetails.getDirections().contains("outbound"))
            lineDetails.getDirections().add("outbound");

        generateSearchKeys(station);
    }

    private Map<String, Station> getSavedStations(String modeName, String lineId) {
        List<Station> stations;

        if (lineId != null) {
            // If we are syncing a specific line, just get stations for that line
            log.info("🔎 Fetching stations for lineId: {} from SQLite", lineId);
            stations = localDatabaseService.getStationsBySearchKey(lineId);
        } else if ("bus".equalsIgnoreCase(modeName) || "tram".equalsIgnoreCase(modeName)) {
            // For bus/tram, fetching ALL is too heavy (20k+). Fetch only for this mode.
            log.info("🔎 Fetching stations for mode: {} (Optimized fetch) from SQLite", modeName);
            stations = localDatabaseService.getStationsBySearchKey(modeName);
        } else {
            // For Tube/DLR/Rail, fetch EVERYTHING EXCEPT buses
            log.info("🔎 Fetching all stations EXCEPT buses (Safe fetch for {}) from SQLite", modeName);
            stations = localDatabaseService.getStationsExceptStopType("NaptanPublicBusCoachTram");
        }

        return stations.stream()
                .collect(Collectors.toMap(Station::getNaptanId, s -> s, (s1, s2) -> s1));
    }

    private void generateSearchKeys(Station station) {
        Set<String> keys = new HashSet<>();

        // Iterate over Modes
        for (Map.Entry<String, Station.ModeGroup> modeEntry : station.getModes().entrySet()) {
            String modeName = modeEntry.getKey();
            Station.ModeGroup modeGroup = modeEntry.getValue();

            keys.add(modeName); // mode

            // Iterate over Lines within Mode
            for (Map.Entry<String, Station.LineDetails> lineEntry : modeGroup.getLines().entrySet()) {
                String lineId = lineEntry.getKey();
                Station.LineDetails details = lineEntry.getValue();

                keys.add(lineId); // lineId
                keys.add(modeName + "_" + lineId); // mode_lineId

                for (String dir : details.getDirections()) {
                    keys.add(lineId + "_" + dir); // lineId_direction
                    keys.add(modeName + "_" + lineId + "_" + dir); // mode_lineId_direction
                }
            }
        }

        station.setSearchKeys(new ArrayList<>(keys));
    }
}
