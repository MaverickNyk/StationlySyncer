package com.stationly.backend.nationalrail.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** Service-level row of the SQL mirror (one per rid). Destination is denormalised for the board query. */
@Data
@Builder
public class NationalRailServiceRow {
    private String rid;
    private LocalDate serviceDate;
    private String uid;
    private String toc;
    private String destinationCrs;
    private String destinationName;
    private boolean cancelled;
}
