package com.stationly.backend.nationalrail.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One calling point of a service in the daily Darwin timetable file.
 * {@code publicDeparture} = the activity flags mark this as a public pick-up
 * (it belongs on a departure board); non-public passes/set-downs are excluded.
 * Times are Darwin's raw "HH:mm" strings, resolved to epoch millis at ingest.
 */
@Data
@Builder
public class DarwinScheduleCall {
    private String tiploc;
    private String scheduledArrival;   // "HH:mm" or null (origin)
    private String scheduledDeparture; // "HH:mm" or null (destination)
    private boolean publicDeparture;
}
