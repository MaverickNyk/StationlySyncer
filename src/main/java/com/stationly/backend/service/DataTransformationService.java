package com.stationly.backend.service;

import com.stationly.backend.model.*;
import com.stationly.backend.service.predictionsources.ArrivalDeparturesData;
import com.stationly.backend.service.predictionsources.PredictionSource;
import com.stationly.backend.service.predictionsources.PredictionSourceFactory;
import com.stationly.backend.service.predictionsources.StationPredictionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DataTransformationService {

    private final ObjectMapper objectMapper;
    private final PredictionSourceFactory predictionSourceFactory;

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
        return transformToStationGroups(arrivals, ArrivalDeparturesData.empty());
    }

    /**
     * @param arrivalDeparturesData resolved departure-board responses for the stations
     *                  planned this cycle (empty for modes without a board
     *                  product). Board-planned stations are processed even
     *                  with zero live arrivals, so a subscribed terminus at a
     *                  quiet hour still gets its real timetable pushed.
     */
    public Map<String, StationPredictions> transformToStationGroups(List<ArrivalPrediction> arrivals, ArrivalDeparturesData arrivalDeparturesData) {
        log.info("🔄 [TRANSFORM] Starting transformation of {} arrivals", arrivals.size());
        Map<String, StationPredictions> stationGroups = new java.util.concurrent.ConcurrentHashMap<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

        // Group by StationId
        Map<String, List<ArrivalPrediction>> byStation = arrivals.stream()
                .filter(a -> a.getNaptanId() != null)
                .collect(Collectors.groupingBy(ArrivalPrediction::getNaptanId));

        Set<String> stationIds = new LinkedHashSet<>(byStation.keySet());
        stationIds.addAll(arrivalDeparturesData.stationIds());

        log.info("🔄 [TRANSFORM] Grouped into {} stations", stationIds.size());

        int arrivalDeparturesRouted = 0;
        // Process each station sequentially to avoid thread exhaustion
        for (String stationId : stationIds) {
            List<ArrivalPrediction> stationArrivals = byStation.getOrDefault(stationId, List.of());
            String stationKey = "Station_" + normalize(stationId);

            ArrivalDeparturesData.StationData board = arrivalDeparturesData.forStation(stationId);
            StationPredictionContext ctx = StationPredictionContext.builder()
                    .stationId(stationId)
                    .arrivals(stationArrivals)
                    .arrivalDeparturesByLine(board != null ? board.entriesByLine() : Map.of())
                    .arrivalDeparturesLineNames(board != null ? board.lineNames() : Map.of())
                    .arrivalDeparturesMode(board != null ? board.mode() : null)
                    .stationCommonName(board != null ? board.commonName() : null)
                    .helpers(this)
                    .build();

            // ONE source per station per cycle — mirror of stationly-backend's
            // PredictionSourceFactory routing.
            PredictionSource source = predictionSourceFactory.forStation(ctx);
            Map<String, LineData> lines = source.buildLines(ctx);
            if (!"tube-dlr-bus-tram-mix".equals(source.name())) {
                arrivalDeparturesRouted++;
                log.info("SYNC: 🔀 {} → {}", stationId, source.name());
            }

            // A board-only station with nothing usable publishes nothing; a
            // station WITH arrivals always publishes, exactly as before —
            // even when every prediction was filtered out.
            if (stationArrivals.isEmpty() && lines.isEmpty()) {
                continue;
            }

            String stationName = !stationArrivals.isEmpty()
                    ? stationArrivals.get(0).getStationName()
                    : (ctx.getStationCommonName() != null ? ctx.getStationCommonName() : stationId);

            StationPredictions station = StationPredictions.builder()
                    .stationId(stationId)
                    .stationName(stationName)
                    .lastUpdatedTime(now)
                    .lines(lines)
                    .build();

            // Dynamic Pruning to fit FCM 4KB limit
            pruneToFitFCM(station);
            if (log.isDebugEnabled() && !"tube-dlr-bus-tram-mix".equals(source.name())) {
                // Validation aid: the exact FCM payload for ArrivalDepartures-
                // served stations (enable via logging.level.com.stationly=DEBUG).
                try {
                    log.debug("SYNC: 📦 {} payload: {}", stationId, objectMapper.writeValueAsString(station));
                } catch (Exception ignored) {
                }
            }
            stationGroups.put(stationKey, station);
        }

        log.info("✅ [TRANSFORM] Completed: {} arrivals → {} station groups{}", arrivals.size(), stationGroups.size(),
                arrivalDeparturesRouted > 0 ? " (" + arrivalDeparturesRouted + " via arrival-departures)" : "");
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

    /**
     * Strip TfL's station-name suffixes for display. Shared by every
     * prediction source so the same destination never renders two different
     * ways depending on which TfL product it came from.
     */
    public String cleanDestinationName(String rawName) {
        if (rawName == null) {
            return null;
        }
        return rawName.replace(" Underground Station", "")
                .replace(" Station", "")
                .replace(" DLR", "")
                .trim();
    }

    public PredictionItem toPredictionItem(ArrivalPrediction arrival) {
        // iBus sends the literal string "null" in `towards` for buses while the
        // real destination sits in destinationName — treat it as absent.
        String towards = arrival.getTowards() != null ? arrival.getTowards().trim() : "";
        String rawName = (!towards.isEmpty() && !towards.equalsIgnoreCase("null"))
                ? towards
                : arrival.getDestinationName();

        rawName = cleanDestinationName(rawName);

        return PredictionItem.builder()
                .destinationNaptanId(arrival.getDestinationNaptanId())
                .platformName(getPresentablePlatform(arrival.getModeName(), arrival.getPlatformName()))
                .expectedArrival(arrival.getExpectedArrival() != null
                        ? arrival.getExpectedArrival().format(DateTimeFormatter.ISO_INSTANT)
                        : null)
                .displayName(rawName)
                .build();
    }

    // TfL only assigns platforms ~5–15 min before departure for these modes;
    // far-future unplatformed predictions from them are noise, not real board data.
    private static final Set<String> LATE_PLATFORM_MODES = Set.of("overground", "dlr", "elizabeth-line");
    public static final long UNASSIGNED_PLATFORM_MAX_MINUTES = 20;

    private static final Set<String> UNASSIGNED_RAW_VALUES = Set.of("null", "unknown", "platform unknown", "no platform");

    /** TfL placeholder strings meaning "no platform assigned yet" (raw value, not the presentable label). */
    public static boolean isUnassignedPlatform(String rawPlatform) {
        String rp = rawPlatform == null ? "" : rawPlatform.trim().toLowerCase();
        return rp.isEmpty() || UNASSIGNED_RAW_VALUES.contains(rp);
    }

    // TfL's expectedArrival is computed from a prediction snapshot that can lag
    // ~40-60s behind real time, so an approaching train routinely shows an
    // expectedArrival slightly in the past while its timeToStation is still
    // positive. 2 minutes is safely beyond that skew: anything older is a
    // genuinely departed train TfL hasn't expired yet, not a live one.
    // Must stay in lockstep with stationly-backend's predictionUtils.ts.
    public static final Duration DEPARTED_CUTOFF = Duration.ofMinutes(2);

    public boolean isLongDeparted(ArrivalPrediction arrival) {
        ZonedDateTime eta = arrival.getExpectedArrival();
        return eta != null && Duration.between(eta, ZonedDateTime.now()).compareTo(DEPARTED_CUTOFF) > 0;
    }

    public boolean isFarFutureUnassigned(ArrivalPrediction arrival) {
        String mode = arrival.getModeName();
        if (mode == null || !LATE_PLATFORM_MODES.contains(mode.toLowerCase())) return false;
        if (!isUnassignedPlatform(arrival.getPlatformName())) return false;
        if (arrival.getExpectedArrival() == null) return true;
        return Duration.between(ZonedDateTime.now(), arrival.getExpectedArrival()).toMinutes() > UNASSIGNED_PLATFORM_MAX_MINUTES;
    }

    public String getPresentablePlatform(String mode, String rawPlatform) {
        boolean isBus = "bus".equalsIgnoreCase(mode);

        if (isUnassignedPlatform(rawPlatform)) {
            // Unassigned bus stop → empty (client renders just the line, no
            // confusing "Stop not assigned"). Rail keeps a presentable label.
            // Must stay in lockstep with stationly-backend formatters.ts.
            return isBus ? "" : "Platform not assigned";
        }

        String p = rawPlatform.trim();

        if (isBus) {
            if (p.toLowerCase().startsWith("stop ")) {
                p = p.substring(5).trim();
            }
            return "Stop " + p.toUpperCase();
        }

        if (p.contains(" - ")) {
            String[] parts = p.split(" - ");
            if (parts.length >= 2) {
                String desc = parts[0].trim();
                String plat = parts[1].trim();
                if (!plat.toLowerCase().startsWith("platform")) {
                    plat = "Platform " + plat;
                }
                return plat + " (" + desc + ")";
            }
        }

        if (p.matches("\\d+")) {
            return "Platform " + p;
        }

        if (p.toLowerCase().matches("^plat \\d+$")) {
            return p.replaceFirst("(?i)^plat ", "Platform ");
        }

        // Short platform code: single letter (Elizabeth "A"/"B", Overground "D")
        // or digit+letter suffix (DLR "4a") — TfL returns these raw without "Platform" prefix
        if (p.matches("[A-Za-z]") || p.matches("\\d+[A-Za-z]+")) {
            return "Platform " + p.toUpperCase();
        }

        return p;
    }
}
