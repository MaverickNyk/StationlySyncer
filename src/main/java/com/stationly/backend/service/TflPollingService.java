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

    @Value("${tfl.transport.modes}")
    private String tflTransportModes;

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

        String[] modes = tflTransportModes.split(",");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(modes.length + 2);
        try {
            log.info("📋 Processing {} modes in parallel: {}", modes.length, Arrays.toString(modes));
            List<CompletableFuture<Void>> futures = Arrays.stream(modes)
                    .map(String::trim)
                    .filter(mode -> !mode.isEmpty())
                    .map(mode -> CompletableFuture.runAsync(() -> refreshMode(mode), executor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            long totalDuration = System.currentTimeMillis() - startMillis;
            monitoringService.recordPollingDuration("total", totalDuration, "SUCCESS");
            log.info("╔═══════════════════════════════════════════════════════════════════");
            log.info("║ ✅ TFL REFRESH COMPLETED | Total Time: {}ms | Modes: {}", totalDuration, modes.length);
            log.info("╚═══════════════════════════════════════════════════════════════════");
        } finally {
            executor.shutdown();
        }
    }

    private void refreshMode(String mode) {
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

            log.info("✅ [{}] Step 1: Received {} arrivals", mode, arrivals.size());

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
}
