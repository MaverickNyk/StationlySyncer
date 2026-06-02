package com.stationly.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineStatusResponse {
    private String id;
    private String name;
    private String mode;
    private String statusSeverityDescription;
    private String reason;
    // Replication watermark — Object so toObject tolerates string|number across
    // the cutover. Coerce with TimeUtils.toEpochMs; writers set a Long.
    private Object lastUpdatedTime;
}
