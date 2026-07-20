package com.stationly.backend.service;

import com.stationly.backend.client.TflApi;
import com.stationly.backend.model.ArrivalDeparture;
import com.stationly.backend.model.Station;
import com.stationly.backend.service.predictionsources.ArrivalDeparturesData;
import com.stationly.backend.service.predictionsources.ArrivalDeparturesData.StationData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plans and starts the per-cycle departure-board fetches (user-confirmed
 * strategy): alongside the bulk /Mode/{mode}/Arrivals call, every SUBSCRIBED
 * station where elizabeth-line or overground passes — solo, terminus,
 * partial-terminus or mixed — gets one parallel
 * /StopPoint/{id}/ArrivalDepartures call per line of that mode. Calls start
 * BEFORE the bulk fetch blocks and resolve while it runs, so board data adds
 * no serial latency to the 30s cycle; the shared TflRateLimiter paces actual
 * dispatch.
 *
 * Budget: `tfl.arrival-departures.max-calls-per-cycle` caps the per-cycle calls. Route
 * TERMINI are always planned first (their board upgrade — real departures
 * instead of "Check Front of Train" — is the transformative one); remaining
 * stations rotate across cycles so each refreshes within a few cycles even
 * under the `all` polling strategy. A station skipped by the cap is served
 * from countdown arrivals exactly as before this feature existed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArrivalDeparturesFetchService {

    private final TflApi tflApi;
    private final LocalDatabaseService localDatabaseService;
    private final RouteDirectionResolver routeDirectionResolver;

    @Value("${tfl.arrival-departures.enabled:true}")
    private boolean arrivalDeparturesEnabled;

    @Value("${tfl.arrival-departures.max-calls-per-cycle:60}")
    private int maxCallsPerCycle;

    private static final Set<String> ARRIVAL_DEPARTURES_MODES = Set.of("elizabeth-line", "overground");
    private static final long STATION_CACHE_TTL_MS = 60 * 60 * 1000L; // station topology changes monthly

    private final Map<String, List<Station>> stationCache = new ConcurrentHashMap<>();
    private volatile long stationCacheLoadedAt = 0L;
    private final AtomicInteger rotation = new AtomicInteger();

    public ArrivalDeparturesFetchPlan planForMode(String mode, Set<String> subscribedFilter) {
        if (!arrivalDeparturesEnabled || mode == null || !ARRIVAL_DEPARTURES_MODES.contains(mode.toLowerCase(Locale.ROOT))) {
            return ArrivalDeparturesFetchPlan.EMPTY;
        }
        String modeLower = mode.toLowerCase(Locale.ROOT);
        List<Station> candidates = stationsForMode(modeLower).stream()
                .filter(s -> subscribedFilter == null || subscribedFilter.contains(s.getNaptanId()))
                .toList();
        if (candidates.isEmpty()) {
            return ArrivalDeparturesFetchPlan.EMPTY;
        }

        // Termini first; the rest rotate so the cap starves nobody forever.
        List<Station> termini = new ArrayList<>();
        List<Station> through = new ArrayList<>();
        Map<String, Map<String, String>> linesByStation = new HashMap<>(); // naptanId -> lineId -> lineName
        for (Station s : candidates) {
            Map<String, String> lines = arrivalDeparturesLinesOf(s, modeLower);
            if (lines.isEmpty()) continue;
            linesByStation.put(s.getNaptanId(), lines);
            boolean isTerminus = lines.keySet().stream()
                    .anyMatch(lineId -> routeDirectionResolver.resolveDepartingDirection(s.getNaptanId(), lineId) != null);
            (isTerminus ? termini : through).add(s);
        }
        if (linesByStation.isEmpty()) {
            return ArrivalDeparturesFetchPlan.EMPTY;
        }
        List<Station> ordered = new ArrayList<>(termini);
        if (!through.isEmpty()) {
            int offset = Math.floorMod(rotation.getAndIncrement(), through.size());
            for (int i = 0; i < through.size(); i++) {
                ordered.add(through.get((offset + i) % through.size()));
            }
        }

        int budget = maxCallsPerCycle;
        int skippedStations = 0;
        List<Station> planned = new ArrayList<>();
        for (Station s : ordered) {
            int cost = linesByStation.get(s.getNaptanId()).size();
            if (cost <= budget) { // all-or-nothing per station: merge semantics stay whole-line
                planned.add(s);
                budget -= cost;
            } else {
                skippedStations++;
            }
        }
        if (planned.isEmpty()) {
            return ArrivalDeparturesFetchPlan.EMPTY;
        }

        int totalCalls = maxCallsPerCycle - budget;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, totalCalls));
        Map<String, PlannedStation> plans = new HashMap<>();
        for (Station s : planned) {
            Map<String, String> lines = linesByStation.get(s.getNaptanId());
            Map<String, CompletableFuture<List<ArrivalDeparture>>> futures = new HashMap<>();
            for (String lineId : lines.keySet()) {
                futures.put(lineId, CompletableFuture.supplyAsync(
                        () -> tflApi.getArrivalDepartures(s.getNaptanId(), lineId), executor));
            }
            plans.put(s.getNaptanId(), new PlannedStation(modeLower, s.getCommonName(), futures, lines));
        }
        log.info("SYNC: 🚉 [{}] ArrivalDepartures plan: {} stations / {} calls ({} termini{}).",
                modeLower, planned.size(), totalCalls, termini.size(),
                skippedStations > 0 ? ", " + skippedStations + " skipped by cap" : "");
        return new ArrivalDeparturesFetchPlan(plans, executor);
    }

    private List<Station> stationsForMode(String modeLower) {
        long now = System.currentTimeMillis();
        if (now - stationCacheLoadedAt > STATION_CACHE_TTL_MS) {
            for (String m : ARRIVAL_DEPARTURES_MODES) {
                stationCache.put(m, localDatabaseService.getStationsByMode(m));
            }
            stationCacheLoadedAt = now;
            log.info("SYNC: 🚉 ArrivalDepartures station cache refreshed: {}",
                    stationCache.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue().size())
                            .reduce((a, b) -> a + ", " + b).orElse("empty"));
        }
        return stationCache.getOrDefault(modeLower, List.of());
    }

    /** lineId (lowercase) → display name for this station's lines of the given mode. */
    private Map<String, String> arrivalDeparturesLinesOf(Station station, String modeLower) {
        Map<String, String> lines = new HashMap<>();
        if (station.getModes() == null) return lines;
        station.getModes().forEach((modeName, group) -> {
            if (modeName == null || !modeName.toLowerCase(Locale.ROOT).equals(modeLower)) return;
            if (group == null || group.getLines() == null) return;
            group.getLines().forEach((lineId, details) -> {
                if (lineId == null || lineId.isBlank()) return;
                lines.put(lineId.toLowerCase(Locale.ROOT),
                        details != null && details.getName() != null ? details.getName() : null);
            });
        });
        return lines;
    }

    private record PlannedStation(String mode,
                                  String commonName,
                                  Map<String, CompletableFuture<List<ArrivalDeparture>>> futures,
                                  Map<String, String> lineNames) {
    }

    /** In-flight board fetches for one mode cycle; await() joins them into ArrivalDeparturesData. */
    public static final class ArrivalDeparturesFetchPlan {

        static final ArrivalDeparturesFetchPlan EMPTY = new ArrivalDeparturesFetchPlan(Map.of(), null);

        private final Map<String, PlannedStation> stations;
        private final ExecutorService executor;

        private ArrivalDeparturesFetchPlan(Map<String, PlannedStation> stations, ExecutorService executor) {
            this.stations = stations;
            this.executor = executor;
        }

        public boolean isEmpty() {
            return stations.isEmpty();
        }

        /** Joins every in-flight call (failed calls yield empty boards → countdown fallback). */
        public ArrivalDeparturesData await() {
            if (stations.isEmpty()) {
                return ArrivalDeparturesData.empty();
            }
            Map<String, StationData> resolved = new HashMap<>();
            stations.forEach((naptanId, plan) -> {
                Map<String, List<ArrivalDeparture>> entriesByLine = new HashMap<>();
                plan.futures().forEach((lineId, future) -> {
                    List<ArrivalDeparture> entries;
                    try {
                        entries = future.join();
                    } catch (Exception e) {
                        entries = List.of(); // client already degrades; belt and braces
                    }
                    entriesByLine.put(lineId, entries != null ? entries : List.of());
                });
                resolved.put(naptanId, new StationData(plan.mode(), plan.commonName(), entriesByLine, plan.lineNames()));
            });
            if (executor != null) {
                executor.shutdown();
            }
            return new ArrivalDeparturesData(resolved);
        }
    }
}
