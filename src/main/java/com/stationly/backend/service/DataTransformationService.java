package com.stationly.backend.service;

import com.stationly.backend.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DataTransformationService {

    private final ObjectMapper objectMapper;

    private String normalize(String input) {
        if (input == null)
            return "";
        // Replace spaces and other non-FCM-friendly characters with ~
        return input.toUpperCase().replaceAll("[^A-Z0-9-_.~%]", "~");
    }

    /**
      * Transform TfL arrivals into grouped Station objects
      * Key pattern: "Station_<stationId>"
      *
      * @param arrivals Raw TfL arrival predictions
      * @return Map with key pattern "Station_<stationId>" and StationPredictions as
      *         value
      */
    public Map<String, StationPredictions> transformToStationGroups(List<ArrivalPrediction> arrivals) {
        log.info("🔄 [TRANSFORM] Starting transformation of {} arrivals", arrivals.size());
        Map<String, StationPredictions> stationGroups = new java.util.concurrent.ConcurrentHashMap<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

        // Group by StationId
        Map<String, List<ArrivalPrediction>> byStation = arrivals.stream()
                .filter(a -> a.getNaptanId() != null)
                .collect(Collectors.groupingBy(ArrivalPrediction::getNaptanId));

        log.info("🔄 [TRANSFORM] Grouped into {} stations", byStation.size());

        // Process each station sequentially to avoid thread exhaustion
        byStation.entrySet().forEach(entry -> {
            String stationId = entry.getKey();
            List<ArrivalPrediction> stationArrivals = entry.getValue();
            String stationKey = "Station_" + normalize(stationId);

            // Create StationPredictions
            StationPredictions station = StationPredictions.builder()
                    .stationId(stationId)
                    .stationName(stationArrivals.get(0).getStationName())
                    .lastUpdatedTime(now)
                    .lines(new HashMap<>())
                    .build();

            // Group arrivals by LineId
            Map<String, List<ArrivalPrediction>> byLine = stationArrivals.stream()
                    .filter(a -> a.getLineId() != null)
                    .collect(Collectors.groupingBy(ArrivalPrediction::getLineId));

            byLine.forEach((lineId, lineArrivals) -> {
                LineData lineData = station.getLines().computeIfAbsent(lineId, k -> LineData.builder()
                        .lineId(lineId)
                        .lineName(lineArrivals.get(0).getLineName())
                        .directions(new HashMap<>())
                        .build());

                Map<String, List<ArrivalPrediction>> byDirection = lineArrivals.stream()
                        .filter(a -> a.getDirection() != null)
                        .collect(Collectors.groupingBy(ArrivalPrediction::getDirection));

                byDirection.forEach((direction, directionArrivals) -> {
                    List<PredictionItem> items = directionArrivals.stream()
                            .map(this::toPredictionItem)
                            .sorted(Comparator.comparing(PredictionItem::getExpectedArrival,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                            .limit(10) // Higher initial limit, pruning will handle safety
                            .collect(Collectors.toList());

                    if (!items.isEmpty()) {
                        DirectionPredictions directionPredictions = DirectionPredictions.builder()
                                .predictions(items)
                                .build();
                        lineData.getDirections().put(direction, directionPredictions);
                    }
                });
            });

            // Dynamic Pruning to fit FCM 4KB limit
            pruneToFitFCM(station);
            stationGroups.put(stationKey, station);
        });

        log.info("✅ [TRANSFORM] Completed: {} arrivals → {} station groups", arrivals.size(), stationGroups.size());
        return stationGroups;
    }

    /**
     * Dynamically prunes predictions from a station object until its serialized
     * size
     * is under 4000 bytes (to safely fit in FCM 4096 byte data limit).
     */
    private void pruneToFitFCM(StationPredictions station) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(station);
            if (bytes.length <= 4000) {
                return;
            }

            log.info("⚠️ Station {} exceeds 4000 bytes ({}). Pruning predictions...",
                    station.getStationName(), bytes.length);

            // Pruning strategy: Repeatedly remove the latest prediction from the direction
            // with the most total predictions until we are under the limit.
            while (bytes.length > 4000) {
                PredictionItem furthestPrediction = null;
                DirectionPredictions targetGroup = null;

                // Find the prediction furthest in time across all lines and directions
                for (LineData line : station.getLines().values()) {
                    for (DirectionPredictions dp : line.getDirections().values()) {
                        if (dp.getPredictions() != null && !dp.getPredictions().isEmpty()) {
                            PredictionItem last = dp.getPredictions().get(dp.getPredictions().size() - 1);
                            if (furthestPrediction == null ||
                                    (last.getExpectedArrival() != null
                                            && furthestPrediction.getExpectedArrival() != null &&
                                            last.getExpectedArrival()
                                                    .compareTo(furthestPrediction.getExpectedArrival()) > 0)) {
                                furthestPrediction = last;
                                targetGroup = dp;
                            }
                        }
                    }
                }

                if (targetGroup != null && furthestPrediction != null) {
                    targetGroup.getPredictions().remove(furthestPrediction);
                    bytes = objectMapper.writeValueAsBytes(station);
                } else {
                    // Cannot prune further
                    break;
                }
            }

            log.info("✂️ Pruned station {} to {} bytes", station.getStationName(), bytes.length);

        } catch (Exception e) {
            log.warn("Failed to prune station {}: {}", station.getStationName(), e.getMessage());
        }
    }

    private PredictionItem toPredictionItem(ArrivalPrediction arrival) {
        String rawName = (arrival.getTowards() != null && !arrival.getTowards().isEmpty())
                ? arrival.getTowards()
                : arrival.getDestinationName();

        if (rawName != null) {
            rawName = rawName.replace(" Underground Station", "")
                    .replace(" Station", "")
                    .replace(" DLR", "")
                    .trim();
        }

        return PredictionItem.builder()
                .destinationNaptanId(arrival.getDestinationNaptanId())
                .platformName(getPresentablePlatform(arrival.getModeName(), arrival.getPlatformName()))
                .expectedArrival(arrival.getExpectedArrival() != null
                        ? arrival.getExpectedArrival().format(DateTimeFormatter.ISO_INSTANT)
                        : null)
                .displayName(rawName)
                .build();
    }

    public String getPresentablePlatform(String mode, String rawPlatform) {
        // 1. Handle Null or Missing Data
        if (rawPlatform == null || rawPlatform.equalsIgnoreCase("null") || rawPlatform.trim().isEmpty()) {
            return "bus".equalsIgnoreCase(mode) ? "No Stop assigned" : "No Platform assigned";
        }

        rawPlatform = rawPlatform.trim();

        // 2. Format Tube/Train descriptive strings (e.g., "Westbound - Platform 2")
        if (rawPlatform.contains(" - ")) {
            String[] parts = rawPlatform.split(" - ");
            if (parts.length == 2) {
                // Re-orders to "Platform 2 (Westbound)"
                return parts[1] + " (" + parts[0] + ")";
            }
        }

        // 3. Format Bus Stop letters (e.g., "BP" -> "Stop BP")
        if ("bus".equalsIgnoreCase(mode)) {
            return "Stop " + rawPlatform.toUpperCase();
        }

        // 4. Format Numeric platforms (e.g., "1" -> "Platform 1")
        if (rawPlatform.matches("\\d+")) {
            return "Platform " + rawPlatform;
        }

        // Fallback for anything already formatted
        return rawPlatform;
    }
}
