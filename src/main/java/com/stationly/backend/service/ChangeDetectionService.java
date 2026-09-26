package com.stationly.backend.service;

import com.stationly.backend.model.LineData;
import com.stationly.backend.model.StationPredictions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChangeDetectionService {

    // Cache to store the previous predictions for change detection
    private final Map<String, Map<String, LineData>> stationStateCache = new ConcurrentHashMap<>();
    // Remembers the last-known station name so wipes NEVER send "name": null
    private final Map<String, String> stationNameCache = new ConcurrentHashMap<>();
    // Cache to store the last time (cycle) we successfully pushed an update for a station
    private final Map<String, Long> stationHeartbeatCache = new ConcurrentHashMap<>();
    private final AtomicLong cycleCounter = new AtomicLong(0);

    private static final int HEARTBEAT_THRESHOLD_CYCLES = 5;

    public long incrementCycle() {
        return cycleCounter.incrementAndGet();
    }

    public long getCurrentCycle() {
        return cycleCounter.get();
    }

    /**
     * Filters a map of station predictions to only those that have changed or triggered a heartbeat.
     */
    public Map<String, Object> getChangedStations(String mode, Map<String, StationPredictions> groupedStations) {
        Map<String, Object> changedData = new HashMap<>();
        long currentCycle = cycleCounter.get();

        groupedStations.forEach((stationId, predictions) -> {
            Map<String, LineData> currentLines = predictions.getLines();
            Map<String, LineData> lastLines = stationStateCache.get(stationId);
            Long lastHeartbeatCycle = stationHeartbeatCache.getOrDefault(stationId, 0L);

            boolean contentChanged = (lastLines == null || !lastLines.equals(currentLines));
            boolean heartbeatTriggered = (currentCycle - lastHeartbeatCycle >= HEARTBEAT_THRESHOLD_CYCLES);

            // Remember the real station name so wipe frames never have null name
            if (predictions.getStationName() != null && !predictions.getStationName().isBlank()) {
                stationNameCache.put(stationId, predictions.getStationName());
            }

            if (contentChanged || heartbeatTriggered) {
                changedData.put(stationId, predictions);
                // Update caches
                stationStateCache.put(stationId, currentLines != null ? new HashMap<>(currentLines) : new HashMap<>());
                stationHeartbeatCache.put(stationId, currentCycle);

                if (!contentChanged && heartbeatTriggered) {
                    log.debug("💓 [{}] Heartbeat push for station: {}", mode, stationId);
                }
            }
        });

        return changedData;
    }

    /**
     * Detects stations that were in the cache for this mode but are missing from the current poll.
     */
    public void detectAndAddWipes(String mode, int totalArrivals, Map<String, StationPredictions> currentStations, Map<String, Object> fcmData) {
        // We only clear if we are confident (e.g. arrivals list is healthy)
        if (totalArrivals > 50) {
            List<String> potentiallyDisappeared = stationStateCache.keySet().stream()
                    .filter(id -> !currentStations.containsKey(id))
                    .filter(id -> isStationRelevantToMode(id, mode))
                    .collect(Collectors.toList());

            for (String stationId : potentiallyDisappeared) {
                log.info("🧹 [{}] Station disappeared from feed, clearing: {}", mode, stationId);
                String cleanId = stationId.replace("Station_", "");
                String stationName = stationNameCache.getOrDefault(stationId, cleanId);

                // Send empty update to clear client state
                fcmData.put(stationId, StationPredictions.builder()
                        .stationId(cleanId)
                        .stationName(stationName)
                        .lines(new HashMap<>())
                        .lastUpdatedTime(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                        .build());
                
                stationStateCache.remove(stationId);
                stationHeartbeatCache.remove(stationId);
                stationNameCache.remove(stationId);
            }
        }
    }

    private boolean isStationRelevantToMode(String stationId, String mode) {
        String id = stationId.startsWith("Station_") ? stationId.substring(8) : stationId;

        // 1. Underground (Tube): All 272 stations
        if ("tube".equalsIgnoreCase(mode)) {
            return id.startsWith("940GZZLU")
                    || "940GZZBPSUST".equals(id)
                    || "940GZZNEUGST".equals(id);
        }

        // 2. DLR: All 45 stations
        if ("dlr".equalsIgnoreCase(mode)) {
            return id.startsWith("940GZZDL");
        }

        // 3. Buses: All 19,737 London bus stops
        if ("bus".equalsIgnoreCase(mode)) {
            return id.startsWith("490")
                    || id.startsWith("4000")
                    || id.startsWith("1500")
                    || id.startsWith("2400")
                    || id.startsWith("2100")
                    || id.startsWith("1590")
                    || id.startsWith("0370")
                    || id.startsWith("0400");
        }

        // Overground & Elizabeth Line: Deliberately skipped.
        // These rail modes use ArrivalDepartures timetable boards (real timetables
        // at termini even during zero live countdown arrivals). Wiping them on countdown
        // absence would destroy quiet-hour terminus boards.
        return false;
    }
}
