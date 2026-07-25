package com.stationly.backend.nationalrail.scheduler;

import com.stationly.backend.nationalrail.service.NationalRailStationMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the identifier indexes current: reloads Darwin's TIPLOC↔CRS reference
 * table and the CRS↔naptan overrides once a day (locations are stable, so daily
 * is ample). Startup population is handled by the listener; this is the ongoing
 * refresh.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "nationalrail", name = "enabled", havingValue = "true")
public class NationalRailReferenceDataScheduler {

    private final NationalRailStationMappingService stationMappingService;

    @Scheduled(cron = "${nationalrail.reference.refresh-cron:0 45 2 * * *}", zone = "Europe/London")
    public void refreshReferenceData() {
        log.info("NR_REF: ⏰ Daily reference-data refresh triggered.");
        stationMappingService.refresh();
    }
}
