package com.stationly.backend.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionItem {
    @JsonProperty("destId")
    private String destinationNaptanId;

    @JsonProperty("platform")
    @JsonAlias("platformName")
    private String platformName;

    @JsonProperty("eta")
    private String expectedArrival; // ISO-8601 string
    private String displayName; // towards or destinationName
}
