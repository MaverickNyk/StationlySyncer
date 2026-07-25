package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.Station;
import com.stationly.backend.model.StationPredictions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Rebuilds one station's board FROM THE SQL MIRROR and pushes it if it changed
 * — the single point both triggers converge on: the Push Port debouncer (on a
 * drift) and the heartbeat sweep (on schedule). No external call here; the
 * board engine is pure SQL. Idempotent and safe to call concurrently for
 * different stations; the change detector guards duplicate pushes per station.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailStationRefreshService {

    private final NationalRailCoverageService coverageService;
    private final NationalRailStationMappingService stationMappingService;
    private final NationalRailBoardEngine boardEngine;
    private final NationalRailBoardChangeDetector changeDetector;
    private final NationalRailPushNotifier pushNotifier;

    @Value("${nationalrail.heartbeat.interval-ms:900000}")
    private long heartbeatMs;

    public void refresh(String naptanId) {
        Optional<Station> stationOpt = coverageService.find(naptanId);
        if (stationOpt.isEmpty()) return; // not a covered station
        Station station = stationOpt.get();

        String crs = (station.getCrs() != null && !station.getCrs().isBlank())
                ? station.getCrs()
                : stationMappingService.crsForNaptanId(naptanId).orElse(null);
        if (crs == null) {
            log.warn("NR_REFRESH: no CRS for {} — cannot build board.", naptanId);
            return;
        }

        StationPredictions board = boardEngine.buildBoard(naptanId, crs, station.getCommonName());
        if (changeDetector.shouldPush(naptanId, board, heartbeatMs)) {
            pushNotifier.push(board);
        }
    }
}
