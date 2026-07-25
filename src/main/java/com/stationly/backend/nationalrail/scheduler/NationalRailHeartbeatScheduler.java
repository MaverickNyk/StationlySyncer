package com.stationly.backend.nationalrail.scheduler;

import com.stationly.backend.model.Station;
import com.stationly.backend.nationalrail.service.NationalRailCoverageService;
import com.stationly.backend.nationalrail.service.NationalRailRefreshDebouncer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety-net freshness sweep. Drift is the real-time path; this bounds
 * worst-case staleness AND ages out departed trains for stations the Push Port
 * has gone quiet on. Refreshes every covered station through the SAME debounced
 * path drift uses (coalescing with any in-flight rebuild); the board change
 * detector then pushes only genuinely-changed boards, plus a forced heartbeat
 * push once its window elapses.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "nationalrail", name = "enabled", havingValue = "true")
public class NationalRailHeartbeatScheduler {

    private final NationalRailCoverageService coverageService;
    private final NationalRailRefreshDebouncer refreshDebouncer;

    @Scheduled(fixedDelayString = "${nationalrail.heartbeat.interval-ms:900000}")
    public void sweep() {
        coverageService.refresh();
        var covered = coverageService.coveredStations();
        if (covered.isEmpty()) return;
        for (Station station : covered) {
            refreshDebouncer.schedule(station.getNaptanId());
        }
        log.info("NR_HEARTBEAT: 💓 Swept {} covered station(s).", covered.size());
    }
}
