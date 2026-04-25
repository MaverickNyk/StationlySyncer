package com.stationly.backend.service;

import com.stationly.backend.client.TflApiClient;
import com.stationly.backend.model.ArrivalPrediction;
import com.stationly.backend.model.StationPredictions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TflPollingService {

    private final TflApiClient tflApiClient;
    private final DataTransformationService transformationService;
    private final FcmService fcmService;
    private final MonitoringService monitoringService;
    private final ChangeDetectionService changeDetectionService;
    private final com.stationly.backend.sync.FirestoreDatabaseSyncer firestoreDatabaseSyncer;

    @Value("${tfl.transport.modes}")
    private String tflTransportModes;

    @Value("${tfl.polling.strategy:all}")
    private String pollingStrategy;

    private String[] activeModesArray;
    private boolean isSubscribedStrategy;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.isSubscribedStrategy = "subscribed".equalsIgnoreCase(pollingStrategy);
        String[] rawModes = tflTransportModes != null ? tflTransportModes.split(",") : new String[0];
        this.activeModesArray = Arrays.stream(rawModes)
                .map(String::trim)
                .filter(m -> !m.isEmpty())
                .toArray(String[]::new);
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Refresh all configured transport modes in parallel.
     */
    public void refreshAll() {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        long startMillis = System.currentTimeMillis();

        long currentCycle = changeDetectionService.incrementCycle();
        log.info("╔═══════════════════════════════════════════════════════════════════");
        log.info("║ 🚇 TFL REFRESH STARTED | Modes: {} | Cycle: #{} | Time: {}",
                tflTransportModes.toUpperCase(), currentCycle, timestamp);
        log.info("╚═══════════════════════════════════════════════════════════════════");

        final java.util.Set<String> activeStationsFilter = getActiveSubscribedStations();

        if (isSubscribedStrategy) {
            log.info("🎯 Strategy: SUBSCRIBED. Found {} active stations globally.", activeStationsFilter.size());
            if (activeStationsFilter.isEmpty()) {
                long duration = System.currentTimeMillis() - startMillis;
                log.info("😴 NAP MODE: No active station subscriptions right now. Pausing TfL fetching entirely! | {}ms", duration);
                return;
            }
        }

        var executor = java.util.concurrent.Executors.newFixedThreadPool(activeModesArray.length + 2);
        try {
            log.info("📋 Processing {} modes in parallel: {}", activeModesArray.length, Arrays.toString(activeModesArray));
            List<CompletableFuture<Void>> futures = Arrays.stream(activeModesArray)
                    .map(mode -> CompletableFuture.runAsync(() -> refreshMode(mode, activeStationsFilter), executor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            long totalDuration = System.currentTimeMillis() - startMillis;
            monitoringService.recordPollingDuration("total", totalDuration, "SUCCESS");
            log.info("╔═══════════════════════════════════════════════════════════════════");
            log.info("║ ✅ TFL REFRESH COMPLETED | Total Time: {}ms | Modes: {}", totalDuration, activeModesArray.length);
            log.info("╚═══════════════════════════════════════════════════════════════════");
        } finally {
            executor.shutdown();
        }
    }

    private void refreshMode(String mode, java.util.Set<String> activeStationsFilter) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        long startMillis = System.currentTimeMillis();

        log.info("┌───────────────────────────────────────────────────────────────────");
        log.info("│ 🚇 POLLING MODE: {} | Time: {}", mode.toUpperCase(), timestamp);
        log.info("└───────────────────────────────────────────────────────────────────");

        try {
            log.info("📡 [{}] Step 1: Fetching arrivals from TfL API...", mode);
            List<ArrivalPrediction> arrivals = tflApiClient.getArrivalsByMode(mode);

            if (arrivals == null || arrivals.isEmpty()) {
                long duration = System.currentTimeMillis() - startMillis;
                log.warn("⚠️  [{}] NO DATA | No arrivals received | Took: {}ms", mode, duration);
                monitoringService.recordPollingDuration(mode, duration, "NO_DATA");
                return;
            }

            arrivals = filterArrivals(arrivals, activeStationsFilter, mode);

            if (arrivals.isEmpty()) {
                long duration = System.currentTimeMillis() - startMillis;
                monitoringService.recordPollingDuration(mode, duration, "SUCCESS");
                return;
            }

            log.info("🔄 [{}] Step 2: Transforming into station-centric groups...", mode);
            Map<String, StationPredictions> groupedStations = transformationService
                    .transformToStationGroups(arrivals);
            log.info("✅ [{}] Step 2: {} station groups", mode, groupedStations.size());

            log.info("⚡ [{}] Step 3: Change detection...", mode);
            Map<String, Object> fcmData = changeDetectionService.getChangedStations(mode, groupedStations);
            changeDetectionService.detectAndAddWipes(mode, arrivals.size(), groupedStations, fcmData);

            int fcmCount = fcmData.size();
            if (fcmCount > 0) {
                log.info("⚡ [{}] Step 3: {}/{} stations changed. Publishing...", mode, fcmCount, groupedStations.size());
                fcmService.publishAll(fcmData);
                log.info("✅ [{}] Step 3: Queued {} FCM messages", mode, fcmCount);
            } else {
                log.info("✅ [{}] Step 3: No changes. Skipping FCM.", mode);
            }

            long duration = System.currentTimeMillis() - startMillis;
            log.info("│ ✅ [{}] {} arrivals → {} stations → {} FCM | {}ms",
                    mode, arrivals.size(), groupedStations.size(), fcmCount, duration);

            monitoringService.recordPollingDuration(mode, duration, "SUCCESS");
            monitoringService.recordArrivalsCount(mode, arrivals.size());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startMillis;
            log.error("❌ [{}] FAILED | Took: {}ms | Error: {}", mode, duration, e.getMessage());
            monitoringService.recordPollingDuration(mode, duration, "FAILED");
        }
    }

    private java.util.Set<String> getActiveSubscribedStations() {
        if (!isSubscribedStrategy) {
            return null;
        }

        com.google.cloud.firestore.DocumentSnapshot doc = firestoreDatabaseSyncer.getDocument("metadata/subscribed_stations");
        if (doc == null || !doc.exists()) {
            return java.util.Collections.emptySet();
        }

        Map<String, Object> data = doc.getData();
        if (data == null || !data.containsKey("stationCounts")) {
            return java.util.Collections.emptySet();
        }

        @SuppressWarnings("unchecked")
        Map<String, Number> counts = (Map<String, Number>) data.get("stationCounts");
        if (counts == null) {
            return java.util.Collections.emptySet();
        }

        return counts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().intValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private List<ArrivalPrediction> filterArrivals(List<ArrivalPrediction> arrivals, java.util.Set<String> activeStationsFilter, String mode) {
        int fetchedCount = arrivals.size();
        
        if (activeStationsFilter == null) {
            log.info("✅ [{}] Step 1: Received {} arrivals", mode, arrivals.size());
            return arrivals;
        }

        List<ArrivalPrediction> filtered = arrivals.stream()
                .filter(a -> activeStationsFilter.contains(a.getNaptanId()))
                .collect(Collectors.toList());
        
        log.info("✅ [{}] Step 1: Received {} arrivals (Filtered down from {} using Subscribed list)", mode, filtered.size(), fetchedCount);
        
        if (filtered.isEmpty()) {
            log.info("⏭️  [{}] Step 1.5: 0 subscribed stations returned for this mode. Skipping processing.", mode);
        }
        
        return filtered;
    }
}
