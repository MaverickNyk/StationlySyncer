package com.stationly.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Station {

    private String naptanId;
    private String commonName;
    private double lat;
    private double lon;
    private String geoHash;
    private String stopType;
    // Optional fields from TfL API
    private String indicator;
    private String stopLetter;
    private String lastUpdatedTime;
    private String icsCode;
    private String towards;
    private String compassPoint;
    private String stationNaptan;

    // Map of modes serving this station: ModeName -> ModeGroup
    @Builder.Default
    private Map<String, ModeGroup> modes = new HashMap<>();

    // Search keys for filtering
    @Builder.Default
    private List<String> searchKeys = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModeGroup {
        private String modeName;
        // Map of lines in this mode: LineId -> LineDetails
        @Builder.Default
        private Map<String, LineDetails> lines = new HashMap<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineDetails {
        private String id;
        private String name;
        // Direction identifiers (e.g., inbound, outbound)
        @Builder.Default
        private List<String> directions = new ArrayList<>();
    }
}
