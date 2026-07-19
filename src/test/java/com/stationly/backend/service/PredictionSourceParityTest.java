package com.stationly.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stationly.backend.model.ArrivalDeparture;
import com.stationly.backend.model.ArrivalPrediction;
import com.stationly.backend.model.DirectionPredictions;
import com.stationly.backend.model.LineData;
import com.stationly.backend.model.PredictionItem;
import com.stationly.backend.model.StationPredictions;
import com.stationly.backend.service.predictionsources.ArrivalDeparturesData;
import com.stationly.backend.service.predictionsources.TubeDlrBusTramMixPredictionSource;
import com.stationly.backend.service.predictionsources.ElizabethOvergroundPredictionSource;
import com.stationly.backend.service.predictionsources.PredictionSourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-regression contract for the prediction-source factory refactor:
 *
 * 1. PARITY — for countdown-only input (no board data) the new pipeline's
 *    payloads must be IDENTICAL to the pre-factory transform (kept verbatim
 *    below as LegacyTransform).
 * 2. POLICY — board-served stations drop Cancelled/NotStoppingAtStation
 *    trains, keep Delayed trains at their updated time, and replace
 *    "Check Front of Train" with real departures.
 * 3. SAFETY — an empty/failed board falls back per-line to countdown rows,
 *    and mixed-direction platform labels never flip a destination's bucket
 *    (the Romford Platform 5 case).
 */
class PredictionSourceParityTest {

    /** No route data: resolveDepartingDirection always null (deterministic; no Mockito — inline mocks don't support this JDK). */
    private static final class NullRouteDirectionResolver extends RouteDirectionResolver {
        NullRouteDirectionResolver(ObjectMapper mapper) {
            super(null, null, mapper);
        }

        @Override
        public String resolveDepartingDirection(String naptanId, String lineId) {
            return null;
        }
    }

    private ObjectMapper mapper;
    private RouteDirectionResolver resolver;
    private DataTransformationService newPipeline;
    private LegacyTransform legacy;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        resolver = new NullRouteDirectionResolver(mapper);
        TubeDlrBusTramMixPredictionSource countdown = new TubeDlrBusTramMixPredictionSource(resolver);
        ElizabethOvergroundPredictionSource board = new ElizabethOvergroundPredictionSource(resolver, countdown);
        newPipeline = new DataTransformationService(mapper, new PredictionSourceFactory(board, countdown));
        legacy = new LegacyTransform(mapper, resolver);
    }

    // ---------------------------------------------------------------- fixtures

    private static ArrivalPrediction arrival(String naptanId, String stationName, String lineId, String lineName,
                                             String mode, String platform, String direction,
                                             String destId, String destName, String towards, ZonedDateTime eta) {
        ArrivalPrediction a = new ArrivalPrediction();
        a.setNaptanId(naptanId);
        a.setStationName(stationName);
        a.setLineId(lineId);
        a.setLineName(lineName);
        a.setModeName(mode);
        a.setPlatformName(platform);
        a.setDirection(direction);
        a.setDestinationNaptanId(destId);
        a.setDestinationName(destName);
        a.setTowards(towards);
        a.setExpectedArrival(eta);
        return a;
    }

    private static ArrivalDeparture boardEntry(String platform, String destId, String destName,
                                               String status, ZonedDateTime estimated, ZonedDateTime scheduled) {
        ArrivalDeparture e = new ArrivalDeparture();
        e.setPlatformName(platform);
        e.setDestinationNaptanId(destId);
        e.setDestinationName(destName);
        e.setDepartureStatus(status);
        e.setEstimatedTimeOfDeparture(estimated);
        e.setScheduledTimeOfDeparture(scheduled);
        return e;
    }

    /** Countdown-only fixture exercising every legacy filter and quirk. */
    private List<ArrivalPrediction> countdownFixture(ZonedDateTime now) {
        List<ArrivalPrediction> arrivals = new ArrayList<>();
        // Tube station: normal rows, a null-direction row, a long-departed row.
        arrivals.add(arrival("940GZZLUOXC", "Oxford Circus Underground Station", "victoria", "Victoria",
                "tube", "Northbound - Platform 3", null, "940GZZLUWWL", "Walthamstow Central Underground Station", null, now.plusMinutes(2)));
        arrivals.add(arrival("940GZZLUOXC", "Oxford Circus Underground Station", "victoria", "Victoria",
                "tube", "Southbound - Platform 4", "inbound", "940GZZLUBXN", "Brixton Underground Station", "Brixton", now.plusMinutes(4)));
        arrivals.add(arrival("940GZZLUOXC", "Oxford Circus Underground Station", "victoria", "Victoria",
                "tube", "Southbound - Platform 4", "inbound", "940GZZLUBXN", "Brixton Underground Station", "Brixton", now.minusMinutes(3))); // long-departed → dropped
        // Bus stop: literal "null" towards, unassigned platform.
        arrivals.add(arrival("490000036R", "Camden Town Station", "24", "24",
                "bus", null, null, "490000254W", "Pimlico Bus Station", "null", now.plusMinutes(6)));
        // XR terminus: self-terminating (→ CFT) + far-future unplatformed (→ dropped).
        arrivals.add(arrival("910GABWDXR", "Abbey Wood", "elizabeth", "Elizabeth line",
                "elizabeth-line", "Platform 4", null, "910GABWDXR", "Abbey Wood", null, now.plusMinutes(5)));
        arrivals.add(arrival("910GABWDXR", "Abbey Wood", "elizabeth", "Elizabeth line",
                "elizabeth-line", null, "outbound", "910GRDNGSTN", "Reading Rail Station", null, now.plusMinutes(25))); // far-future unassigned → dropped
        return arrivals;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void countdownOnlyOutputIsByteIdenticalToLegacyTransform() throws Exception {
        ZonedDateTime now = ZonedDateTime.now();
        // Legacy mutates arrivals in place (CFT relabelling) — feed each
        // pipeline its own copy of identical fixtures.
        Map<String, StationPredictions> legacyOut = legacy.transformToStationGroups(countdownFixture(now));
        Map<String, StationPredictions> newOut = newPipeline.transformToStationGroups(countdownFixture(now));

        assertEquals(legacyOut.keySet(), newOut.keySet(), "station key sets must match");
        for (String key : legacyOut.keySet()) {
            assertEquals(
                    withoutLut(legacyOut.get(key)),
                    withoutLut(newOut.get(key)),
                    "payload for " + key + " must be identical to the pre-factory transform");
        }
    }

    /** Serialize minus `lut` — each pipeline stamps its own now(); everything else must match exactly. */
    private com.fasterxml.jackson.databind.JsonNode withoutLut(StationPredictions station) {
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(mapper.writeValueAsString(station));
            node.remove("lut");
            return node;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void cancelledBoardTrainsAreNeverReturned() {
        ZonedDateTime now = ZonedDateTime.now();
        List<ArrivalDeparture> board = List.of(
                boardEntry("Platform A", "910GSHENFLD", "Shenfield Rail Station", "OnTime", now.plusMinutes(5), now.plusMinutes(5)),
                boardEntry("Platform A", "910GHTRWTM4", "Heathrow Terminal 4 Rail Station", "Cancelled", null, now.plusMinutes(12)),
                boardEntry("Platform A", "910GRDNGSTN", "Reading Rail Station", "NotStoppingAtStation", now.plusMinutes(15), now.plusMinutes(15)));

        StationPredictions station = transformBoardStation("910GFRNDXR", "elizabeth", board, List.of());

        List<String> shown = allDisplayNames(station);
        assertTrue(shown.contains("Shenfield Rail"), "runnable train must be shown");
        assertFalse(shown.contains("Heathrow Terminal 4 Rail"), "Cancelled train must never be returned");
        assertFalse(shown.contains("Reading Rail"), "NotStoppingAtStation train must never be returned");
    }

    @Test
    void delayedBoardTrainsSurviveTheDepartedCutoffAtTheirUpdatedTime() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime stale = now.minusMinutes(4); // beyond the 2-min cutoff
        List<ArrivalDeparture> board = List.of(
                boardEntry("Platform A", "910GSHENFLD", "Shenfield Rail Station", "Delayed", stale, stale),
                boardEntry("Platform A", "910GPADTON", "London Paddington Rail Station", "OnTime", stale, stale));

        StationPredictions station = transformBoardStation("910GFRNDXR", "elizabeth", board, List.of());

        List<String> shown = allDisplayNames(station);
        assertTrue(shown.contains("Shenfield Rail"), "Delayed train is TfL-curated — keep it");
        assertFalse(shown.contains("London Paddington Rail"), "long-departed OnTime train must be dropped");
        String eta = allItems(station).stream()
                .filter(i -> "Shenfield Rail".equals(i.getDisplayName()))
                .findFirst().orElseThrow().getExpectedArrival();
        assertEquals(stale.format(DateTimeFormatter.ISO_INSTANT), eta, "Delayed row carries its (updated) board time");
    }

    @Test
    void terminusCheckFrontOfTrainIsReplacedByRealDepartures() {
        ZonedDateTime now = ZonedDateTime.now();
        // Countdown sees only the inbound working (destination = this station).
        List<ArrivalPrediction> arrivals = List.of(
                arrival("910GABWDXR", "Abbey Wood", "elizabeth", "Elizabeth line",
                        "elizabeth-line", "Platform 4", null, "910GABWDXR", "Abbey Wood", null, now.plusMinutes(4)));
        // The board carries the return working: true destination + departure time.
        List<ArrivalDeparture> board = List.of(
                boardEntry("Platform 4", "910GPADTON", "London Paddington Rail Station", "OnTime", now.plusMinutes(9), now.plusMinutes(9)));

        StationPredictions station = transformBoardStation("910GABWDXR", "elizabeth", board, arrivals);

        List<String> shown = allDisplayNames(station);
        assertTrue(shown.contains("London Paddington Rail"), "real departure must replace CFT");
        assertFalse(shown.contains("Check Front of Train"), "board-served line must not show CFT");
    }

    @Test
    void emptyBoardFallsBackToExactCountdownRows() throws Exception {
        ZonedDateTime now = ZonedDateTime.now();
        Map<String, StationPredictions> legacyOut = legacy.transformToStationGroups(countdownFixture(now));

        // Same countdown fixtures, but Abbey Wood was board-planned and its
        // board came back empty (late-night gap / endpoint failure).
        ArrivalDeparturesData arrivalDeparturesData = new ArrivalDeparturesData(Map.of(
                "910GABWDXR", new ArrivalDeparturesData.StationData("elizabeth-line", "Abbey Wood",
                        Map.of("elizabeth", List.of()), Map.of("elizabeth", "Elizabeth line"))));
        Map<String, StationPredictions> newOut = newPipeline.transformToStationGroups(countdownFixture(now), arrivalDeparturesData);

        assertEquals(
                withoutLut(legacyOut.get("Station_910GABWDXR")),
                withoutLut(newOut.get("Station_910GABWDXR")),
                "empty board must degrade to the exact pre-factory payload");
    }

    @Test
    void mixedDirectionPlatformLabelsNeverFlipADestinationsBucket() {
        ZonedDateTime now = ZonedDateTime.now();
        // Romford case: SAME physical platform, TfL labels Gidea Park workings
        // inbound and Shenfield trains outbound in the countdown feed.
        List<ArrivalPrediction> arrivals = List.of(
                arrival("910GROMFORD", "Romford", "elizabeth", "Elizabeth line",
                        "elizabeth-line", "Platform 5", "inbound", "910GGIDEAPK", "Gidea Park Rail Station", null, now.plusMinutes(3)),
                arrival("910GROMFORD", "Romford", "elizabeth", "Elizabeth line",
                        "elizabeth-line", "Platform 5", "outbound", "910GSHENFLD", "Shenfield Rail Station", null, now.plusMinutes(6)));
        List<ArrivalDeparture> board = List.of(
                boardEntry("Platform 5", "910GSHENFLD", "Shenfield Rail Station", "OnTime", now.plusMinutes(6), now.plusMinutes(6)),
                boardEntry("Platform 5", "910GGIDEAPK", "Gidea Park Rail Station", "OnTime", now.plusMinutes(3), now.plusMinutes(3)));

        StationPredictions station = transformBoardStation("910GROMFORD", "elizabeth", board, arrivals);

        Map<String, DirectionPredictions> dirs = station.getLines().get("elizabeth").getDirections();
        assertNotNull(dirs.get("outbound"), "Shenfield keeps prod's outbound bucket");
        assertTrue(dirs.get("outbound").getPredictions().stream().anyMatch(p -> "Shenfield Rail".equals(p.getDisplayName())));
        assertNotNull(dirs.get("inbound"), "Gidea Park keeps prod's inbound bucket");
        assertTrue(dirs.get("inbound").getPredictions().stream().anyMatch(p -> "Gidea Park Rail".equals(p.getDisplayName())));
    }

    @Test
    void farFutureUnplatformedBoardRowsAreKept() {
        // The 20-min unassigned-platform rule is countdown noise filtering;
        // board rows are curated timetable and must survive it (tfl.gov.uk
        // shows them — verified live at Hackney Central / Highbury).
        ZonedDateTime now = ZonedDateTime.now();
        List<ArrivalDeparture> board = List.of(
                boardEntry(null, "910GCLPHMJC", "Clapham Junction Rail Station", "OnTime", now.plusMinutes(26), now.plusMinutes(26)));

        StationPredictions station = transformBoardStation("910GHACKNYC", "mildmay", board, List.of());

        assertTrue(allDisplayNames(station).contains("Clapham Junction Rail"),
                "far-future unplatformed BOARD row must be kept");
    }

    @Test
    void transientTflDuplicateRowsAreCollapsed() {
        ZonedDateTime now = ZonedDateTime.now();
        ArrivalDeparture once = boardEntry("Platform 4", "910GRDNGSTN", "Reading Rail Station", "OnTime", now.plusMinutes(5), now.plusMinutes(5));
        ArrivalDeparture twice = boardEntry("Platform 4", "910GRDNGSTN", "Reading Rail Station", "OnTime", now.plusMinutes(5), now.plusMinutes(5));

        StationPredictions station = transformBoardStation("910GABWDXR", "elizabeth", List.of(once, twice), List.of());

        long readingRows = allDisplayNames(station).stream().filter("Reading Rail"::equals).count();
        assertEquals(1, readingRows, "TfL's transient duplicate emissions must collapse to one row");
    }

    // ---------------------------------------------------------------- helpers

    private StationPredictions transformBoardStation(String naptanId, String lineId,
                                                     List<ArrivalDeparture> board, List<ArrivalPrediction> arrivals) {
        ArrivalDeparturesData arrivalDeparturesData = new ArrivalDeparturesData(Map.of(
                naptanId, new ArrivalDeparturesData.StationData("elizabeth-line", "Test Station",
                        Map.of(lineId, board), Map.of(lineId, "Elizabeth line"))));
        Map<String, StationPredictions> out = newPipeline.transformToStationGroups(new ArrayList<>(arrivals), arrivalDeparturesData);
        StationPredictions station = out.get("Station_" + naptanId);
        assertNotNull(station, "board station must be present in the output");
        return station;
    }

    private static List<PredictionItem> allItems(StationPredictions station) {
        return station.getLines().values().stream()
                .flatMap(l -> l.getDirections().values().stream())
                .flatMap(d -> d.getPredictions().stream())
                .collect(Collectors.toList());
    }

    private static List<String> allDisplayNames(StationPredictions station) {
        return allItems(station).stream().map(PredictionItem::getDisplayName).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------
    // VERBATIM copy of DataTransformationService.transformToStationGroups (and
    // the private helpers it used) as of the commit BEFORE the factory
    // refactor — the parity oracle. Do not "improve" this code.
    // ------------------------------------------------------------------------
    static final class LegacyTransform {

        private final ObjectMapper objectMapper;
        private final RouteDirectionResolver routeDirectionResolver;

        LegacyTransform(ObjectMapper objectMapper, RouteDirectionResolver routeDirectionResolver) {
            this.objectMapper = objectMapper;
            this.routeDirectionResolver = routeDirectionResolver;
        }

        private static final String CHECK_FRONT_OF_TRAIN = "Check Front of Train";

        private String normalize(String input) {
            if (input == null)
                return "";
            return input.toUpperCase().replaceAll("[^A-Z0-9-_.~%]", "~");
        }

        public Map<String, StationPredictions> transformToStationGroups(List<ArrivalPrediction> arrivals) {
            Map<String, StationPredictions> stationGroups = new java.util.concurrent.ConcurrentHashMap<>();
            String now = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

            Map<String, List<ArrivalPrediction>> byStation = arrivals.stream()
                    .filter(a -> a.getNaptanId() != null)
                    .collect(Collectors.groupingBy(ArrivalPrediction::getNaptanId));

            byStation.entrySet().forEach(entry -> {
                String stationId = entry.getKey();
                List<ArrivalPrediction> stationArrivals = entry.getValue();
                String stationKey = "Station_" + normalize(stationId);

                stationArrivals.forEach(a -> {
                    if (a.getDestinationNaptanId() != null && a.getDestinationNaptanId().equals(stationId)) {
                        a.setTowards(CHECK_FRONT_OF_TRAIN);
                        a.setDestinationName(null);
                        a.setDestinationNaptanId("unknown");
                        if (a.getDirection() == null || a.getDirection().trim().isEmpty()) {
                            a.setDirection(routeDirectionResolver.resolveDepartingDirection(stationId, a.getLineId()));
                        }
                    }
                });

                StationPredictions station = StationPredictions.builder()
                        .stationId(stationId)
                        .stationName(stationArrivals.get(0).getStationName())
                        .lastUpdatedTime(now)
                        .lines(new HashMap<>())
                        .build();

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
                            .collect(Collectors.groupingBy(a -> {
                                String dir = a.getDirection();
                                if (dir == null || dir.trim().isEmpty()) {
                                    String plat = a.getPlatformName() != null ? a.getPlatformName().toLowerCase() : "";
                                    return plat.contains("inbound") ? "inbound" : "outbound";
                                }
                                return dir.toLowerCase();
                            }));

                    byDirection.forEach((direction, directionArrivals) -> {
                        List<PredictionItem> items = directionArrivals.stream()
                                .filter(a -> !isFarFutureUnassigned(a))
                                .filter(a -> !isLongDeparted(a))
                                .map(this::toPredictionItem)
                                .sorted(Comparator.comparing(PredictionItem::getExpectedArrival,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                                .limit(10)
                                .collect(Collectors.toList());

                        if (!items.isEmpty()) {
                            DirectionPredictions directionPredictions = DirectionPredictions.builder()
                                    .predictions(items)
                                    .build();
                            lineData.getDirections().put(direction, directionPredictions);
                        }
                    });
                });

                pruneToFitFCM(station);
                stationGroups.put(stationKey, station);
            });

            return stationGroups;
        }

        private void pruneToFitFCM(StationPredictions station) {
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(station);
                if (bytes.length <= 4000) {
                    return;
                }
                while (bytes.length > 4000) {
                    PredictionItem furthestPrediction = null;
                    DirectionPredictions targetGroup = null;
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
                        break;
                    }
                }
            } catch (Exception e) {
                // parity oracle: swallow like the original
            }
        }

        private PredictionItem toPredictionItem(ArrivalPrediction arrival) {
            String towards = arrival.getTowards() != null ? arrival.getTowards().trim() : "";
            String rawName = (!towards.isEmpty() && !towards.equalsIgnoreCase("null"))
                    ? towards
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

        private static final Set<String> LATE_PLATFORM_MODES = Set.of("overground", "dlr", "elizabeth-line");
        private static final long UNASSIGNED_PLATFORM_MAX_MINUTES = 20;
        private static final Set<String> UNASSIGNED_RAW_VALUES = Set.of("null", "unknown", "platform unknown", "no platform");
        private static final Duration DEPARTED_CUTOFF = Duration.ofMinutes(2);

        private boolean isLongDeparted(ArrivalPrediction arrival) {
            ZonedDateTime eta = arrival.getExpectedArrival();
            return eta != null && Duration.between(eta, ZonedDateTime.now()).compareTo(DEPARTED_CUTOFF) > 0;
        }

        private boolean isFarFutureUnassigned(ArrivalPrediction arrival) {
            String mode = arrival.getModeName();
            if (mode == null || !LATE_PLATFORM_MODES.contains(mode.toLowerCase())) return false;
            String p = arrival.getPlatformName();
            String rp = p == null ? "" : p.trim().toLowerCase();
            if (!rp.isEmpty() && !UNASSIGNED_RAW_VALUES.contains(rp)) return false;
            if (arrival.getExpectedArrival() == null) return true;
            return Duration.between(ZonedDateTime.now(), arrival.getExpectedArrival()).toMinutes() > UNASSIGNED_PLATFORM_MAX_MINUTES;
        }

        public String getPresentablePlatform(String mode, String rawPlatform) {
            boolean isBus = "bus".equalsIgnoreCase(mode);
            String rp = rawPlatform == null ? "" : rawPlatform.trim().toLowerCase();
            if (rp.isEmpty() || rp.equals("null") || rp.equals("unknown") || rp.equals("platform unknown") || rp.equals("no platform")) {
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
            if (p.matches("[A-Za-z]") || p.matches("\\d+[A-Za-z]+")) {
                return "Platform " + p.toUpperCase();
            }
            return p;
        }
    }
}
