package com.stationly.backend.service.predictionsources;

import com.stationly.backend.model.ArrivalDeparture;
import com.stationly.backend.model.ArrivalPrediction;
import com.stationly.backend.model.DirectionPredictions;
import com.stationly.backend.model.LineData;
import com.stationly.backend.model.PredictionItem;
import com.stationly.backend.service.DataTransformationService;
import com.stationly.backend.service.RouteDirectionResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prediction source for stations served by TfL's rail-style departures board
 * (/StopPoint/{id}/ArrivalDepartures — Elizabeth line + London Overground).
 * Java mirror of stationly-backend's ElizabethOvergroundPredictionSource:
 * same row filters (Cancelled/NotStoppingAtStation dropped, Delayed exempt
 * from the departed cutoff, far-future-unassigned dropped) and the same
 * CORRECTED direction chain (destination map first — see the Romford note
 * below). A line whose board yields zero usable rows falls back per-line to
 * its countdown arrivals, so the board can only add, never blank.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ElizabethOvergroundPredictionSource implements PredictionSource {

    private final RouteDirectionResolver routeDirectionResolver;
    private final TubeDlrBusTramMixPredictionSource countdownFallback;

    /** Rows TfL marks as not boardable at this station. */
    private static final Set<String> SKIPPED_DEPARTURE_STATUSES = Set.of("Cancelled", "NotStoppingAtStation");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    @Override
    public String name() {
        return "arrival-departures";
    }

    @Override
    public boolean supports(StationPredictionContext ctx) {
        // The fetch planner decides which stations get a board this cycle
        // (subscribed ∩ XR/OG, termini first under the per-cycle cap);
        // this source serves exactly those.
        return !ctx.getArrivalDeparturesByLine().isEmpty();
    }

    /** One usable board entry, held until direction resolution completes. */
    private static final class BoardRow {
        String lineId;
        String eta;
        String rawPlatform;
        String platform;
        String destId;
        String displayName;
        String direction = "";
    }

    @Override
    public Map<String, LineData> buildLines(StationPredictionContext ctx) {
        String stationId = ctx.getStationId();
        DataTransformationService helpers = ctx.getHelpers();

        // Direction maps learned from the countdown arrivals (which DO carry
        // a direction), conflict-dropped: a destination or platform seen with
        // both labels teaches nothing. The DESTINATION map leads the chain —
        // it mirrors prod's per-train raw labels even where TfL's labels
        // disagree on one physical platform (Romford Platform 5: Gidea Park
        // workings labelled inbound, Shenfield trains outbound; a
        // platform-first lookup flipped the Shenfield group vs prod).
        Map<String, String> dirByDest = new HashMap<>();
        Map<String, String> dirByPlatform = new HashMap<>();
        for (ArrivalPrediction a : ctx.getArrivals()) {
            if (a.getDirection() == null || a.getDirection().trim().isEmpty()) continue;
            String dir = a.getDirection().trim().toLowerCase();
            if (a.getDestinationNaptanId() != null) {
                mergeConflictDropped(dirByDest, a.getDestinationNaptanId(), dir);
            }
            String plat = a.getPlatformName() == null ? "" : a.getPlatformName().trim();
            if (!DataTransformationService.isUnassignedPlatform(plat)) {
                mergeConflictDropped(dirByPlatform, plat, dir);
            }
        }

        // TfL transiently emits the same working twice mid-refresh (observed
        // live in both feeds at Abbey Wood 2026-07-19) — identical rows are
        // collapsed. Two real trains can't share one platform at one minute.
        Set<String> seenRows = new LinkedHashSet<>();
        List<BoardRow> rows = new ArrayList<>();
        ctx.getArrivalDeparturesByLine().forEach((lineId, entries) -> {
            for (ArrivalDeparture entry : entries) {
                BoardRow row = toBoardRow(stationId, lineId, ctx.getArrivalDeparturesMode(), entry, dirByDest, dirByPlatform, helpers);
                if (row != null && seenRows.add(row.lineId + "|" + row.destId + "|" + row.eta + "|" + row.rawPlatform)) {
                    rows.add(row);
                }
            }
        });

        // Pass 2: platforms on these railways are usually direction-bound —
        // rows the confident signals missed inherit the direction already
        // resolved on the same REAL platform (conflict-dropped, so a
        // mixed-direction platform donates nothing).
        Map<String, String> platformDirs = new HashMap<>();
        for (BoardRow r : rows) {
            if (!r.direction.isEmpty() && !DataTransformationService.isUnassignedPlatform(r.rawPlatform)) {
                mergeConflictDropped(platformDirs, r.rawPlatform, r.direction);
            }
        }
        for (BoardRow r : rows) {
            if (r.direction.isEmpty()) r.direction = valueOrEmpty(platformDirs.get(r.rawPlatform));
        }
        // Pass 3: line uniformity — if every resolved train on a line goes one
        // way, the unresolved ones go that way too; never invent a split TfL
        // gave no signal for.
        Map<String, String> uniformByLine = new HashMap<>();
        for (BoardRow r : rows) {
            if (!r.direction.isEmpty()) mergeConflictDropped(uniformByLine, r.lineId, r.direction);
        }
        for (BoardRow r : rows) {
            if (r.direction.isEmpty()) r.direction = valueOrEmpty(uniformByLine.get(r.lineId));
        }
        // Pass 4: at a terminus every departure leaves in the line's single
        // departing direction; 'outbound' is the last-resort default.
        for (BoardRow r : rows) {
            if (!r.direction.isEmpty()) continue;
            String departing = routeDirectionResolver.resolveDepartingDirection(stationId, r.lineId);
            r.direction = departing != null ? departing : "outbound";
        }

        Map<String, LineData> lines = new HashMap<>();
        Map<String, List<BoardRow>> rowsByLine = rows.stream().collect(Collectors.groupingBy(r -> r.lineId));
        rowsByLine.forEach((lineId, lineRows) -> {
            LineData lineData = LineData.builder()
                    .lineId(lineId)
                    .lineName(lineDisplayName(ctx, lineId))
                    .directions(new HashMap<>())
                    .build();
            Map<String, List<BoardRow>> byDirection = lineRows.stream().collect(Collectors.groupingBy(r -> r.direction));
            byDirection.forEach((direction, directionRows) -> {
                List<PredictionItem> items = directionRows.stream()
                        .sorted(Comparator.comparing(r -> r.eta, Comparator.nullsLast(Comparator.naturalOrder())))
                        .limit(10) // same per-direction budget as the countdown source
                        .map(r -> PredictionItem.builder()
                                .destinationNaptanId(r.destId)
                                .platformName(r.platform)
                                .expectedArrival(r.eta)
                                .displayName(r.displayName)
                                .build())
                        .collect(Collectors.toList());
                if (!items.isEmpty()) {
                    lineData.getDirections().put(direction, DirectionPredictions.builder().predictions(items).build());
                }
            });
            lines.put(lineId, lineData);
        });

        // Any line the board could not serve (endpoint down, late-night gap
        // where the last trains are all still inbound, a mode the board
        // doesn't know) falls back to its countdown arrivals — per line, so
        // one dead board never blanks the rest of the station.
        List<ArrivalPrediction> uncovered = ctx.getArrivals().stream()
                .filter(a -> a.getLineId() == null || !lines.containsKey(a.getLineId()))
                .collect(Collectors.toList());
        if (!uncovered.isEmpty()) {
            Set<String> uncoveredIds = uncovered.stream()
                    .map(a -> a.getLineId() == null ? "?" : a.getLineId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            log.warn("SYNC: ⚠️ [{}] No usable board rows for {} at {} — serving countdown arrivals for them.",
                    name(), String.join(",", uncoveredIds), stationId);
            StationPredictionContext fallbackCtx = StationPredictionContext.builder()
                    .stationId(stationId)
                    .arrivals(uncovered)
                    .helpers(helpers)
                    .build();
            lines.putAll(countdownFallback.buildLines(fallbackCtx));
        }

        return lines;
    }

    /** Filters one raw board entry; null when it must not reach the payload. */
    private BoardRow toBoardRow(String stationId, String lineId, String mode, ArrivalDeparture entry,
                                Map<String, String> dirByDest, Map<String, String> dirByPlatform,
                                DataTransformationService helpers) {
        // Entries carry no lineId today; if TfL ever starts sending one,
        // trust it over our per-line attribution.
        if (entry.getLineId() != null && !entry.getLineId().equalsIgnoreCase(lineId)) return null;

        if (entry.getDepartureStatus() != null && SKIPPED_DEPARTURE_STATUSES.contains(entry.getDepartureStatus())) return null;

        // Trains still inbound to a terminus have no departure time yet —
        // their future departure appears as a separate entry once TfL
        // assigns the return working, so dropping these loses nothing.
        ZonedDateTime eta = entry.getEstimatedTimeOfDeparture() != null
                ? entry.getEstimatedTimeOfDeparture()
                : entry.getScheduledTimeOfDeparture();
        if (eta == null) return null;

        // A "departure to here" is an arrival wearing the wrong hat.
        if (stationId.equals(entry.getDestinationNaptanId())) return null;

        // TfL curates its own board's lifecycle: a Delayed train keeps its
        // stale timestamps until it actually leaves. Exempt Delayed rows from
        // the skew filter; it still drops long-departed OnTime rows.
        boolean delayed = "Delayed".equals(entry.getDepartureStatus());
        if (!delayed && Duration.between(eta, ZonedDateTime.now()).compareTo(DataTransformationService.DEPARTED_CUTOFF) > 0) {
            return null;
        }

        String rawPlatform = entry.getPlatformName() == null ? "" : entry.getPlatformName().trim();
        // Board rows are deliberately NOT passed through the far-future-
        // unassigned rule: it exists for countdown snapshot noise, but board
        // rows are TfL-curated timetable — the board just withholds platforms
        // until ~15min out. Dropping them under-showed the horizon vs both
        // prod and tfl.gov.uk (verified live at Hackney Central / Highbury).

        BoardRow row = new BoardRow();
        row.lineId = lineId;
        row.eta = eta.format(ISO);
        row.rawPlatform = rawPlatform;
        row.platform = helpers.getPresentablePlatform(mode, rawPlatform);
        row.destId = entry.getDestinationNaptanId() != null ? entry.getDestinationNaptanId() : "unknown";
        // Same cleaner as the countdown source, so the same destination never
        // renders two different ways depending on which feed it came from.
        row.displayName = helpers.cleanDestinationName(entry.getDestinationName());

        // Direction pass 1 — confident signals only, CORRECTED order
        // (destination map first; see class doc).
        String direction = valueOrEmpty(entry.getDestinationNaptanId() != null ? dirByDest.get(entry.getDestinationNaptanId()) : null);
        if (direction.isEmpty() && rawPlatform.toLowerCase().contains("inbound")) direction = "inbound";
        if (direction.isEmpty() && !DataTransformationService.isUnassignedPlatform(rawPlatform)) {
            direction = valueOrEmpty(dirByPlatform.get(rawPlatform));
        }
        row.direction = direction;
        return row;
    }

    private String lineDisplayName(StationPredictionContext ctx, String lineId) {
        // Display-name parity: prefer the lineName the countdown arrivals
        // carry (what payloads always used), then local line metadata, then a
        // capitalised lineId as the last resort.
        String fromArrivals = ctx.getArrivals().stream()
                .filter(a -> lineId.equalsIgnoreCase(a.getLineId()) && a.getLineName() != null)
                .map(ArrivalPrediction::getLineName)
                .findFirst().orElse(null);
        if (fromArrivals != null) return fromArrivals;
        String fromMetadata = ctx.getArrivalDeparturesLineNames().get(lineId);
        if (fromMetadata != null && !fromMetadata.isBlank()) return fromMetadata;
        return lineId.isEmpty() ? lineId : Character.toUpperCase(lineId.charAt(0)) + lineId.substring(1);
    }

    /** First label wins; a key later seen with a different label is dropped ("") — it teaches nothing. */
    private static void mergeConflictDropped(Map<String, String> map, String key, String value) {
        String seen = map.get(key);
        if (seen == null) {
            map.put(key, value);
        } else if (!seen.isEmpty() && !seen.equals(value)) {
            map.put(key, "");
        }
    }

    private static String valueOrEmpty(String s) {
        return s == null ? "" : s;
    }
}
