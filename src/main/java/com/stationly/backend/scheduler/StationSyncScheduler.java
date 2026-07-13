package com.stationly.backend.scheduler;

import com.stationly.backend.service.StationService;
import com.stationly.backend.status.SyncRunRecord;
import com.stationly.backend.status.SyncStatusRecorder;
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
    private final SyncStatusRecorder syncStatusRecorder;

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
        boolean ok = true;
        String error = null;

        try {
            for (String mode : modes) {
                // Delegate to StationService for optimized, batched processing per mode
                stationService.syncStationsByMode(mode);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Station sync completed in {}ms", duration);

        } catch (Exception e) {
            ok = false;
            error = e.getMessage();
            log.error("💥 Critical error during station sync", e);
        } finally {
            long finished = System.currentTimeMillis();
            java.util.Map<String, Object> detail = new java.util.HashMap<>();
            detail.put("modes", modes);
            syncStatusRecorder.record(SyncRunRecord.builder()
                    .jobType(SyncRunRecord.JOB_STATION_SYNC)
                    .startedAt(startTime)
                    .finishedAt(finished)
                    .durationMs(finished - startTime)
                    .status(ok ? SyncRunRecord.OK : SyncRunRecord.FAILED)
                    .modesProcessed(modes.size())
                    .errors(ok ? 0 : 1)
                    .errorMsg(error)
                    .detail(detail)
                    .build());
        }
    }
}
