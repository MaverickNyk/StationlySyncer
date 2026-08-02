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
    private final LiveStreamPublisher liveStreamPublisher;
    private final ChangeDetectionService changeDetectionService;
    private final com.stationly.backend.sync.FirestoreDatabaseSyncer firestoreDatabaseSyncer;
    private final LocalDatabaseService localDatabaseService;
    private final ArrivalDeparturesFetchService arrivalDeparturesFetchService;

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
            // Departure-board calls (subscribed ∩ XR/OG stations) depend only
            // on local metadata — start them FIRST so they overlap the bulk
            // arrivals round-trip instead of stacking after it.
            var arrivalDeparturesPlan = arrivalDeparturesFetchService.planForMode(mode, activeStationsFilter);

            log.info("📡 [{}] Step 1: Fetching arrivals from TfL API...", mode);
            List<ArrivalPrediction> arrivals = tflApiClient.getArrivalsByMode(mode);
            if (arrivals == null) {
                arrivals = List.of();
            }

            if (arrivals.isEmpty() && arrivalDeparturesPlan.isEmpty()) {
                long duration = System.currentTimeMillis() - startMillis;
                log.warn("⚠️  [{}] NO DATA | No arrivals received | Took: {}ms", mode, duration);
                return;
            }

            arrivals = filterArrivals(arrivals, activeStationsFilter, mode);

            // Board-planned stations proceed even with zero subscribed
            // arrivals (quiet-hour terminus still gets its real timetable).
            if (arrivals.isEmpty() && arrivalDeparturesPlan.isEmpty()) {
                return;
            }

            var arrivalDeparturesData = arrivalDeparturesPlan.await();

            log.info("🔄 [{}] Step 2: Transforming into station-centric groups...", mode);
            Map<String, StationPredictions> groupedStations = transformationService
                    .transformToStationGroups(arrivals, arrivalDeparturesData);
            log.info("✅ [{}] Step 2: {} station groups", mode, groupedStations.size());

            log.info("⚡ [{}] Step 3: Change detection...", mode);
            Map<String, Object> fcmData = changeDetectionService.getChangedStations(mode, groupedStations);
            changeDetectionService.detectAndAddWipes(mode, arrivals.size(), groupedStations, fcmData);

            int fcmCount = fcmData.size();
            if (fcmCount > 0) {
                log.info("⚡ [{}] Step 3: {}/{} stations changed. Publishing...", mode, fcmCount, groupedStations.size());
                fcmService.publishAll(fcmData);

                // Second consumer of the SAME change-detected map: the live stream
                // for foreground app clients. Enqueue-only, so
                // it can't delay this cycle. Wrapped anyway — the enclosing
                // catch would swallow a throw here but also skip the summary
                // logging below, making an unrelated failure look like a
                // polling failure.
                try {
                    liveStreamPublisher.publishAll(fcmData);
                } catch (Exception wsErr) {
                    log.warn("⚠️ [{}] Live-stream enqueue failed (FCM unaffected): {}", mode, wsErr.getMessage());
                }
                log.info("✅ [{}] Step 3: Queued {} FCM messages", mode, fcmCount);
            } else {
                log.info("✅ [{}] Step 3: No changes. Skipping FCM.", mode);
            }

            long duration = System.currentTimeMillis() - startMillis;
            log.info("│ ✅ [{}] {} arrivals → {} stations → {} FCM | {}ms",
                    mode, arrivals.size(), groupedStations.size(), fcmCount, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startMillis;
            log.error("❌ [{}] FAILED | Took: {}ms | Error: {}", mode, duration, e.getMessage());
        }
    }

    private java.util.Set<String> getActiveSubscribedStations() {
        if (!isSubscribedStrategy) {
            return null;
        }

        // 1. Prefer the live realtime snapshot (RAM, kept by FirestoreDatabaseSyncer).
        com.google.cloud.firestore.DocumentSnapshot doc = firestoreDatabaseSyncer.getDocument("metadata/subscribed_stations");
        if (doc != null && doc.exists()) {
            Map<String, Object> data = doc.getData();
            Object countsObj = (data != null) ? data.get("stationCounts") : null;
            if (countsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Number> counts = (Map<String, Number>) countsObj;
                java.util.Set<String> active = counts.entrySet().stream()
                        .filter(e -> e.getValue() != null && e.getValue().intValue() > 0)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());
                // SQLite is kept in sync by the realtime listener
                // (FirestoreDatabaseSyncer), same as every other collection — so
                // here we just return the live set.
                return active;
            }
            // Doc present but no usable stationCounts — don't trust it enough to
            // wipe the durable copy; fall through to the SQLite set below.
        }

        // 2. RAM cold (just restarted, listener not warm yet) or malformed →
        //    use the durable SQLite mirror so we never miss a subscribed station
        //    and never drop into NAP MODE during the warm-up window.
        java.util.Set<String> fromDb = localDatabaseService.getSubscribedStationIds();
        log.warn("⚠️ subscribed_stations not live in RAM — using {} stations from SQLite fallback.", fromDb.size());
        return fromDb;
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
