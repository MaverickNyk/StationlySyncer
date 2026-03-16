package com.stationly.backend.service;

import com.stationly.backend.client.TflApiClient;
import com.stationly.backend.model.ArrivalPrediction;
import com.stationly.backend.model.RefreshSummary;
import com.stationly.backend.model.StationPredictions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
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
         * Refresh all configured transport modes
         *
         * @return List of summaries for each mode
         */
        public List<RefreshSummary> refreshAll() {
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
                        // Process all modes in parallel
                        log.info("📋 Processing {} modes in parallel: {}", modes.length, Arrays.toString(modes));
                        List<CompletableFuture<RefreshSummary>> futures = Arrays.stream(modes)
                                        .map(String::trim)
                                        .filter(mode -> !mode.isEmpty())
                                        .map(mode -> CompletableFuture.supplyAsync(() -> refreshMode(mode), executor))
                                        .collect(Collectors.toList());

                        // Wait for all modes to complete and gather summaries
                        List<RefreshSummary> summaries = futures.stream()
                                        .map(CompletableFuture::join)
                                        .collect(Collectors.toList());

                        long totalDuration = System.currentTimeMillis() - startMillis;
                        monitoringService.recordPollingDuration("total", totalDuration, "SUCCESS");
                        log.info("╔═══════════════════════════════════════════════════════════════════");
                        log.info("║ ✅ TFL REFRESH COMPLETED | Total Time: {}ms | Modes Processed: {}", totalDuration, summaries.size());
                        log.info("╚═══════════════════════════════════════════════════════════════════");
                        
                        // Log summary for each mode
                        for (RefreshSummary summary : summaries) {
                                log.info("   📊 Mode: {} | Status: {} | Arrivals: {} | FCM: {} | Time: {}ms",
                                                summary.getMode(), summary.getStatus(),
                                                summary.getArrivalsReceived(), summary.getFcmTopicsPublished(),
                                                summary.getProcessingTimeMs());
                        }
                        
                        return summaries;
                } finally {
                        executor.shutdown();
                }
        }

        /**
          * Manually refresh data for a specific mode
          *
          * @param mode Transport mode (tube, dlr, bus, etc.)
          * @return Summary of the refresh operation
          */
        public RefreshSummary refreshMode(String mode) {
                String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
                LocalDateTime startTime = LocalDateTime.now();
                long startMillis = System.currentTimeMillis();

                log.info("┌───────────────────────────────────────────────────────────────────");
                log.info("│ 🚇 POLLING MODE: {} | Time: {}", mode.toUpperCase(), timestamp);
                log.info("└───────────────────────────────────────────────────────────────────");

                try {
                        // Fetch arrivals from TfL API
                        log.info("📡 [{}] Step 1: Fetching arrivals from TfL API...", mode);
                        List<ArrivalPrediction> arrivals = tflApiClient.getArrivalsByMode(mode);

                        if (arrivals == null || arrivals.isEmpty()) {
                                long duration = System.currentTimeMillis() - startMillis;
                                log.warn("⚠️  [{}] STATUS: NO DATA | No arrivals received | Took: {}ms", mode, duration);

                                monitoringService.recordPollingDuration(mode, duration, "NO_DATA");
                                return RefreshSummary.builder()
                                                .mode(mode)
                                                .timestamp(startTime)
                                                .status("NO_DATA")
                                                .arrivalsReceived(0)
                                                .cacheKeysCreated(0)
                                                .fcmTopicsPublished(0)
                                                .ttlSeconds(0L)
                                                .processingTimeMs(duration)
                                                .message("No arrivals received from TfL API for mode: " + mode)
                                                .build();
                        }

                        log.info("✅ [{}] Step 1: Received {} arrivals from TfL API", mode, arrivals.size());

                        // Transform into grouped Station objects
                        log.info("🔄 [{}] Step 2: Transforming data into station-centric groups...", mode);
                        Map<String, StationPredictions> groupedStations = transformationService
                                        .transformToStationGroups(arrivals);
                        log.info("✅ [{}] Step 2: Transformed into {} station groups", mode, groupedStations.size());

                        // Publish to FCM in batch - Only if data changed
                        log.info("⚡ [{}] Step 3: Performing modular change detection...", mode);
                        
                        // 1. Detect changes & heartbeats
                        Map<String, Object> fcmData = changeDetectionService.getChangedStations(mode, groupedStations);
                        
                        // 2. Detect and handle vanished stations (Wipes)
                        changeDetectionService.detectAndAddWipes(mode, arrivals.size(), groupedStations, fcmData);

                        int fcmCount = fcmData.size();
                        if (fcmCount > 0) {
                                log.info("⚡ [{}] Step 3: Change detection found {}/{} stations targets. Publishing...", mode, fcmCount, groupedStations.size());
                                fcmService.publishAll(fcmData);
                                log.info("✅ [{}] Step 3: Queued {} FCM messages", mode, fcmCount);
                        } else {
                                log.info("✅ [{}] Step 3: 0/{} stations changed. Skipping FCM publish.", mode, groupedStations.size());
                        }

                        long duration = System.currentTimeMillis() - startMillis;
                        log.info("┌───────────────────────────────────────────────────────────────────");
                        log.info("│ ✅ [{}] SUMMARY: {} arrivals → {} station keys → {} FCM topics | Time: {}ms",
                                        mode, arrivals.size(), groupedStations.size(), fcmCount, duration);
                        log.info("└───────────────────────────────────────────────────────────────────");

                        monitoringService.recordPollingDuration(mode, duration, "SUCCESS");
                        monitoringService.recordArrivalsCount(mode, arrivals.size());

                        return RefreshSummary.builder()
                                        .mode(mode)
                                        .timestamp(startTime)
                                        .status("SUCCESS")
                                        .arrivalsReceived(arrivals.size())
                                        .cacheKeysCreated(groupedStations.size())
                                        .fcmTopicsPublished(fcmCount)
                                        .ttlSeconds(0L)
                                        .processingTimeMs(duration)
                                        .message(String.format(
                                                        "Successfully processed %d arrivals into %d station keys",
                                                        arrivals.size(), groupedStations.size()))
                                        .build();

                } catch (Exception e) {
                        long duration = System.currentTimeMillis() - startMillis;
                        log.error("❌ [{}] STATUS: FAILED | Error during polling | Took: {}ms | Error: {}",
                                        mode, duration, e.getMessage());

                        monitoringService.recordPollingDuration(mode, duration, "FAILED");

                        return RefreshSummary.builder()
                                        .mode(mode)
                                        .timestamp(startTime)
                                        .status("FAILED")
                                        .arrivalsReceived(0)
                                        .cacheKeysCreated(0)
                                        .fcmTopicsPublished(0)
                                        .ttlSeconds(0L)
                                        .processingTimeMs(duration)
                                        .message("Error during polling: " + e.getMessage())
                                        .build();
                }
        }
}
