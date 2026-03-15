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
    private String lastUpdatedTime;
}
