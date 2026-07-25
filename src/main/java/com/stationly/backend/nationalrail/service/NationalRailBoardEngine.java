package com.stationly.backend.nationalrail.service;

import com.stationly.backend.model.DirectionPredictions;
import com.stationly.backend.model.LineData;
import com.stationly.backend.model.PredictionItem;
import com.stationly.backend.model.StationPredictions;
import com.stationly.backend.nationalrail.dto.NationalRailBoardDeparture;
import com.stationly.backend.nationalrail.policy.NationalRailBoardKeys;
import com.stationly.backend.nationalrail.repository.NationalRailScheduleRepository;
import com.stationly.backend.nationalrail.util.NationalRailTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes a station's board straight from the SQL mirror — the "return the
 * next N departures from station X at the current time" engine. Pure local
 * query + shape into {@link StationPredictions} (== the app's FcmPayload); NO
 * external call, so the heartbeat can run it for every covered station cheaply.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NationalRailBoardEngine {

    /** Small grace so a just-due train doesn't vanish the instant its time passes. */
    private static final long PAST_GRACE_MS = 60_000;

    private final NationalRailScheduleRepository repository;
    private final NationalRailStationMappingService stationMappingService;
    private final NationalRailBoardKeys boardKeys;

    @Value("${nationalrail.board.max-departures:30}")
    private int maxDepartures;

    public StationPredictions buildBoard(String naptanId, String crs, String stationName) {
        LocalDate today = NationalRailTime.today();
        long fromMs = NationalRailTime.nowMs() - PAST_GRACE_MS;

        List<NationalRailBoardDeparture> departures = repository.queryBoard(crs, today, fromMs, maxDepartures);

        // lineId -> direction -> preds (SQL already ordered by effective time).
        Map<String, Map<String, java.util.List<PredictionItem>>> byLineDir = new LinkedHashMap<>();
        Map<String, String> lineNames = new LinkedHashMap<>();

        for (NationalRailBoardDeparture dep : departures) {
            String lineId = boardKeys.lineIdFor(dep);
            String direction = boardKeys.directionFor(dep);
            lineNames.putIfAbsent(lineId, boardKeys.lineNameFor(dep));

            byLineDir
                    .computeIfAbsent(lineId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(direction, k -> new java.util.ArrayList<>())
                    .add(PredictionItem.builder()
                            .destinationNaptanId(resolveDestId(dep.getDestinationCrs()))
                            .platformName(formatPlatform(dep.getPlatform()))
                            .expectedArrival(NationalRailTime.toIso(dep.getEffectiveDepartureMs()))
                            .displayName(cleanDestination(dep.getDestinationName()))
                            .build());
        }

        Map<String, LineData> lines = new LinkedHashMap<>();
        byLineDir.forEach((lineId, dirs) -> {
            Map<String, DirectionPredictions> dirMap = new LinkedHashMap<>();
            dirs.forEach((dir, preds) -> dirMap.put(dir, DirectionPredictions.builder().predictions(preds).build()));
            lines.put(lineId, LineData.builder()
                    .lineId(lineId)
                    .lineName(lineNames.getOrDefault(lineId, lineId))
                    .directions(dirMap)
                    .build());
        });

        return StationPredictions.builder()
                .stationId(naptanId)
                .stationName(stationName != null ? stationName : "")
                .lastUpdatedTime(NationalRailTime.toIso(NationalRailTime.nowMs()))
                .lines(lines)
                .build();
    }

    private String resolveDestId(String destinationCrs) {
        if (destinationCrs == null) return "unknown";
        return stationMappingService.naptanIdForCrs(destinationCrs).orElse("unknown");
    }

    private String formatPlatform(String platform) {
        if (platform == null || platform.isBlank()) return ""; // app treats "" as unassigned
        return "Platform " + platform.trim();
    }

    private String cleanDestination(String name) {
        if (name == null) return "";
        return name.replaceAll("(?i)\\s+rail station$", "").trim();
    }
}
