package com.stationly.backend.service.predictionsources;

import com.stationly.backend.model.ArrivalDeparture;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolved departure-board responses for one mode cycle, keyed by station.
 * Produced by ArrivalDeparturesFetchService.ArrivalDeparturesFetchPlan.await(); consumed by
 * DataTransformationService when building StationPredictionContexts.
 */
public record ArrivalDeparturesData(Map<String, StationData> stations) {

    private static final ArrivalDeparturesData EMPTY = new ArrivalDeparturesData(Map.of());

    public static ArrivalDeparturesData empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return stations.isEmpty();
    }

    public Set<String> stationIds() {
        return stations.keySet();
    }

    public StationData forStation(String stationId) {
        return stations.get(stationId);
    }

    /**
     * @param mode          mode cycle that planned the fetch (elizabeth-line/overground)
     * @param commonName    local-DB station name (payload name for arrival-less stations)
     * @param entriesByLine lineId (lowercase) → raw board entries ([] for a failed call)
     * @param lineNames     lineId → display name from local station metadata
     */
    public record StationData(String mode,
                                   String commonName,
                                   Map<String, List<ArrivalDeparture>> entriesByLine,
                                   Map<String, String> lineNames) {
    }
}
