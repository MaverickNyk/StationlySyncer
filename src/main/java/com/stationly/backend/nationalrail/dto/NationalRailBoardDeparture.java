package com.stationly.backend.nationalrail.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One departure row returned by the board engine's SQL query — the merged
 * baseline+live view for a station, ready for the engine to turn into a
 * {@code PredictionItem}. {@code effectiveDepartureMs} is estimated-else-
 * scheduled (already resolved in SQL), so the engine just formats + buckets.
 */
@Data
@Builder
public class NationalRailBoardDeparture {
    private long effectiveDepartureMs;
    private String platform;
    private String destinationCrs;
    private String destinationName;
    private String operatorCode;
}
