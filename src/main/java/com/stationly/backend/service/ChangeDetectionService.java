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
                // Send empty update to clear client state
                fcmData.put(stationId, StationPredictions.builder()
                        .stationId(stationId.replace("Station_", ""))
                        .lines(new HashMap<>())
                        .lastUpdatedTime(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                        .build());
                
                stationStateCache.remove(stationId);
                stationHeartbeatCache.remove(stationId);
            }
        }
    }

    private boolean isStationRelevantToMode(String stationId, String mode) {
        if ("bus".equalsIgnoreCase(mode)) return stationId.contains("490");
        if ("tube".equalsIgnoreCase(mode)) return stationId.contains("940");
        if ("dlr".equalsIgnoreCase(mode)) return stationId.contains("940GZZD");
        return false;
    }
}
