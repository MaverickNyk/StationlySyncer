package com.stationly.backend.nationalrail.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * Calling-point row of the SQL mirror (one per rid per station). Times are
 * epoch millis (resolved from Darwin "HH:mm" at ingest) so board ordering and
 * "future only" filtering are trivial comparisons. Live deltas mutate
 * {@code estimatedDepartureMs}, {@code actualDepartureMs}, {@code platform},
 * {@code cancelled}; the baseline sets the scheduled fields.
 */
@Data
@Builder
public class NationalRailCallingPointRow {
    private String rid;
    private String tiploc;
    private String crs;
    private LocalDate serviceDate;
    private Long scheduledDepartureMs;
    private Long estimatedDepartureMs;
    private Long actualDepartureMs;
    private String platform;
    private boolean publicDeparture;
    private boolean cancelled;
}
