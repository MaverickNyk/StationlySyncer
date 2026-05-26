package com.stationly.backend.scheduler;

import com.stationly.backend.service.StationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class StationSyncScheduler {

    private final StationService stationService;

    @Value("${tfl.transport.modes}")
    private String transportModes;

    // @EventListener(ApplicationReadyEvent.class)
    // public void syncStationOnStart() {
    // scheduleStationSync();
    // }

    // Simplified scheduler: Syncs ALL modes at the scheduled time.
    // Rate limiting is now handled by TflRateLimiter to avoid 429s.
    @Scheduled(cron = "${station.sync.cron}")
    public void scheduleStationSync() {
        log.info("⏰ Triggering scheduled station sync...");

        List<String> allModes = Arrays.stream(transportModes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        log.info("📅 Syncing all configured modes: {}", allModes);
        performSync(allModes);
    }

    private void performSync(List<String> modes) {
        long startTime = System.currentTimeMillis();

        try {
            for (String mode : modes) {
                // Delegate to StationService for optimized, batched processing per mode
                stationService.syncStationsByMode(mode);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Station sync completed in {}ms", duration);

        } catch (Exception e) {
            log.error("💥 Critical error during station sync", e);
        }
    }
}
