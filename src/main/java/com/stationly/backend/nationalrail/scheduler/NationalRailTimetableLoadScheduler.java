package com.stationly.backend.nationalrail.scheduler;

import com.stationly.backend.nationalrail.service.NationalRailTimetableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Loads the daily National Rail timetable baseline into the SQL mirror: the
 * full morning sync at 03:00 Europe/London, and once on startup so a mid-day
 * (re)deploy always has today's schedule to correct against. The reference
 * TIPLOC↔CRS table refreshes just before (02:45) so ingest can resolve stations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "nationalrail", name = "enabled", havingValue = "true")
public class NationalRailTimetableLoadScheduler {

    private final NationalRailTimetableService timetableService;

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        log.info("NR_TT: ⏳ Startup baseline check…");
        timetableService.loadBaselineForToday();
    }

    @Scheduled(cron = "${nationalrail.timetable.load-cron:0 0 3 * * *}", zone = "Europe/London")
    public void loadDaily() {
        log.info("NR_TT: ⏰ Daily 03:00 baseline sync triggered.");
        timetableService.loadBaselineForToday();
    }
}
