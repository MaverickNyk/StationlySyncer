package com.stationly.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.ZonedDateTime;

/**
 * One row of TfL's rail-style live departure board
 * (/StopPoint/{id}/ArrivalDepartures) — Elizabeth line and London Overground
 * only. Unlike the Countdown ArrivalPrediction it carries true DEPARTURE
 * times, the outbound destination at termini, and a per-train
 * departureStatus (OnTime/Delayed/Cancelled/NotStoppingAtStation).
 * NOTE: despite the docs advertising ArrivalDepartureWithLine, live entries
 * carry no lineId — attribution comes from the per-line request.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArrivalDeparture {
    private String naptanId;
    private String stationName;
    private String lineId; // not sent today; trusted over per-line attribution if TfL adds it
    private String platformName;
    private String destinationName;
    private String destinationNaptanId;
    private String departureStatus;
    private ZonedDateTime estimatedTimeOfDeparture;
    private ZonedDateTime scheduledTimeOfDeparture;
}
