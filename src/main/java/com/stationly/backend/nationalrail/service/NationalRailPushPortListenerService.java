package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.Station;
import com.stationly.backend.nationalrail.client.DarwinPushPortClient;
import com.stationly.backend.nationalrail.dto.DarwinScheduleChangeFrame;
import com.stationly.backend.nationalrail.dto.DarwinTrainStatusFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Set;

/**
 * The live path's orchestrator. Connects the Push Port on startup, applies each
 * TS/SC delta to the SQL mirror, and debounces a board rebuild for every
 * covered station the delta impacted. On disconnect it re-pushes every covered
 * station from the mirror (the Push Port never replays; boards converge as
 * fresh deltas arrive) before resuming.
 *
 * Gated by {@code nationalrail.enabled}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "nationalrail", name = "enabled", havingValue = "true")
public class NationalRailPushPortListenerService {

    private final DarwinPushPortClient pushPortClient;
    private final NationalRailDeltaApplier deltaApplier;
    private final NationalRailStationMappingService stationMappingService;
    private final NationalRailCoverageService coverageService;
    private final NationalRailRefreshDebouncer refreshDebouncer;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("NR_STREAM: 🚉 Starting Darwin Push Port listener…");
        stationMappingService.refresh();
        coverageService.refresh();
        pushPortClient.connect(this::onTrainStatus, this::onScheduleChange, this::onDisconnect);
    }

    /** TS delta → mutate SQL → debounce a rebuild for each impacted covered station. */
    private void onTrainStatus(DarwinTrainStatusFrame frame) {
        scheduleAll(deltaApplier.applyTimingUpdate(frame));
    }

    /** SC delta (cancel / re-plan) → mutate SQL → debounce a rebuild for each impacted covered station. */
    private void onScheduleChange(DarwinScheduleChangeFrame frame) {
        scheduleAll(deltaApplier.applyScheduleChange(frame));
    }

    private void scheduleAll(Set<String> impactedNaptanIds) {
        impactedNaptanIds.forEach(refreshDebouncer::schedule);
    }

    /**
     * Gap-fill: the Push Port doesn't replay, so on reconnect re-push every
     * covered station from the mirror and resume; missed deltas converge as new
     * TS arrive (v2: LDBWS re-anchor for an exact catch-up).
     */
    private void onDisconnect() {
        log.warn("NR_STREAM: ⚠️ Push Port disconnected — re-pushing covered stations before reconnect.");
        try {
            coverageService.refresh();
            for (Station station : coverageService.coveredStations()) {
                refreshDebouncer.schedule(station.getNaptanId());
            }
        } catch (Exception e) {
            log.error("NR_STREAM: ❌ Gap-fill failed; reconnecting anyway.", e);
        }
        pushPortClient.connect(this::onTrainStatus, this::onScheduleChange, this::onDisconnect);
    }

    @PreDestroy
    public void stop() {
        pushPortClient.disconnect();
        log.info("NR_STREAM: 🛑 Push Port listener stopped.");
    }
}
