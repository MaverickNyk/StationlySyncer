package com.stationly.backend.service.predictionsources;

import com.stationly.backend.model.ArrivalDeparture;
import com.stationly.backend.model.ArrivalPrediction;
import com.stationly.backend.service.DataTransformationService;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Everything a source needs to build one station's lines for one mode cycle.
 * The countdown arrivals are always provided (possibly empty for a planned
 * board station in a quiet hour); board entries are present only for stations
 * the ArrivalDeparturesFetchService planned this cycle.
 */
@Value
@Builder
public class StationPredictionContext {

    String stationId;

    /** This station's rows from the bulk /Mode/{mode}/Arrivals fetch. */
    List<ArrivalPrediction> arrivals;

    /** lineId (lowercase) → raw board entries; empty when no board was fetched. */
    @Builder.Default
    Map<String, List<ArrivalDeparture>> arrivalDeparturesByLine = Map.of();

    /** lineId → display name from local station metadata (board stations only). */
    @Builder.Default
    Map<String, String> arrivalDeparturesLineNames = Map.of();

    /** Mode that planned the board fetch (elizabeth-line/overground); null otherwise. */
    String arrivalDeparturesMode;

    /** Local-DB commonName fallback for board stations with zero live arrivals. */
    String stationCommonName;

    /**
     * Shared formatting/filter helpers (platform label, destination cleaning,
     * departed/far-future rules). Passed by the transform service so every
     * source drops or renders a train by the exact same rules — and so the
     * sources need no Spring dependency back on the transform (no DI cycle).
     */
    DataTransformationService helpers;
}
