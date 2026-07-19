package com.stationly.backend.service.predictionsources;

import com.stationly.backend.model.ArrivalPrediction;
import com.stationly.backend.model.DirectionPredictions;
import com.stationly.backend.model.LineData;
import com.stationly.backend.model.PredictionItem;
import com.stationly.backend.service.DataTransformationService;
import com.stationly.backend.service.RouteDirectionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The pre-factory transform logic for TfL's Countdown arrivals feed —
 * tube/dlr/bus/tram, and the universal fallback for any line the departure
 * board cannot serve. Extracted VERBATIM from
 * DataTransformationService.transformToStationGroups (zero-regression
 * contract: behavior here must not drift from what shipped before the
 * factory existed).
 */
@Component
@RequiredArgsConstructor
public class TubeDlrBusTramMixPredictionSource implements PredictionSource {

    private final RouteDirectionResolver routeDirectionResolver;

    static final String CHECK_FRONT_OF_TRAIN = "Check Front of Train";

    @Override
    public String name() {
        return "tube-dlr-bus-tram-mix";
    }

    @Override
    public boolean supports(StationPredictionContext ctx) {
        return true; // universal fallback — the factory consults it last
    }

    @Override
    public Map<String, LineData> buildLines(StationPredictionContext ctx) {
        String stationId = ctx.getStationId();
        List<ArrivalPrediction> stationArrivals = ctx.getArrivals();
        DataTransformationService helpers = ctx.getHelpers();
        Map<String, LineData> lines = new HashMap<>();

        // Terminus rule (mirrors tfl.gov.uk and stationly-backend): a train
        // whose destination is this very station is arriving to turn
        // around. It IS a future departure, but its outbound destination is
        // unknown until TfL assigns the return working at the platform — so
        // relabel it "Check Front of Train" (never drop; keyed on the
        // naptanId, not the name) and re-bucket it into the line's single
        // departing direction, since the raw entry carries none.
        stationArrivals.forEach(a -> {
            if (a.getDestinationNaptanId() != null && a.getDestinationNaptanId().equals(stationId)) {
                a.setTowards(CHECK_FRONT_OF_TRAIN);
                a.setDestinationName(null);
                // "unknown" (not null) so FCM payloads carry the same destId
                // the backend REST path emits for unknown destinations.
                a.setDestinationNaptanId("unknown");
                if (a.getDirection() == null || a.getDirection().trim().isEmpty()) {
                    a.setDirection(routeDirectionResolver.resolveDepartingDirection(stationId, a.getLineId()));
                }
            }
        });

        // Group arrivals by LineId
        Map<String, List<ArrivalPrediction>> byLine = stationArrivals.stream()
                .filter(a -> a.getLineId() != null)
                .collect(Collectors.groupingBy(ArrivalPrediction::getLineId));

        byLine.forEach((lineId, lineArrivals) -> {
            LineData lineData = lines.computeIfAbsent(lineId, k -> LineData.builder()
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
                        .filter(a -> !helpers.isFarFutureUnassigned(a))
                        .filter(a -> !helpers.isLongDeparted(a))
                        .map(helpers::toPredictionItem)
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

        return lines;
    }
}
