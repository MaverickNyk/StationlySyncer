package com.stationly.backend.nationalrail.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One row of an OpenLDBWS departure board ({@code GetArrDepBoardWithDetails}),
 * as parsed off the SOAP response. This is the authoritative, self-contained
 * board Darwin serves on request — the source we assemble each push from once a
 * Push Port TS tells us the station changed.
 *
 * Times are Darwin's raw strings: {@code scheduledDeparture} is "HH:mm";
 * {@code estimatedDeparture} is "On time" | "HH:mm" | "Delayed" | "Cancelled" |
 * "Starts here" | "No report" — resolved to an ISO instant by the assembler.
 */
@Data
@Builder
public class DarwinServiceBoardRow {
    private String destinationCrs;
    private String destinationName;
    private String scheduledDeparture; // std, "HH:mm"
    private String estimatedDeparture; // etd, see class doc
    private String platform;
    private String operatorCode;       // TOC code, e.g. "CH" (Chiltern)
    private String operatorName;
    private boolean cancelled;
}
